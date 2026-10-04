package libs.libs.libs.adb.connect

import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

public class AdbStream(
    private val connection: AdbConnection,
    public val localId: Int,
    public val remoteId: Int,
    private val maxPayloadSize: Int = AdbCommand.MAX_PAYLOAD,
    initialAvailableSendBytes: Long = maxPayloadSize.toLong()
) {
    private val isClosed = AtomicBoolean(false)
    private val delayedAckEnabled: Boolean = connection.hasFeature("delayed_ack")
    private val streamWriteMutex = Mutex()

    private val availableSendBytes = AtomicLong(
        if (delayedAckEnabled) initialAvailableSendBytes else 0L
    )

    internal val incomingChannel = Channel<AdbPacket>(Channel.UNLIMITED)
    internal val ackQuotaChannel = Channel<Int>(Channel.UNLIMITED)

    private var currentWritePacket: AdbPacket? = null
    private var packetReadOffset = 0

    internal fun onOkayReceived(packet: AdbPacket) {
        val grantedBytes = if (delayedAckEnabled && packet.payload.size == 4) {
            ByteBuffer.wrap(packet.payload).order(ByteOrder.LITTLE_ENDIAN).int
        } else {
            0
        }
        ackQuotaChannel.trySend(grantedBytes)
    }

    public suspend fun read(): ByteArray? = readNextChunk()

    /**
     * 读取下一个数据块（优先消费当前未读完的包缓冲）
     */
    public suspend fun readNextChunk(): ByteArray? = withContext(Dispatchers.IO) {
        if (isClosed.get()) return@withContext null

        // 1. 如果当前包还有剩余字节，直接返回剩余部分
        val current = currentWritePacket
        if (current != null) {
            val remaining = current.payload.size - packetReadOffset
            val chunk = current.payload.copyOfRange(packetReadOffset, current.payload.size)
            currentWritePacket = null
            packetReadOffset = 0
            if (delayedAckEnabled) {
                sendAckForBytes(remaining)
            }
            return@withContext chunk
        }

        // 2. 拉取新数据包
        val packet = incomingChannel.receiveCatching().getOrNull() ?: return@withContext null
        if (packet.command == AdbCommand.CMD_CLSE) {
            closeInternal()
            return@withContext null
        }

        val data = packet.payload
        sendAckForBytes(data.size)
        return@withContext data
    }

    /**
     * 按 Byte 数组填充读取
     */
    public suspend fun read(sink: ByteArray, offset: Int = 0, byteCount: Int = sink.size): Int = withContext(Dispatchers.IO) {
        if (isClosed.get()) return@withContext -1

        var packet = currentWritePacket
        if (packet == null) {
            val nextPacket = incomingChannel.receiveCatching().getOrNull() ?: return@withContext -1
            if (nextPacket.command == AdbCommand.CMD_CLSE) {
                closeInternal()
                return@withContext -1
            }
            packet = nextPacket
            currentWritePacket = packet
            packetReadOffset = 0

            // 标准 ADB 模式下，收到并解包时立即回传 CMD_OKAY 确认，解除对端阻塞
            if (!delayedAckEnabled) {
                val okayPacket = AdbPacket(AdbCommand.CMD_OKAY, localId, remoteId)
                connection.sendPacket(okayPacket)
            }
        }

        val remainingInPacket = packet.payload.size - packetReadOffset
        val bytesToRead = minOf(byteCount, remainingInPacket)

        System.arraycopy(packet.payload, packetReadOffset, sink, offset, bytesToRead)
        packetReadOffset += bytesToRead

        if (packetReadOffset >= packet.payload.size) {
            currentWritePacket = null
            packetReadOffset = 0
        }

        // delayed_ack 模式下，按实际消费的字节数累计回传窗口 credit
        if (delayedAckEnabled) {
            sendAckForBytes(bytesToRead)
        }

        return@withContext bytesToRead
    }

    private suspend fun sendAckForBytes(byteCount: Int) {
        if (delayedAckEnabled) {
            val ackPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteCount).array()
            val okayPacket = AdbPacket(AdbCommand.CMD_OKAY, localId, remoteId, ackPayload)
            connection.sendPacket(okayPacket)
        }
    }

    public suspend fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) = withContext(Dispatchers.IO) {
        if (isClosed.get()) throw IOException("AdbStream $localId is closed")

        // 加锁保护写操作，防止并发写时 ACK 错乱
        streamWriteMutex.withLock {
            var remaining = length
            var currentOffset = offset

            while (remaining > 0) {
                if (delayedAckEnabled) {
                    while (availableSendBytes.get() <= 0) {
                        val grantedCredit = ackQuotaChannel.receiveCatching().getOrNull()
                            ?: throw IOException("Stream $localId closed while waiting for delayed ACK")
                        availableSendBytes.addAndGet(grantedCredit.toLong())
                    }
                }

                val currentWindow = if (delayedAckEnabled) availableSendBytes.get().toInt() else maxPayloadSize
                val chunkSize = minOf(remaining, maxPayloadSize, currentWindow.coerceAtLeast(1))

                val payload = if (offset == 0 && length == data.size && chunkSize == data.size) {
                    data
                } else {
                    data.copyOfRange(currentOffset, currentOffset + chunkSize)
                }

                val writePacket = AdbPacket(AdbCommand.CMD_WRTE, localId, remoteId, payload)
                connection.sendPacket(writePacket)

                if (delayedAckEnabled) {
                    availableSendBytes.addAndGet(-chunkSize.toLong())
                } else {
                    val ack = ackQuotaChannel.receiveCatching()
                    if (ack.isFailure || isClosed.get()) {
                        throw IOException("Stream $localId closed while waiting for write ACK")
                    }
                }

                remaining -= chunkSize
                currentOffset += chunkSize
            }
        }
    }

    public suspend fun close() = withContext(Dispatchers.IO) {
        if (!isClosed.compareAndSet(false, true)) return@withContext
        performCleanup()

        val closePacket = AdbPacket(AdbCommand.CMD_CLSE, localId, remoteId)
        try {
            connection.sendPacket(closePacket)
        } catch (_: Exception) {}
    }

    internal fun closeInternal() {
        if (isClosed.compareAndSet(false, true)) {
            performCleanup()
        }
    }

    private fun performCleanup() {
        incomingChannel.close()
        ackQuotaChannel.close()
        connection.removeStream(localId)
    }
}

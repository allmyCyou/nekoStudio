package libs.libs.libs.adb.connect

import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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

    // 是否开启了 delayed_ack
    private val delayedAckEnabled: Boolean = connection.hasFeature("delayed_ack")

    // 可用发送额度（字节数）。开启 delayed_ack 时优先使用握手时 OKAY 返回的配额
    private val availableSendBytes = AtomicLong(
        if (delayedAckEnabled) initialAvailableSendBytes else 0L
    )

    // 接收解复用分发的 WRTE / CLSE 报文包
    internal val incomingChannel = Channel<AdbPacket>(64)

    // 接收 OKAY 回执中的 ACK 归还额度 (Int)
    internal val ackQuotaChannel = Channel<Int>(Channel.UNLIMITED)

    // 用于部分读取未消耗完的 WRTE 包缓存
    private var currentWritePacket: AdbPacket? = null
    private var packetReadOffset = 0

    /**
     * 底层解复用 Loop 收到 OKAY 时回调
     */
    internal fun onOkayReceived(packet: AdbPacket) {
        val grantedBytes = if (delayedAckEnabled && packet.payload.size == 4) {
            ByteBuffer.wrap(packet.payload).order(ByteOrder.LITTLE_ENDIAN).int
        } else {
            0
        }
        ackQuotaChannel.trySend(grantedBytes)
    }

    /**
     * 无参读取方法（兼容历史调用如 AdbAbbClient / AdbShellClient）
     */
    public suspend fun read(): ByteArray? = readNextChunk()

    /**
     * 读取单个完整包的 Payload（针对单次交互场景，如 getprop/shell 命令）
     */
    public suspend fun readNextChunk(): ByteArray? = withContext(Dispatchers.IO) {
        if (isClosed.get()) return@withContext null

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
     * 字节数组精准读取（支持流式、部分读取，适用于文件传输/日志流）
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
        }

        val remainingInPacket = packet.payload.size - packetReadOffset
        val bytesToRead = minOf(byteCount, remainingInPacket)

        System.arraycopy(packet.payload, packetReadOffset, sink, offset, bytesToRead)
        packetReadOffset += bytesToRead

        // 当前包已全部读取完毕
        if (packetReadOffset >= packet.payload.size) {
            val totalSize = packet.payload.size
            currentWritePacket = null
            packetReadOffset = 0
            sendAckForBytes(totalSize)
        } else if (delayedAckEnabled && bytesToRead > 0) {
            // 在 delayed_ack 模式下，部分读取也要向对方回复增量消费 ACK
            sendAckForBytes(bytesToRead)
        }

        return@withContext bytesToRead
    }

    /**
     * 向设备回复 OKAY ACK
     */
    private suspend fun sendAckForBytes(byteCount: Int) {
        if (delayedAckEnabled) {
            val ackPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteCount).array()
            val okayPacket = AdbPacket(AdbCommand.CMD_OKAY, localId, remoteId, ackPayload)
            connection.sendPacket(okayPacket)
        } else {
            val okayPacket = AdbPacket(AdbCommand.CMD_OKAY, localId, remoteId)
            connection.sendPacket(okayPacket)
        }
    }

    /**
     * 写入数据，自动按 maxPayloadSize 切片并进行滑动窗口背压管理
     */
    public suspend fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) = withContext(Dispatchers.IO) {
        if (isClosed.get()) throw IOException("AdbStream $localId is closed")

        var remaining = length
        var currentOffset = offset

        while (remaining > 0) {
            val chunkSize = minOf(remaining, maxPayloadSize)

            if (delayedAckEnabled) {
                // delayed_ack 控流：无可用配额时挂起等待设备端返回 ACK 配额
                while (availableSendBytes.get() <= 0) {
                    val grantedCredit = ackQuotaChannel.receiveCatching().getOrNull()
                        ?: throw IOException("Stream $localId closed while waiting for delayed ACK")
                    availableSendBytes.addAndGet(grantedCredit.toLong())
                }
            }

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
                // 传统模式：写一个 WRTE 包必须挂起等待一个 OKAY 确认
                val ack = ackQuotaChannel.receiveCatching()
                if (ack.isFailure || isClosed.get()) {
                    throw IOException("Stream $localId closed while waiting for write ACK")
                }
            }

            remaining -= chunkSize
            currentOffset += chunkSize
        }
    }

    public suspend fun close() = withContext(Dispatchers.IO) {
        if (!isClosed.compareAndSet(false, true)) return@withContext
        closeInternal()

        val closePacket = AdbPacket(AdbCommand.CMD_CLSE, localId, remoteId)
        try {
            connection.sendPacket(closePacket)
        } catch (_: Exception) {}
    }

    internal fun closeInternal() {
        if (isClosed.compareAndSet(false, true)) {
            incomingChannel.close()
            ackQuotaChannel.close()
            connection.removeStream(localId)
        }
    }
}

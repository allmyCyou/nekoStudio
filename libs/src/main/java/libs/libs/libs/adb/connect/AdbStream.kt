package libs.libs.libs.adb.connect

import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
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
    private val delayedAckEnabled: Boolean = connection.hasFeature(AdbCommand.FEATURE_DELAYED_ACK)

    private val streamWriteMutex = Mutex()
    private val streamReadMutex = Mutex()

    private val availableSendBytes = AtomicLong(
        if (delayedAckEnabled) initialAvailableSendBytes else 0L
    )

    internal val incomingChannel = Channel<AdbPacket>(Channel.UNLIMITED)
    internal val ackQuotaChannel = Channel<Int>(Channel.UNLIMITED)

    private var currentWritePacket: AdbPacket? = null
    private var packetReadOffset = 0

    internal fun onOkayReceived(packet: AdbPacket) {
        val grantedBytes = if (delayedAckEnabled && packet.payload.size == 4) {
            java.nio.ByteBuffer.wrap(packet.payload)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .int
        } else {
            0
        }
        ackQuotaChannel.trySend(grantedBytes)
    }

    public suspend fun read(): ByteArray? = readNextChunk()

    public suspend fun readNextChunk(): ByteArray? = withContext(Dispatchers.IO) {
        if (isClosed.get()) return@withContext null

        streamReadMutex.withLock {
            if (isClosed.get()) return@withLock null

            val current = currentWritePacket
            if (current != null) {
                val remaining = current.payload.size - packetReadOffset
                val chunk = current.payload.copyOfRange(packetReadOffset, current.payload.size)
                currentWritePacket = null
                packetReadOffset = 0

                if (delayedAckEnabled) {
                    sendAckForBytes(remaining)
                }
                return@withLock chunk
            }

            val packet = incomingChannel.receiveCatching().getOrNull() ?: return@withLock null
            if (packet.command == AdbCommand.CMD_CLSE) {
                closeInternal()
                return@withLock null
            }

            val data = packet.payload

            if (delayedAckEnabled) {
                sendAckForBytes(data.size)
            } else {
                connection.sendPacket(AdbPacket.createOkay(localId, remoteId))
            }

            return@withLock data
        }
    }

    public suspend fun read(sink: ByteArray, offset: Int = 0, byteCount: Int = sink.size): Int = withContext(Dispatchers.IO) {
        if (isClosed.get()) return@withContext -1

        streamReadMutex.withLock {
            if (isClosed.get()) return@withLock -1

            var packet = currentWritePacket
            if (packet == null) {
                val nextPacket = incomingChannel.receiveCatching().getOrNull() ?: return@withLock -1
                if (nextPacket.command == AdbCommand.CMD_CLSE) {
                    closeInternal()
                    return@withLock -1
                }
                packet = nextPacket
                currentWritePacket = packet
                packetReadOffset = 0

                if (!delayedAckEnabled) {
                    connection.sendPacket(AdbPacket.createOkay(localId, remoteId))
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

            if (delayedAckEnabled) {
                sendAckForBytes(bytesToRead)
            }

            return@withLock bytesToRead
        }
    }

    private suspend fun sendAckForBytes(byteCount: Int) {
        if (delayedAckEnabled && byteCount > 0) {
            connection.sendPacket(AdbPacket.createOkay(localId, remoteId, ackBytes = byteCount))
        }
    }

    public suspend fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Unit = withContext(Dispatchers.IO) {
        if (isClosed.get()) throw IOException("AdbStream $localId is closed")

        streamWriteMutex.withLock {
            var remaining = length
            var currentOffset = offset

            while (remaining > 0) {
                if (isClosed.get()) throw IOException("AdbStream $localId was closed during write")

                if (delayedAckEnabled) {
                    while (availableSendBytes.get() <= 0) {
                        val grantedCredit = ackQuotaChannel.receiveCatching().getOrNull()
                            ?: throw IOException("Stream $localId closed while waiting for delayed ACK")
                        availableSendBytes.addAndGet(grantedCredit.toLong())
                    }
                    while (true) {
                        val extraCredit = ackQuotaChannel.tryReceive().getOrNull() ?: break
                        availableSendBytes.addAndGet(extraCredit.toLong())
                    }
                }

                val currentWindow = if (delayedAckEnabled) availableSendBytes.get().toInt() else maxPayloadSize
                val chunkSize = minOf(remaining, maxPayloadSize, currentWindow.coerceAtLeast(1))

                val payload = if (offset == 0 && length == data.size && chunkSize == data.size) {
                    data
                } else {
                    data.copyOfRange(currentOffset, currentOffset + chunkSize)
                }

                connection.sendPacket(AdbPacket.createWrite(localId, remoteId, payload))

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

    public suspend fun close(): Unit = withContext(Dispatchers.IO) {
        if (!isClosed.compareAndSet(false, true)) return@withContext
        performCleanup()

        try {
            connection.sendPacket(AdbPacket.createClose(localId, remoteId))
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

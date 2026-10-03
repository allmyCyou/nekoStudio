package libs.libs.libs.adb.connect

import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

public class AdbStream(
    private val connection: AdbConnection,
    public val localId: Int,
    public val remoteId: Int
) {
    @Volatile
    private var isClosed = false
    
    // 设置合理的 Channel 容量背压，避免海量日志场景下的 OOM
    internal val incomingChannel = Channel<AdbPacket>(64)
    
    internal val writeAckChannel = Channel<Unit>(Channel.CONFLATED)

    public suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext null

        for (packet in incomingChannel) {
            when (packet.command) {
                AdbCommand.CMD_WRTE -> {
                    val okayPacket = AdbPacket(
                        command = AdbCommand.CMD_OKAY,
                        arg0 = localId,
                        arg1 = remoteId,
                        payload = ByteArray(0)
                    )
                    connection.sendPacket(okayPacket)
                    return@withContext packet.payload
                }
                AdbCommand.CMD_CLSE -> {
                    closeInternal()
                    return@withContext null
                }
            }
        }
        null
    }

    public suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        if (isClosed) throw IllegalStateException("AdbStream $localId is closed")

        val writePacket = AdbPacket(
            command = AdbCommand.CMD_WRTE,
            arg0 = localId,
            arg1 = remoteId,
            payload = data
        )
        
        connection.sendPacket(writePacket)

        val ack = writeAckChannel.receiveCatching()
        if (ack.isFailure || isClosed) {
            throw IllegalStateException("Stream $localId closed while waiting for write ACK")
        }
    }

    public suspend fun close() = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext
        closeInternal()

        val closePacket = AdbPacket(
            command = AdbCommand.CMD_CLSE,
            arg0 = localId,
            arg1 = remoteId,
            payload = ByteArray(0)
        )
        try {
            connection.sendPacket(closePacket)
        } catch (_: Exception) {}
    }

    internal fun closeInternal() {
        if (isClosed) return
        isClosed = true
        incomingChannel.close()
        writeAckChannel.close()
        connection.removeStream(localId)
    }
}

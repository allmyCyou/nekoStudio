package libs.libs.libs.adb.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

public object SyncCommandV2 {
    public const val ID_STA2: String = "STA2"
    public const val ID_LSTA: String = "LSTA"
    public const val ID_LST2: String = "LST2"
    public const val ID_DNT2: String = "DNT2"
    public const val ID_SND2: String = "SND2"
    public const val ID_RCV2: String = "RCV2"

    public fun createRequestV2(
        id: String,
        mode: Int,
        flags: Int,
        remotePath: String
    ): ByteArray {
        val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
        val payloadLen = 12 + pathBytes.size

        val buffer = ByteBuffer.allocate(SyncCommand.HEADER_SIZE + payloadLen)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(id.toByteArray(Charsets.US_ASCII))
        buffer.putInt(payloadLen)
        buffer.putInt(mode)
        buffer.putInt(flags)
        buffer.putInt(pathBytes.size)
        buffer.put(pathBytes)

        return buffer.array()
    }
}

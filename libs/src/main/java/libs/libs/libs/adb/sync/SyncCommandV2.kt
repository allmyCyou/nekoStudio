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
        val buffer = ByteBuffer.allocate(16 + pathBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(id.toByteArray(Charsets.US_ASCII)) // 0..3 : ID
        buffer.putInt(mode)                           // 4..7 : mode
        buffer.putInt(flags)                          // 8..11: flags
        buffer.putInt(pathBytes.size)                 // 12..15: path_len
        buffer.put(pathBytes)                         // 16.. : remotePath

        return buffer.array()
    }
}

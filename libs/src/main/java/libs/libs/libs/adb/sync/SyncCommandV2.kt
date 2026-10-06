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
        
        // V2 报文总长度 = 8(Header) + 4(mode) + 4(flags) + pathBytes.size
        val buffer = ByteBuffer.allocate(SyncCommand.HEADER_SIZE + 8 + pathBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(id.toByteArray(Charsets.US_ASCII)) // 4 Bytes: 命令 ID
        buffer.putInt(pathBytes.size)                 // 4 Bytes: 仅填充路径字节长度
        buffer.putInt(mode)                           // 4 Bytes: mode
        buffer.putInt(flags)                          // 4 Bytes: flags
        buffer.put(pathBytes)                         // N Bytes: 路径字节内容 (无需再重复写入 pathBytes.size)

        return buffer.array()
    }
}

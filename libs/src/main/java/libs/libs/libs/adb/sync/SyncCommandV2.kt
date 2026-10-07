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

    /**
     * 构建 STA2 (Stat V2) / LST2 (List V2) 请求数据包
     * 格式: [4B ID][4B path_len][path_len 字节 remotePath]
     */
    public fun createStatOrListRequestV2(
        id: String,
        remotePath: String
    ): ByteArray {
        val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(SyncCommand.HEADER_SIZE + pathBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(id.toByteArray(Charsets.US_ASCII)) // 0..3 : ID
        buffer.putInt(pathBytes.size)                 // 4..7 : path_len
        buffer.put(pathBytes)                         // 8..  : remotePath

        return buffer.array()
    }

    /**
     * 构建 SND2 (Push V2) 请求数据包
     * 格式: [4B "SND2"][4B flags][4B path_len][path_len 字节 "$remotePath,$mode"]
     */
    public fun createSendRequestV2(
        remotePath: String,
        mode: Int,
        flags: Int
    ): ByteArray {
        val destinationStr = "$remotePath,$mode"
        val pathBytes = destinationStr.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(12 + pathBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(ID_SND2.toByteArray(Charsets.US_ASCII)) // 0..3 : "SND2"
        buffer.putInt(flags)                                // 4..7 : flags
        buffer.putInt(pathBytes.size)                       // 8..11: path_len
        buffer.put(pathBytes)                               // 12.. : "$remotePath,$mode"

        return buffer.array()
    }

    /**
     * 构建 RCV2 (Pull V2) 请求数据包
     * 格式: [4B "RCV2"][4B flags][4B path_len][path_len 字节 remotePath]
     */
    public fun createRecvRequestV2(
        remotePath: String,
        flags: Int
    ): ByteArray {
        val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(12 + pathBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put(ID_RCV2.toByteArray(Charsets.US_ASCII)) // 0..3 : "RCV2"
        buffer.putInt(flags)                                // 4..7 : flags
        buffer.putInt(pathBytes.size)                       // 8..11: path_len
        buffer.put(pathBytes)                               // 12.. : remotePath

        return buffer.array()
    }

    public fun createRequestV2(
        id: String,
        mode: Int,
        flags: Int,
        remotePath: String
    ): ByteArray = when (id) {
        ID_SND2 -> createSendRequestV2(remotePath, mode, flags)
        ID_RCV2 -> createRecvRequestV2(remotePath, flags)
        else -> createStatOrListRequestV2(id, remotePath)
    }
}

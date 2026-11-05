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

    /** V2 通用请求头：id + path_length + path */
    public fun createSyncRequest(id: String, remotePath: String): ByteArray {
        val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + pathBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII))
            .putInt(pathBytes.size)
            .put(pathBytes)
            .array()
    }

    /** SND2 专用：路径发送后，追加 mode + flags */
    public fun createSendV2(mode: Int, flags: Int): ByteArray {
        return ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(ID_SND2.toByteArray(Charsets.US_ASCII))
            .putInt(mode)
            .putInt(flags)
            .array()
    }

    /** RCV2 专用：路径发送后，追加 flags */
    public fun createRecvV2(flags: Int): ByteArray {
        return ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(ID_RCV2.toByteArray(Charsets.US_ASCII))
            .putInt(flags)
            .array()
    }
}
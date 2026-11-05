package libs.libs.libs.adb.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 解析 Sync v2 响应中的 stat payload（不含 id，含 error）
 * 对应 AOSP: struct sync_stat_v2 { id, error, dev, ino, mode, nlink, uid, gid, size, atime, mtime, ctime }
 */
public fun ByteBuffer.parseSyncStatV2(path: String): FileStatV2 {
    order(ByteOrder.LITTLE_ENDIAN)
    val error = int          // 4
    val dev = long           // 8
    val ino = long           // 8
    val mode = int           // 4
    val nlink = int          // 4
    val uid = int            // 4
    val gid = int            // 4
    val size = long          // 8
    val atime = long         // 8
    val mtime = long         // 8
    val ctime = long         // 8
    return FileStatV2(path, error, dev, ino, mode, nlink, uid, gid, size, atime, mtime, ctime)
}
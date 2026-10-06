package libs.libs.libs.adb.sync

public data class FileStatV2(
    val path: String,
    val error: Int,      // 0 表示成功，非 0 为 errno
    val dev: Long,
    val ino: Long,
    val mode: Int,
    val nlink: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,      // 64 位文件大小
    val atime: Long,
    val mtime: Long,
    val ctime: Long
) {
    val exists: Boolean get() = error == 0 && mode != 0
    val isDirectory: Boolean get() = (mode and FilePermissions.S_IFMT) == FilePermissions.S_IFDIR
    val isFile: Boolean get() = (mode and FilePermissions.S_IFMT) == FilePermissions.S_IFREG
    val isSymbolicLink: Boolean get() = (mode and FilePermissions.S_IFMT) == FilePermissions.S_IFLNK
}

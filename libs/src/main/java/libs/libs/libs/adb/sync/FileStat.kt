package libs.libs.libs.adb.sync

public data class FileStat(
    val path: String,
    val mode: Int,
    val size: Long,
    val mtime: Long
) {
    val exists: Boolean get() = mode != 0
    val isDirectory: Boolean get() = (mode and FilePermissions.S_IFDIR) != 0
    val isFile: Boolean get() = (mode and FilePermissions.S_IFREG) != 0

    public fun toFileStatV2(): FileStatV2 = FileStatV2(
        path = path,
        error = if (exists) 0 else 2, // ENOENT = 2
        dev = 0, ino = 0, mode = mode, nlink = 1,
        uid = 0, gid = 0, size = size,
        atime = mtime, mtime = mtime, ctime = mtime
    )
}

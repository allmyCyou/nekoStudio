package libs.libs.libs.adb.sync

public data class DirectoryEntry(
    val name: String,
    val mode: Int,
    val size: Long,
    val mtime: Long
) {
    val isDirectory: Boolean get() = (mode and FilePermissions.S_IFMT) == FilePermissions.S_IFDIR
    val isFile: Boolean get() = (mode and FilePermissions.S_IFMT) == FilePermissions.S_IFREG
    val isSymbolicLink: Boolean get() = (mode and FilePermissions.S_IFMT) == FilePermissions.S_IFLNK
}

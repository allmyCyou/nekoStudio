package libs.libs.libs.adb.sync

public data class DirectoryEntry(
    val name: String,
    val mode: Int,
    val size: Long,
    val mtime: Long
) {
    val isDirectory: Boolean get() = (mode and FilePermissions.S_IFDIR) != 0
    val isFile: Boolean get() = (mode and FilePermissions.S_IFREG) != 0
}

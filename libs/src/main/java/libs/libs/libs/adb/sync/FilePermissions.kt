package libs.libs.libs.adb.sync

public object FilePermissions {
    public const val S_IFMT: Int = 0xF000  // 文件类型掩码
    public const val S_IFLNK: Int = 0xA000 // 符号链接
    public const val S_IFREG: Int = 0x8000 // 普通文件
    public const val S_IFDIR: Int = 0x4000 // 目录

    public const val DEFAULT_MODE: Int = S_IFREG or 0x01B0 // 0660，标准 /storage/emulated/0/ 文件权限
}

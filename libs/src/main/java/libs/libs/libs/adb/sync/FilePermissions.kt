package libs.libs.libs.adb.sync

public object FilePermissions {
    public const val S_IFREG: Int = 0x8000 // 普通文件
    public const val S_IFDIR: Int = 0x4000 // 目录

    public const val DEFAULT_MODE: Int = S_IFREG or 0x01A4 // 0644
}

package libs.libs.libs.adb.abb

/**
 * 应用卸载可选项
 */
public data class AbbUninstallOptions(
    /** -k: 移除应用但保留其数据与缓存目录 */
    val keepData: Boolean = false,
    /** --user <USER_ID>: 仅针对特定 Android 用户卸载 (例如: 0 表示主用户, 999 表示分身) */
    val userId: Int? = null,
    /** --versionCode <VERSION_CODE>: 仅当应用的 versionCode 匹配时才执行卸载 (Android 10+) */
    val versionCode: Long? = null
) {
    public fun toArgs(): List<String> {
        val args = mutableListOf<String>()
        if (keepData) args.add("-k")
        if (userId != null) {
            args.add("--user")
            args.add(userId.toString())
        }
        if (versionCode != null) {
            args.add("--versionCode")
            args.add(versionCode.toString())
        }
        return args
    }
}

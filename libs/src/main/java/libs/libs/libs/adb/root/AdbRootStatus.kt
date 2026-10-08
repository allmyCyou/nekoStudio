package libs.libs.libs.adb.root

public enum class AdbRootStatus {
    /**
     * adbd 进程正在以 root 身份重启（此时底层 Socket/USB 通道会断开，需等待 1-3 秒后重新连接）
     */
    RESTARTING_AS_ROOT,

    /**
     * adbd 进程正在恢复为普通 shell 身份重启（同样会断开连接）
     */
    RESTARTING_AS_SHELL,

    /**
     * adbd 当前已经运行在 root 身份下
     */
    ALREADY_ROOT,

    /**
     * adbd 当前已经运行在 shell 身份下
     */
    ALREADY_SHELL,

    /**
     * 系统为 user 生产构建版本，adbd 拒绝提供 root 权限
     */
    DISABLED_IN_PRODUCTION,

    /**
     * 通信过程出错或无法解析响应
     */
    FAILED
}

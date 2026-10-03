package libs.libs.libs.adb.pair

public interface AdbPairing {
    /**
     * 发起无线配对
     * @param host 设备 IP 地址
     * @param port 配对端口 (由 mDNS 发现或无线调试界面提供)
     * @param pairingCode 手机屏幕上显示的 6 位数配对码
     * @param listener 配对状态监听器
     */
    public suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        listener: AdbPairingListener? = null
    ): Boolean
}

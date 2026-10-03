package libs.libs.libs.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import libs.libs.libs.adb.abb.AdbAbbClient
import libs.libs.libs.adb.abb.AbbInstallOptions
import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbConnectionState
import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.mdns.AdbMdnsManager
import libs.libs.libs.adb.pair.AdbPairingListener
import libs.libs.libs.adb.pair.AdbPairingManager
import libs.libs.libs.adb.root.AdbRootClient
import libs.libs.libs.adb.shell.AdbShellClient
import libs.libs.libs.adb.shell.ShellCommandResult
import libs.libs.libs.adb.shell.ShellStreamChunk
import libs.libs.libs.adb.sync.AdbSyncClientV2
import libs.libs.libs.adb.sync.FileStatV2
import libs.libs.libs.adb.sync.SyncFlags
import libs.libs.libs.adb.usb.accessory.AdbUsbAccessoryManager
import libs.libs.libs.adb.usb.host.AdbUsbHostConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.OptIn
import java.io.File
import java.io.InputStream

/**
 * 统一 ADB 客户端门面 (Facade)
 * 整合 Connection、Pair (SPAKE2)、Shell、ABB、Sync(V2)、Root 以及 USB Host/Accessory 模块
 */
@OptIn(ExperimentalSerializationApi::class)
public class AdbClient(
    public val keyManager: AdbKeyManager,
    public val connection: AdbConnection = AdbConnection(keyManager)
) {
    // 1. 核心交互子模块 (持有当前 connection)
    public val shell: AdbShellClient by lazy { AdbShellClient(connection) }
    public val abb: AdbAbbClient by lazy { AdbAbbClient(connection) }
    public val sync: AdbSyncClientV2 by lazy { AdbSyncClientV2(connection) }
    public val rootClient: AdbRootClient by lazy { AdbRootClient(connection) }

    // 2. 配对与 mDNS 搜索子模块
    public val pairingManager: AdbPairingManager by lazy { AdbPairingManager(keyManager) }

    public fun createMdnsManager(context: Context): AdbMdnsManager = AdbMdnsManager(context)

    // 3. USB 扩展模块（按需动态构建）
    public fun createUsbHostConnection(context: Context, device: UsbDevice): AdbUsbHostConnection {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return AdbUsbHostConnection(usbManager, device)
    }

    public fun createUsbHostConnection(usbManager: UsbManager, device: UsbDevice): AdbUsbHostConnection {
        return AdbUsbHostConnection(usbManager, device)
    }

    public fun createUsbAccessoryManager(context: Context): AdbUsbAccessoryManager {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return AdbUsbAccessoryManager(usbManager)
    }

    public fun createUsbAccessoryManager(usbManager: UsbManager): AdbUsbAccessoryManager {
        return AdbUsbAccessoryManager(usbManager)
    }

    // 连接状态与 Feature 观察
    public val state: StateFlow<AdbConnectionState> get() = connection.state
    public val features: Set<String> get() = connection.features
    public fun hasFeature(feature: String): Boolean = connection.hasFeature(feature)

    // 连接与配对 API (对接 mdns & pair SPAKE2 模块)

    /**
     * 无线配对 (基于 Android 11+ SPAKE2 / SPAKE2+ 握手协议)
     */
    public suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        deviceName: String? = null,
        listener: AdbPairingListener? = null
    ): Result<String> {
        ensureKeyLoaded()
        return pairingManager.pairWithResult(host, port, pairingCode, deviceName)
    }

    /**
     * 便捷方法：仅返回配对成功/失败状态的配对方法
     */
    public suspend fun pairSimple(
        host: String,
        port: Int,
        pairingCode: String,
        deviceName: String? = null,
        listener: AdbPairingListener? = null
    ): Result<Boolean> = runCatching {
        ensureKeyLoaded()
        pairingManager.pair(host, port, pairingCode, deviceName, listener)
    }

    /**
     * 连接 TCP 无线/网络设备
     */
    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = "host::host_model=NekoStudio;mobile_model=Android;",
        timeoutMs: Int = 10000
    ) {
        ensureKeyLoaded()
        connection.connect(host, port, systemIdentity, timeoutMs)
    }

    public fun disconnect() {
        connection.disconnect()
    }

    /**
     * 检查并确保 RSA 密钥已被正确加载或初始化（优先从磁盘读取，文件不存在时才自动生成）
     */
    private fun ensureKeyLoaded() {
        keyManager.ensureLoaded()
    }

    // 提权与重启 API (直接对接 root 模块)

    public suspend fun getProp(property: String): String {
        return shell.execV2("getprop $property").stdout.trim()
    }

    /**
     * 请求 adbd 以 root 身份重启
     */
    public suspend fun root(): ShellCommandResult {
        val result = rootClient.requestRoot()
        return ShellCommandResult(
            exitCode = if (result.isSuccessful) 0 else 1,
            stdout = result.rawMessage,
            stderr = if (result.isSuccessful) "" else result.rawMessage
        )
    }

    /**
     * 请求 adbd 恢复为普通权限重启
     */
    public suspend fun unroot(): ShellCommandResult {
        val result = rootClient.requestUnroot()
        return ShellCommandResult(
            exitCode = if (result.isSuccessful) 0 else 1,
            stdout = result.rawMessage,
            stderr = if (result.isSuccessful) "" else result.rawMessage
        )
    }

    public suspend fun reboot(target: String = ""): Boolean {
        val dest = if (target.isBlank()) "reboot:" else "reboot:$target"
        val stream = connection.openStream(dest)
        val success = stream != null
        stream?.close()
        return success
    }

    // 应用安装与传输 API (全面由 AdbAbbClient 模块托管)

    /**
     * 安装单体 APK 文件 (底层由 AdbAbbClient 自动选择 ABB 极速流或 pm install 兼容模式)
     */
    public suspend fun installApk(
        apkFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = abb.installApk(apkFile, options, onProgress)

    /**
     * 流式安装单体 APK Stream
     */
    public suspend fun installApk(
        apkStream: InputStream,
        apkSize: Long,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = abb.installApk(apkStream, apkSize, options, onProgress)

    /**
     * 安装 APKS 应用套件 (底层由 AdbAbbClient 自动选择 ABB 零磁盘解压流或 pm install 兼容模式)
     */
    public suspend fun installApks(
        apksFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = abb.installApks(apksFile, options, onProgress)

    /**
     * 流式安装 Split APKs 套件
     */
    public suspend fun installSplitApks(
        apks: Map<String, Pair<InputStream, Long>>,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = abb.installSplitApks(apks, options, onProgress)

    // 文件传输 API (对接 sync 模块)

    /**
     * 推送文件到远程设备 (优先使用 Sync V2 SND2，不支持时自动退回 Sync V1 SEND)
     */
    public suspend fun pushFile(
        localFile: File,
        remotePath: String,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = runCatching {
        localFile.inputStream().use { inputStream ->
            sync.pushV2(
                inputStream = inputStream,
                remotePath = remotePath,
                totalSize = localFile.length(),
                flags = flags,
                onProgress = onProgress
            )
        }
    }

    /**
     * 从远程设备拉取文件 (优先使用 Sync V2 RCV2，不支持时自动退回 Sync V1 RECV)
     */
    public suspend fun pullFile(
        remotePath: String,
        localFile: File,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = runCatching {
        localFile.outputStream().use { outputStream ->
            sync.pullV2(
                remotePath = remotePath,
                outputStream = outputStream,
                flags = flags,
                onProgress = onProgress
            )
        }
    }

    /**
     * 获取文件完整属性 (优先使用 Sync V2 STA2，不支持时自动由 V1 STAT 转换补全)
     */
    public suspend fun stat(remotePath: String): FileStatV2 = sync.statV2(remotePath)

    /**
     * 列出目录文件列表 (优先使用 Sync V2 LST2，不支持时自动由 V1 LIST 转换补全)
     */
    public suspend fun listFiles(remotePath: String): List<FileStatV2> = sync.listV2(remotePath)

    // Shell 与日志流 API (对接 shell 模块)

    public fun streamLogcat(args: String = "-v time"): Flow<ShellStreamChunk> {
        return shell.execV2Stream("logcat $args")
    }
}

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
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
    // 1. 核心交互子模块 (确保密钥初始化并在 Connection 状态更新时正常运行)
    public val shell: AdbShellClient = AdbShellClient(connection)
    public val abb: AdbAbbClient = AdbAbbClient(connection)
    public val sync: AdbSyncClientV2 = AdbSyncClientV2(connection)
    public val rootClient: AdbRootClient = AdbRootClient(connection)

    // 2. 配对与 mDNS 搜索子模块
    public val pairingManager: AdbPairingManager by lazy { AdbPairingManager(keyManager) }

    public fun createMdnsManager(context: Context): AdbMdnsManager = AdbMdnsManager(context)

    // 3. USB 扩展模块
    public fun createUsbHostConnection(context: Context, device: UsbDevice): AdbUsbHostConnection {
        ensureKeyLoaded()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return AdbUsbHostConnection(usbManager, device)
    }

    public fun createUsbHostConnection(usbManager: UsbManager, device: UsbDevice): AdbUsbHostConnection {
        ensureKeyLoaded()
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

    // 连接与配对 API

    /**
     * 无线配对 (基于 Android 11+ SPAKE2 / SPAKE2+ 握手协议)
     */
    public suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        listener: AdbPairingListener? = null
    ): Result<String> {
        ensureKeyLoaded()
        return pairingManager.pairWithResult(host, port, pairingCode)
    }

    /**
     * 便捷方法：仅返回配对成功/失败状态的配对方法
     */
    public suspend fun pairSimple(
        host: String,
        port: Int,
        pairingCode: String,
        listener: AdbPairingListener? = null
    ): Result<Boolean> = runCatching {
        ensureKeyLoaded()
        pairingManager.pair(host, port, pairingCode, listener)
    }

    /**
     * 连接 TCP 无线/网络设备
     * 明确返回连接结果 Result<AdbConnectionState.Connected>，方便上层业务判断连接是否成功
     */
    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = "host::;",
        timeoutMs: Long = 10000L
    ): Result<AdbConnectionState.Connected> = runCatching {
        ensureKeyLoaded()
        connection.connect(host, port, systemIdentity, timeoutMs)
    }

    public fun disconnect() {
        connection.disconnect()
    }

    /**
     * 检查并确保 RSA 密钥已被正确加载或初始化（优先从磁盘读取，文件不存在时才自动生成）
     */
    public fun ensureKeyLoaded() {
        val defaultComment = "nekoStudio@adbClient"
        keyManager.ensureLoaded(comment = defaultComment)
    }

    // 提权与重启 API

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

    /**
     * 重启设备，处理设备断开时的 Socket 异常
     */
    public suspend fun reboot(target: String = ""): Boolean = withContext(Dispatchers.IO) {
        val dest = if (target.isBlank()) "reboot:" else "reboot:$target"
        runCatching {
            val stream = connection.openStream(dest)
            val success = stream != null
            stream?.close()
            success
        }.getOrDefault(true) // 设备执行 reboot 后网络会立即切断，抛出异常通常代表命令已成功投递
    }

    // 应用安装与传输 API

    public suspend fun installApk(
        apkFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = abb.installApk(apkFile, options, onProgress)

    public suspend fun installApk(
        apkStream: InputStream,
        apkSize: Long,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = abb.installApk(apkStream, apkSize, options, onProgress)

    public suspend fun installApks(
        apksFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = abb.installApks(apksFile, options, onProgress)

    public suspend fun installSplitApks(
        apks: Map<String, Pair<InputStream, Long>>,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = abb.installSplitApks(apks, options, onProgress)

    // 文件传输 API

    public suspend fun pushFile(
        localFile: File,
        remotePath: String,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
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
    }

    public suspend fun pullFile(
        remotePath: String,
        localFile: File,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            localFile.outputStream().use { outputStream ->
                sync.pullV2(
                    remotePath = remotePath,
                    outputStream = outputStream,
                    flags = flags,
                    onProgress = onProgress
                )
            }
        }
    }

    public suspend fun stat(remotePath: String): FileStatV2 = sync.statV2(remotePath)

    public suspend fun listFiles(remotePath: String): List<FileStatV2> = sync.listV2(remotePath)

    // Shell 与日志流 API

    /**
     * 实时获取 logcat 日志流，自动调度到 IO 线程，并确保上层流取消时正确清理流资源
     */
    public fun streamLogcat(args: String = "-v time"): Flow<ShellStreamChunk> {
        return shell.execV2Stream("logcat $args")
            .flowOn(Dispatchers.IO)
    }
}

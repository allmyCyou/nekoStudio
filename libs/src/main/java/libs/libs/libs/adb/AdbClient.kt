package libs.libs.libs.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import libs.libs.libs.adb.abb.AdbAbbClient
import libs.libs.libs.adb.abb.AbbInstallOptions
import libs.libs.libs.adb.abb.AbbUninstallOptions
import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbConnectionState
import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.mdns.AdbMdnsManager
import libs.libs.libs.adb.mdns.AdbMdnsType
import libs.libs.libs.adb.mdns.AdbMdnsServiceInfo
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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.io.InputStream

/**
 * 内部用于提前中断 Flow 收集的控制流异常
 */
private class MdnsSuccessException : CancellationException("mDNS operation succeeded")

/**
 * 统一 ADB 客户端门面 (Facade)
 * 整合 Connection、Pair (SPAKE2)、Shell、ABB、Sync(V2)、Root、mDNS 自动发现 以及 USB Host/Accessory 模块
 */
@OptIn(ExperimentalSerializationApi::class)
public class AdbClient(
    public val keyManager: AdbKeyManager,
    public val connection: AdbConnection = AdbConnection(keyManager)
) {
    public companion object {
        /**
         * 默认 ADB System Identity 描述字符串 (支持 shell_v2, cmd, sendrecv_v2 等全特性集)
         */
        public const val DEFAULT_SYSTEM_IDENTITY: String =
            "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dry_run_send,openscreen_mdns,devicetracker_proto_format,devraw,app_info,server_status,delayed_ack;"
    }

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
     * 通过 mDNS 自动搜索局域网内的配对服务并完成无线配对
     */
    public suspend fun mdnsPair(
        context: Context,
        pairingCode: String,
        deviceName: String? = null,
        timeoutMs: Long = 10000L,
        listener: AdbPairingListener? = null
    ): Result<String> = mdnsPair(createMdnsManager(context), pairingCode, deviceName, timeoutMs, listener)

    /**
     * 通过传入的 AdbMdnsManager 实例搜索配对服务并完成无线配对
     */
    public suspend fun mdnsPair(
        mdnsManager: AdbMdnsManager,
        pairingCode: String,
        deviceName: String? = null,
        timeoutMs: Long = 10000L,
        listener: AdbPairingListener? = null
    ): Result<String> = runCatching {
        val attemptedEndpoints = mutableSetOf<Pair<String, Int>>()
        val failedLogs = mutableListOf<String>()
        var successResult: String? = null

        try {
            withTimeoutOrNull(timeoutMs) {
                mdnsManager.discoverServices(AdbMdnsType.PAIRING)
                    .collect { service ->
                        val ip = service.ipAddress ?: return@collect
                        val port = service.port

                        if (deviceName != null && !service.name.contains(deviceName, ignoreCase = true)) {
                            return@collect
                        }

                        if (!attemptedEndpoints.add(ip to port)) {
                            return@collect
                        }

                        val res = pair(
                            host = ip,
                            port = port,
                            pairingCode = pairingCode,
                            listener = listener
                        )

                        if (res.isSuccess) {
                            successResult = res.getOrThrow()
                            throw MdnsSuccessException()
                        } else {
                            val errMsg = res.exceptionOrNull()?.message ?: "Pairing failed"
                            failedLogs.add("$ip:$port ($errMsg)")
                        }
                    }
            }
        } catch (_: MdnsSuccessException) {
            // 成功时捕获异常正常退出
        }

        successResult ?: throw IllegalStateException(
            if (attemptedEndpoints.isEmpty()) {
                "未在 $timeoutMs ms 内找到匹配的 mDNS 配对服务"
            } else {
                "尝试配对所有匹配的 mDNS 端口均失败: [${failedLogs.joinToString("; ")}]"
            }
        )
    }

    /**
     * 连接 TCP 无线/网络设备
     */
    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = DEFAULT_SYSTEM_IDENTITY,
        timeoutMs: Long = 10000L
    ): Result<AdbConnectionState.Connected> = runCatching {
        ensureKeyLoaded()
        connection.connect(host, port, systemIdentity, timeoutMs)
    }

    /**
     * 通过 mDNS 自动搜索局域网内的 TLS 调试服务并建立 ADB 连接
     */
    public suspend fun mdnsConnect(
        context: Context,
        deviceName: String? = null,
        systemIdentity: String = DEFAULT_SYSTEM_IDENTITY,
        mdnsTimeoutMs: Long = 10000L,
        connectTimeoutMs: Long = 10000L
    ): Result<AdbConnectionState.Connected> = mdnsConnect(
        mdnsManager = createMdnsManager(context),
        deviceName = deviceName,
        systemIdentity = systemIdentity,
        mdnsTimeoutMs = mdnsTimeoutMs,
        connectTimeoutMs = connectTimeoutMs
    )

    /**
     * 通过传入的 AdbMdnsManager 实例搜索 TLS 调试服务并建立 ADB 连接
     */
    public suspend fun mdnsConnect(
        mdnsManager: AdbMdnsManager,
        deviceName: String? = null,
        systemIdentity: String = DEFAULT_SYSTEM_IDENTITY,
        mdnsTimeoutMs: Long = 10000L,
        connectTimeoutMs: Long = 10000L
    ): Result<AdbConnectionState.Connected> = runCatching {
        val attemptedEndpoints = mutableSetOf<Pair<String, Int>>()
        val failedLogs = mutableListOf<String>()
        var successResult: AdbConnectionState.Connected? = null

        try {
            withTimeoutOrNull(mdnsTimeoutMs) {
                mdnsManager.discoverServices(AdbMdnsType.CONNECT)
                    .collect { service ->
                        val ip = service.ipAddress ?: return@collect
                        val port = service.port

                        if (deviceName != null && !service.name.contains(deviceName, ignoreCase = true)) {
                            return@collect
                        }

                        if (!attemptedEndpoints.add(ip to port)) {
                            return@collect
                        }

                        val res = connect(
                            host = ip,
                            port = port,
                            systemIdentity = systemIdentity,
                            timeoutMs = connectTimeoutMs
                        )

                        if (res.isSuccess) {
                            successResult = res.getOrThrow()
                            throw MdnsSuccessException()
                        } else {
                            val errMsg = res.exceptionOrNull()?.message ?: "Connection failed"
                            failedLogs.add("$ip:$port ($errMsg)")
                        }
                    }
            }
        } catch (_: MdnsSuccessException) {
            // 成功时捕获异常正常退出
        }

        successResult ?: throw IllegalStateException(
            if (attemptedEndpoints.isEmpty()) {
                "未在 $mdnsTimeoutMs ms 内找到匹配的 mDNS 调试服务"
            } else {
                "尝试连接所有匹配的 mDNS 端口均失败: [${failedLogs.joinToString("; ")}]"
            }
        )
    }

    /**
     * 搜索局域网内所有 mDNS 设备列表
     */
    public suspend fun mdnsList(
        context: Context,
        types: List<AdbMdnsType> = AdbMdnsType.entries,
        scanDurationMs: Long = 3000L
    ): List<AdbMdnsServiceInfo> = mdnsList(createMdnsManager(context), types, scanDurationMs)

    /**
     * 通过 AdbMdnsManager 实例搜索局域网内所有 mDNS 服务设备列表
     */
    public suspend fun mdnsList(
        mdnsManager: AdbMdnsManager,
        types: List<AdbMdnsType> = AdbMdnsType.entries,
        scanDurationMs: Long = 3000L
    ): List<AdbMdnsServiceInfo> {
        val results = mutableListOf<AdbMdnsServiceInfo>()
        withTimeoutOrNull(scanDurationMs) {
            types.map { mdnsManager.discoverServices(it) }
                .merge()
                .collect { service ->
                    if (service.ipAddress != null && results.none { it.ipAddress == service.ipAddress && it.port == service.port }) {
                        results.add(service)
                    }
                }
        }
        return results
    }

    public fun disconnect() {
        connection.disconnect()
    }

    /**
     * 检查并确保 RSA 密钥已被正确加载或初始化
     */
    public fun ensureKeyLoaded() {
        val defaultComment = "nekoStudio@adbClient"
        keyManager.ensureLoaded(comment = defaultComment)
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
        }.getOrDefault(true)
    }

    // 应用安装与卸载 API

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

    /**
     * 卸载指定的应用包
     */
    public suspend fun uninstall(
        packageName: String,
        options: AbbUninstallOptions = AbbUninstallOptions()
    ): Result<Unit> = abb.uninstall(packageName, options)

    // 文件传输 API（基础单文件 API & 增强版目录树 Landing Path API）

    /**
     * 推送 (Push) 单个本地文件到设备指定的远端绝对路径
     */
    public suspend fun pushFile(
        localFile: File,
        remotePath: String,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(localFile.exists() && localFile.isFile) { "推送源必须是存在的单文件: ${localFile.absolutePath}" }
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

    /**
     * 从设备远端绝对路径拉取 (Pull) 单个文件到本地
     */
    public suspend fun pullFile(
        remotePath: String,
        localFile: File,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 确保本地父级目录已自动创建
            localFile.parentFile?.mkdirs()
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

    /**
     * 增强 Push API：处理文件或目录树传输，并按标准 `adb push` 规范推算落地路径
     *
     * 采用流式遍历：不缓存 File / 路径列表，内存占用与目录规模无关
     */
    public suspend fun push(
        local: File,
        remotePath: String,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(local.exists()) { "本地路径不存在: ${local.absolutePath}" }

            val isRemoteDir = isRemoteDirectory(remotePath) || remotePath.endsWith("/")

            // === 单文件模式 ===
            if (local.isFile) {
                val targetRemotePath = if (isRemoteDir) {
                    "${remotePath.trimEnd('/')}/${local.name}"
                } else {
                    remotePath
                }
                pushFile(local, targetRemotePath, flags, onProgress).getOrThrow()
                return@runCatching
            }

            // === 目录模式 ===
            val baseRemoteDir = if (isRemoteDir) {
                "${remotePath.trimEnd('/')}/${local.name}"
            } else {
                remotePath.trimEnd('/')
            }

            // 1. 流式分批创建远端目录：每满 50 个目标就 flush 一次 mkdir -p
            val dirBatch = ArrayList<String>(50)
            val dirIter = local.walkTopDown().filter { it.isDirectory }.iterator()
            while (dirIter.hasNext()) {
                val dir = dirIter.next()
                val relativePath = dir.relativeTo(local).path.replace('\\', '/')
                dirBatch.add(if (relativePath.isEmpty()) baseRemoteDir else "$baseRemoteDir/$relativePath")
                if (dirBatch.size >= 50) {
                    flushMkdirBatch(dirBatch)
                }
            }
            flushMkdirBatch(dirBatch) // 处理最后不满 50 的尾巴

            // 2. 第一遍流式遍历：仅统计总字节（不缓存）
            var totalBytes = 0L
            val statIter = local.walkTopDown().filter { it.isFile }.iterator()
            while (statIter.hasNext()) {
                totalBytes += statIter.next().length()
            }

            // 3. 第二遍流式遍历：逐个推送
            var accumulatedBytes = 0L
            val fileIter = local.walkTopDown().filter { it.isFile }.iterator()
            while (fileIter.hasNext()) {
                val file = fileIter.next()
                val relativePath = file.relativeTo(local).path.replace('\\', '/')
                val targetPath = "$baseRemoteDir/$relativePath"
                val currentFileSize = file.length()

                pushFile(file, targetPath, flags) { fileWritten, _ ->
                    onProgress?.invoke(accumulatedBytes + fileWritten, totalBytes)
                }.getOrThrow()

                accumulatedBytes += currentFileSize
                onProgress?.invoke(accumulatedBytes, totalBytes)
            }

            // 空目录场景兜底
            if (totalBytes == 0L) {
                onProgress?.invoke(0L, 0L)
            }
        }
    }

    /**
     * 增强 Pull API：处理文件或目录树从设备拉取，并按标准 `adb pull` 规范推算落地路径
     * 包含防路径穿越 (Path Traversal / Zip Slip) 安全校验
     *
     * 采用流式回调遍历：不缓存远端节点列表，内存占用与目录规模无关
     */
    public suspend fun pull(
        remotePath: String,
        local: File,
        flags: Int = SyncFlags.FLAG_NONE,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val isRemoteDir = isRemoteDirectory(remotePath) || remotePath.endsWith("/")

            // === 单文件模式 ===
            if (!isRemoteDir) {
                val targetLocalFile = if (local.isDirectory ||
                    local.path.endsWith(File.separator) || local.path.endsWith("/")
                ) {
                    File(local, remotePath.trimEnd('/').substringAfterLast('/'))
                } else {
                    local
                }

                var progressCalled = false
                pullFile(remotePath, targetLocalFile, flags) { read, total ->
                    progressCalled = true
                    onProgress?.invoke(read, total)
                }.getOrThrow()

                if (!progressCalled) {
                    onProgress?.invoke(0L, 0L)
                }
                return@runCatching
            }

            // === 目录模式 ===
            val remoteCleanPath = remotePath.trimEnd('/')
            val remoteDirName = remoteCleanPath.substringAfterLast('/')

            // 1. 计算并固化 baseLocalDir 的 canonical 路径
            val baseLocalDirRaw = if (local.exists() && local.isDirectory) {
                File(local, remoteDirName)
            } else {
                local
            }
            baseLocalDirRaw.mkdirs()

            val canonicalBase = baseLocalDirRaw.canonicalFile
            val canonicalBasePath = canonicalBase.path
            val canonicalBasePrefix = canonicalBasePath + File.separator

            fun ensureInsideBase(target: File, relativePath: String) {
                val canonicalDest = target.canonicalPath
                if (canonicalDest != canonicalBasePath && !canonicalDest.startsWith(canonicalBasePrefix)) {
                    throw SecurityException("检测到非法路径穿越尝试: $relativePath")
                }
            }

            // 2. 第一遍扫描：只统计总字节
            var totalBytes = 0L
            walkRemoteTree(
                baseRemoteDir = remoteCleanPath,
                onFile = { _, size -> totalBytes += size }
            )

            // 3. 第二遍扫描：DFS 边建目录、边拉取文件（DFS 天然保证父目录先于子目录）
            var accumulatedBytes = 0L
            walkRemoteTree(
                baseRemoteDir = remoteCleanPath,
                onDirectory = { relativePath ->
                    val targetLocalDir = File(canonicalBase, relativePath)
                    ensureInsideBase(targetLocalDir, relativePath)
                    targetLocalDir.mkdirs()
                },
                onFile = { relativePath, size ->
                    val targetLocalFile = File(canonicalBase, relativePath)
                    ensureInsideBase(targetLocalFile, relativePath)
                    // 兜底：极端情况下父目录可能未被单独回调（如 listV2 返回异常），保险建一次
                    targetLocalFile.parentFile?.mkdirs()

                    val fullRemotePath = "$remoteCleanPath/$relativePath"
                    pullFile(fullRemotePath, targetLocalFile, flags) { fileRead, _ ->
                        onProgress?.invoke(accumulatedBytes + fileRead, totalBytes)
                    }.getOrThrow()

                    accumulatedBytes += size
                    onProgress?.invoke(accumulatedBytes, totalBytes)
                }
            )

            // 全空目录 / 全空文件场景兜底
            if (totalBytes == 0L) {
                onProgress?.invoke(0L, 0L)
            }
        }
    }

    /**
     * 判断远端路径是否为目录
     */
    private suspend fun isRemoteDirectory(remotePath: String): Boolean {
        if (remotePath.endsWith("/")) return true

        return runCatching {
            val stat = sync.statV2(remotePath)
            stat.exists && stat.isDirectory
        }.getOrElse {
            false
        }
    }

    /**
     * 流式分批 mkdir：发一条 `mkdir -p` 后清空批次
     */
    private suspend fun flushMkdirBatch(batch: MutableList<String>) {
        if (batch.isEmpty()) return
        val escaped = batch.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val result = shell.exec("mkdir -p $escaped")
        check(result.isSuccess) { "批量创建远端目录失败: ${result.stderr}" }
        batch.clear()
    }

    /**
     * 回调式递归遍历远端文件树（流式，不缓存节点）
     * - [onDirectory]：进入子目录前回调（父目录先于子目录）
     * - [onFile]：遇到文件时回调
     */
    private suspend fun walkRemoteTree(
        baseRemoteDir: String,
        currentRelativeDir: String = "",
        depth: Int = 0,
        onDirectory: (suspend (relativePath: String) -> Unit)? = null,
        onFile: (suspend (relativePath: String, size: Long) -> Unit)? = null
    ) {
        if (depth > 64) return

        val currentRemoteDir = if (currentRelativeDir.isEmpty()) {
            baseRemoteDir
        } else {
            "$baseRemoteDir/$currentRelativeDir"
        }

        val dirEntries = runCatching { sync.listV2(currentRemoteDir) }.getOrDefault(emptyList())
        for (stat in dirEntries) {
            if (!stat.exists) continue

            val fileName = stat.path.trimEnd('/').substringAfterLast('/')
            if (fileName == "." || fileName == ".." || fileName.isBlank()) continue

            val relativePath = if (currentRelativeDir.isEmpty()) fileName
                               else "$currentRelativeDir/$fileName"

            if (stat.isDirectory) {
                onDirectory?.invoke(relativePath)
                walkRemoteTree(baseRemoteDir, relativePath, depth + 1, onDirectory, onFile)
            } else if (stat.isFile) {
                onFile?.invoke(relativePath, stat.size)
            }
        }
    }
}

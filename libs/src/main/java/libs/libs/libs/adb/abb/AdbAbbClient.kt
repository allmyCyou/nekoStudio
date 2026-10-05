package libs.libs.libs.adb.abb

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.shell.AdbShellClient
import libs.libs.libs.adb.shell.ShellCommandResult
import libs.libs.libs.adb.shell.ShellV2Buffer
import libs.libs.libs.adb.shell.ShellV2Packet
import libs.libs.libs.adb.sync.AdbSyncClientV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 动态 ABB 客户端 (取消对 features 标识位的强制依赖，采用动态探测与自动降级机制)
 */
@OptIn(ExperimentalSerializationApi::class)
public class AdbAbbClient(
    @PublishedApi internal val connection: AdbConnection
) {
    private val shellClient by lazy { AdbShellClient(connection) }
    private val syncClient by lazy { AdbSyncClientV2(connection) }

    // 运行时动态缓存：null 表示未探测，true 表示已确认支持，false 表示已确认不支持
    @Volatile
    private var isAbbSupportedCache: Boolean? = null

    /**
     * 重置 ABB 可用性缓存（在 ADB 重连或断开时调用）
     */
    public fun resetCapabilityCache() {
        isAbbSupportedCache = null
    }

    /**
     * 检查当前连接是否可尝试 ABB
     */
    public fun canTryAbb(): Boolean = isAbbSupportedCache != false

    private fun buildDestination(servicePrefix: String, args: List<String>): String {
        return buildString {
            append(servicePrefix)
            args.forEach { arg ->
                append(arg)
                append('\u0000')
            }
        }
    }

    public suspend fun execAbb(args: List<String>): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val destination = buildDestination("abb:", args)
        
        val stream = connection.openStream(destination)
            ?: run {
                isAbbSupportedCache = false
                return@withContext ShellCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "ABB service not supported by adbd (Stream rejected)",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()
        var exitCode = -1
        val v2Buffer = ShellV2Buffer()

        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) {
                    v2Buffer.append(data)
                    while (true) {
                        val packet = v2Buffer.pollPacket() ?: break
                        when (packet.id) {
                            ShellV2Packet.ID_STDOUT -> stdoutStream.write(packet.payload)
                            ShellV2Packet.ID_STDERR -> stderrStream.write(packet.payload)
                            ShellV2Packet.ID_EXIT -> {
                                if (packet.payload.isNotEmpty()) {
                                    exitCode = packet.payload[0].toInt() and 0xFF
                                }
                            }
                        }
                    }
                }
            }
            if (isAbbSupportedCache == null) {
                isAbbSupportedCache = true
            }
        } finally {
            stream.close()
        }

        ShellCommandResult(
            exitCode = exitCode,
            stdout = stdoutStream.toString(Charsets.UTF_8.name()),
            stderr = stderrStream.toString(Charsets.UTF_8.name()),
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 安装单体 APK File (支持自动降级)
     */
    public suspend fun installApk(
        apkFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> {
        require(apkFile.exists()) { "APK file non-existent: ${apkFile.absolutePath}" }

        if (canTryAbb()) {
            val abbResult = apkFile.inputStream().use { stream ->
                installApkAbbInternal(stream, apkFile.length(), options, onProgress)
            }
            
            if (abbResult.isSuccess) {
                isAbbSupportedCache = true
                return abbResult
            }

            if (isAbbSupportedCache == false) {
                // 自动进入 Legacy 降级流程
            } else {
                return abbResult
            }
        }

        return installApkLegacy(apkFile, options, onProgress)
    }

    /**
     * 安装单体 APK InputStream (支持自动降级)
     */
    public suspend fun installApk(
        apkStream: InputStream,
        apkSize: Long,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        require(apkSize > 0) { "Invalid APK size: $apkSize" }

        if (canTryAbb()) {
            val abbResult = installApkAbbInternal(apkStream, apkSize, options, onProgress)
            if (abbResult.isSuccess) {
                isAbbSupportedCache = true
                return@withContext abbResult
            }

            if (isAbbSupportedCache != false) {
                return@withContext abbResult
            }
        }

        // Legacy 降级：将流临时落盘后走 Sync Push + pm install
        val tempFile = File.createTempFile("temp_install_", ".apk")
        try {
            tempFile.outputStream().use { output ->
                apkStream.copyTo(output)
            }
            installApkLegacy(tempFile, options, onProgress)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 安装 APKS 套件 (支持自动降级)
     */
    public suspend fun installApks(
        apksFile: File,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        require(apksFile.exists()) { "APKS file non-existent: ${apksFile.absolutePath}" }

        if (canTryAbb()) {
            val abbResult = installApksAbbInternal(apksFile, options, onProgress)
            if (abbResult.isSuccess) {
                isAbbSupportedCache = true
                return@withContext abbResult
            }

            if (isAbbSupportedCache != false) {
                return@withContext abbResult
            }
        }

        installApksLegacy(apksFile, options, onProgress)
    }

    /**
     * 安装 Split APKs Map 套件 (支持自动降级)
     */
    public suspend fun installSplitApks(
        apks: Map<String, Pair<InputStream, Long>>,
        options: AbbInstallOptions = AbbInstallOptions(),
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        require(apks.isNotEmpty()) { "APKs map cannot be empty" }

        if (canTryAbb()) {
            val abbResult = installSplitApksAbbInternal(apks, options, onProgress)
            if (abbResult.isSuccess) {
                isAbbSupportedCache = true
                return@withContext abbResult
            }

            if (isAbbSupportedCache != false) {
                return@withContext abbResult
            }
        }

        installSplitApksLegacy(apks, options, onProgress)
    }

    // 内部实现逻辑 (ABB 模式 vs Legacy 模式)

    private suspend fun installApkAbbInternal(
        apkStream: InputStream,
        apkSize: Long,
        options: AbbInstallOptions,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<Unit> = runCatching {
        val createArgs = mutableListOf("package", "install-create", "-S", apkSize.toString())
        createArgs.addAll(options.toArgs())

        var createResult = execAbb(createArgs)
        
        if (isAbbSupportedCache == false) {
            throw UnsupportedOperationException("ABB is not supported by device")
        }

        val combinedOutput = "${createResult.stdout} ${createResult.stderr}"
        if (!createResult.isSuccess && options.bypassLowTargetSdkBlock && combinedOutput.contains("Unknown option")) {
            val fallbackArgs = mutableListOf("package", "install-create", "-S", apkSize.toString())
            fallbackArgs.addAll(options.toArgs(includeBypassLowSdk = false))
            createResult = execAbb(fallbackArgs)
        }

        check(createResult.isSuccess) { "Failed to create install session: $combinedOutput" }

        val sessionId = extractSessionId(createResult.stdout)
            ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

        try {
            val writeDestination = buildDestination(
                "abb_exec:",
                listOf("package", "install-write", "-S", apkSize.toString(), sessionId, "base.apk", "-")
            )
            val writeStream = connection.openStream(writeDestination)
                ?: throw IllegalStateException("Failed to open install-write stream")

            try {
                apkStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesWritten = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (read > 0) {
                            val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                            writeStream.write(chunk)
                            bytesWritten += read
                            onProgress?.invoke(bytesWritten, apkSize)
                        }
                    }
                }
            } finally {
                writeStream.close()
            }

            val commitResult = execAbb(listOf("package", "install-commit", sessionId))
            check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                "Failed to commit install session $sessionId: ${commitResult.stdout}${commitResult.stderr}"
            }
        } catch (e: Exception) {
            execAbb(listOf("package", "install-abandon", sessionId))
            throw e
        }
    }

    private suspend fun installApkLegacy(
        apkFile: File,
        options: AbbInstallOptions,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<Unit> = runCatching {
        val tempPath = "/data/local/tmp/temp_${System.currentTimeMillis()}.apk"
        try {
            apkFile.inputStream().use { stream ->
                syncClient.pushV2(
                    inputStream = stream,
                    remotePath = tempPath,
                    totalSize = apkFile.length(),
                    onProgress = onProgress
                )
            }
            val result = shellClient.execV2("pm install ${options.toArgs().joinToString(" ")} '$tempPath'")
            check(result.isSuccess && result.stdout.contains("Success")) {
                "Legacy install failed: ${result.stdout} ${result.stderr}"
            }
        } finally {
            shellClient.execV2("rm -f '$tempPath'")
        }
    }

    private suspend fun installApksAbbInternal(
        apksFile: File,
        options: AbbInstallOptions,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<Unit> = runCatching {
        ZipFile(apksFile).use { zip ->
            val apkEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .toList()

            check(apkEntries.isNotEmpty()) { "No .apk files found in ${apksFile.name}" }

            val totalBytes = apkEntries.sumOf { it.size }
            check(totalBytes > 0) { "Invalid total byte size in APKS: $totalBytes" }

            val createArgs = mutableListOf("package", "install-create", "-S", totalBytes.toString())
            createArgs.addAll(options.toArgs())

            var createResult = execAbb(createArgs)
            if (isAbbSupportedCache == false) {
                throw UnsupportedOperationException("ABB is not supported by device")
            }

            val combinedOutput = "${createResult.stdout} ${createResult.stderr}"
            if (!createResult.isSuccess && options.bypassLowTargetSdkBlock && combinedOutput.contains("Unknown option")) {
                val fallbackArgs = mutableListOf("package", "install-create", "-S", totalBytes.toString())
                fallbackArgs.addAll(options.toArgs(includeBypassLowSdk = false))
                createResult = execAbb(fallbackArgs)
            }

            check(createResult.isSuccess) { "Failed to create install session: $combinedOutput" }

            val sessionId = extractSessionId(createResult.stdout)
                ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

            var globalBytesWritten = 0L

            try {
                apkEntries.forEach { entry ->
                    val splitName = entry.name.substringAfterLast('/').ifEmpty { "base.apk" }
                    val entrySize = entry.size

                    val writeDestination = buildDestination(
                        "abb_exec:",
                        listOf("package", "install-write", "-S", entrySize.toString(), sessionId, splitName, "-")
                    )

                    val writeStream = connection.openStream(writeDestination)
                        ?: throw IllegalStateException("Failed to open install-write stream for $splitName")

                    try {
                        zip.getInputStream(entry).use { apkStream ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (apkStream.read(buffer).also { read = it } != -1) {
                                if (read > 0) {
                                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                                    writeStream.write(chunk)
                                    globalBytesWritten += read
                                    onProgress?.invoke(globalBytesWritten, totalBytes)
                                }
                            }
                        }
                    } finally {
                        writeStream.close()
                    }
                }

                val commitResult = execAbb(listOf("package", "install-commit", sessionId))
                check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                    "Failed to commit install session $sessionId: ${commitResult.stdout}${commitResult.stderr}"
                }
            } catch (e: Exception) {
                execAbb(listOf("package", "install-abandon", sessionId))
                throw e
            }
        }
    }

    private suspend fun installApksLegacy(
        apksFile: File,
        options: AbbInstallOptions,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<Unit> = runCatching {
        ZipFile(apksFile).use { zip ->
            val apkEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .toList()

            check(apkEntries.isNotEmpty()) { "No .apk files found in ${apksFile.name}" }

            val totalBytes = apkEntries.sumOf { it.size }
            val createResult = shellClient.execV2("pm install-create -S $totalBytes ${options.toArgs().joinToString(" ")}")
            check(createResult.isSuccess) { "Failed to create pm session: ${createResult.stderr}" }

            val sessionId = extractSessionId(createResult.stdout)
                ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

            var globalWritten = 0L

            try {
                apkEntries.forEachIndexed { index, entry ->
                    val splitName = entry.name.substringAfterLast('/')
                    val tempPath = "/data/local/tmp/temp_split_${index}_${System.currentTimeMillis()}.apk"

                    try {
                        zip.getInputStream(entry).use { inputStream ->
                            syncClient.pushV2(
                                inputStream = inputStream,
                                remotePath = tempPath,
                                totalSize = entry.size,
                                onProgress = { read, _ ->
                                    onProgress?.invoke(globalWritten + read, totalBytes)
                                }
                            )
                        }
                        globalWritten += entry.size

                        val writeResult = shellClient.execV2("pm install-write -S ${entry.size} $sessionId '$splitName' '$tempPath'")
                        check(writeResult.isSuccess) { "Failed to write split $splitName: ${writeResult.stderr}" }
                    } finally {
                        shellClient.execV2("rm -f '$tempPath'")
                    }
                }

                val commitResult = shellClient.execV2("pm install-commit $sessionId")
                check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                    "Failed to commit session $sessionId: ${commitResult.stdout}${commitResult.stderr}"
                }
            } catch (e: Exception) {
                shellClient.execV2("pm install-abandon $sessionId")
                throw e
            }
        }
    }

    private suspend fun installSplitApksAbbInternal(
        apks: Map<String, Pair<InputStream, Long>>,
        options: AbbInstallOptions,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<Unit> = runCatching {
        val totalSize = apks.values.sumOf { it.second }
        check(totalSize > 0) { "Invalid total byte size: $totalSize" }

        val createArgs = mutableListOf("package", "install-create", "-S", totalSize.toString())
        createArgs.addAll(options.toArgs())

        var createResult = execAbb(createArgs)
        if (isAbbSupportedCache == false) {
            throw UnsupportedOperationException("ABB is not supported by device")
        }

        val combinedOutput = "${createResult.stdout} ${createResult.stderr}"
        if (!createResult.isSuccess && options.bypassLowTargetSdkBlock && combinedOutput.contains("Unknown option")) {
            val fallbackArgs = mutableListOf("package", "install-create", "-S", totalSize.toString())
            fallbackArgs.addAll(options.toArgs(includeBypassLowSdk = false))
            createResult = execAbb(fallbackArgs)
        }

        check(createResult.isSuccess) { "Failed to create install session: $combinedOutput" }

        val sessionId = extractSessionId(createResult.stdout)
            ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

        var globalBytesWritten = 0L

        try {
            apks.forEach { (splitName, streamWithSize) ->
                val (stream, size) = streamWithSize
                val safeSplitName = splitName.substringAfterLast('/').ifEmpty { "base.apk" }
                val writeDestination = buildDestination(
                    "abb_exec:",
                    listOf("package", "install-write", "-S", size.toString(), sessionId, safeSplitName, "-")
                )
                val writeStream = connection.openStream(writeDestination)
                    ?: throw IllegalStateException("Failed to open stream for $safeSplitName")

                try {
                    stream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (read > 0) {
                                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                                writeStream.write(chunk)
                                globalBytesWritten += read
                                onProgress?.invoke(globalBytesWritten, totalSize)
                            }
                        }
                    }
                } finally {
                    writeStream.close()
                }
            }

            val commitResult = execAbb(listOf("package", "install-commit", sessionId))
            check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                "Commit failed for session $sessionId: ${commitResult.stdout}${commitResult.stderr}"
            }
        } catch (e: Exception) {
            execAbb(listOf("package", "install-abandon", sessionId))
            throw e
        }
    }

    private suspend fun installSplitApksLegacy(
        apks: Map<String, Pair<InputStream, Long>>,
        options: AbbInstallOptions,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<Unit> = runCatching {
        val totalSize = apks.values.sumOf { it.second }
        val createResult = shellClient.execV2("pm install-create -S $totalSize ${options.toArgs().joinToString(" ")}")
        check(createResult.isSuccess) { "Failed to create pm session: ${createResult.stderr}" }

        val sessionId = extractSessionId(createResult.stdout)
            ?: throw IllegalStateException("Failed to parse session ID from: ${createResult.stdout}")

        var globalWritten = 0L

        try {
            apks.entries.forEachIndexed { index, entry ->
                val splitName = entry.key
                val (inputStream, size) = entry.value
                val tempPath = "/data/local/tmp/temp_split_${index}_${System.currentTimeMillis()}.apk"

                try {
                    inputStream.use { stream ->
                        syncClient.pushV2(
                            inputStream = stream,
                            remotePath = tempPath,
                            totalSize = size,
                            onProgress = { read, _ ->
                                onProgress?.invoke(globalWritten + read, totalSize)
                            }
                        )
                    }
                    globalWritten += size

                    val writeResult = shellClient.execV2("pm install-write -S $size $sessionId '$splitName' '$tempPath'")
                    check(writeResult.isSuccess) { "Failed to write split $splitName: ${writeResult.stderr}" }
                } finally {
                    shellClient.execV2("rm -f '$tempPath'")
                }
            }

            val commitResult = shellClient.execV2("pm install-commit $sessionId")
            check(commitResult.isSuccess && commitResult.stdout.contains("Success")) {
                "Commit failed for session $sessionId: ${commitResult.stdout}${commitResult.stderr}"
            }
        } catch (e: Exception) {
            shellClient.execV2("pm install-abandon $sessionId")
            throw e
        }
    }

    private fun extractSessionId(output: String): String? {
        val regex = Regex("""created install session \[(\d+)]""")
        return regex.find(output)?.groupValues?.get(1)
    }

    /**
     * 卸载指定的应用包 (支持 ABB 快速通道与 Shell 动态降级)
     */
    public suspend fun uninstall(
        packageName: String,
        options: AbbUninstallOptions = AbbUninstallOptions()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(packageName.isNotBlank()) { "Package name cannot be blank" }

            val extraArgs = options.toArgs()

            if (canTryAbb()) {
                val abbArgs = mutableListOf("package", "uninstall").apply {
                    addAll(extraArgs)
                    add(packageName)
                }

                val abbResult = execAbb(abbArgs)

                if (isAbbSupportedCache != false) {
                    val output = "${abbResult.stdout} ${abbResult.stderr}".trim()
                    check(abbResult.isSuccess && output.contains("Success", ignoreCase = true)) {
                        "Uninstall failed via ABB: $output"
                    }
                    isAbbSupportedCache = true
                    return@runCatching
                }
            }

            val legacyArgs = if (extraArgs.isNotEmpty()) "${extraArgs.joinToString(" ")} " else ""
            val command = "pm uninstall $legacyArgs'$packageName'"
            
            val shellResult = shellClient.execV2(command)
            val output = "${shellResult.stdout} ${shellResult.stderr}".trim()

            check(shellResult.isSuccess && output.contains("Success", ignoreCase = true)) {
                "Uninstall failed via Shell: $output"
            }
        }
    }
}

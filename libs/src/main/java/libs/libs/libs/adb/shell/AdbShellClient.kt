package libs.libs.libs.adb.shell

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

public class AdbShellClient(
    @PublishedApi internal val connection: AdbConnection
) {

    /**
     * 执行 Shell 指令（乐观尝试 Shell V2，失败/不支持时自动无缝降级至 V1）
     */
    public suspend fun exec(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        // 1. 尝试 Shell V2 执行
        val v2Result = execV2(command)
        
        // 2. 判断 V2 是否成功建立与执行：若 exitCode 为 -1 且 stderr 提示建流失败/未收到 exit 包，自动回退到 V1
        if (v2Result.exitCode == -1 && (v2Result.stderr.contains("Failed to open shell_v2 stream") || v2Result.stdout.isEmpty())) {
            return@withContext execV1(command)
        }
        
        v2Result
    }

    /**
     * 以 Exec (V1) 模式发送命令（使用 sentinel 机制解析 Exit Code）
     */
    public suspend fun execV1(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val sentinel = "__ADB_EXIT_CODE_${startTime}__:"
        val wrappedCommand = "($command); printf \"\n$sentinel%d\" \$?"

        val stream = connection.openStream("exec:$wrappedCommand")
            ?: return@withContext ShellCommandResult(
                exitCode = -1, stdout = "", stderr = "Failed to open exec stream", durationMs = 0L
            )

        val outputStream = ByteArrayOutputStream()
        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) outputStream.write(data)
            }
        } finally {
            stream.close()
        }

        val rawOutput = outputStream.toString(Charsets.UTF_8.name())
        val sentinelIndex = rawOutput.lastIndexOf(sentinel)

        val (stdout, exitCode) = if (sentinelIndex != -1) {
            var stdoutRaw = rawOutput.substring(0, sentinelIndex)
            if (stdoutRaw.endsWith("\r\n")) {
                stdoutRaw = stdoutRaw.substring(0, stdoutRaw.length - 2)
            } else if (stdoutRaw.endsWith("\n")) {
                stdoutRaw = stdoutRaw.substring(0, stdoutRaw.length - 1)
            }
            val code = rawOutput.substring(sentinelIndex + sentinel.length).trim().toIntOrNull() ?: 0
            stdoutRaw to code
        } else {
            rawOutput to 0
        }

        ShellCommandResult(exitCode, stdout, "", System.currentTimeMillis() - startTime)
    }

    /**
     * 以 Shell V2 模式发送命令 (`shell,v2,raw:`)
     */
    public suspend fun execV2(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val stream = connection.openStream("shell,v2,raw:$command")
            ?: return@withContext ShellCommandResult(
                exitCode = -1, stdout = "", stderr = "Failed to open shell_v2 stream", durationMs = 0L
            )

        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()
        var exitCode = -1

        try {
            readShellV2Stream(stream) { packet ->
                when (packet.id) {
                    ShellV2Packet.ID_STDOUT -> stdoutStream.write(packet.payload)
                    ShellV2Packet.ID_STDERR -> stderrStream.write(packet.payload)
                    ShellV2Packet.ID_EXIT -> {
                        if (packet.payload.isNotEmpty()) {
                            exitCode = packet.payload[0].toUByte().toInt()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext ShellCommandResult(
                exitCode = -1, stdout = "", stderr = "Shell V2 read failed: ${e.message}", durationMs = System.currentTimeMillis() - startTime
            )
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
     * 读取无损二进制字节数组（优先 Shell V2，降级使用标准 V1 exec 原始流）
     */
    public suspend fun execRawBytes(command: String): ByteArray = withContext(Dispatchers.IO) {
        // 尝试 V2 模式（无协议损耗）
        val v2Stream = connection.openStream("shell,v2,raw:$command")
        if (v2Stream != null) {
            val stdoutStream = ByteArrayOutputStream()
            var exitCode = -1
            try {
                readShellV2Stream(v2Stream) { packet ->
                    when (packet.id) {
                        ShellV2Packet.ID_STDOUT -> stdoutStream.write(packet.payload)
                        ShellV2Packet.ID_EXIT -> {
                            if (packet.payload.isNotEmpty()) {
                                exitCode = packet.payload[0].toUByte().toInt()
                            }
                        }
                    }
                }
                if (exitCode == 0) return@withContext stdoutStream.toByteArray()
            } catch (_: Exception) {
                // V2 读取失败则继续回退 V1
            } finally {
                v2Stream.close()
            }
        }

        // 回退 V1 (exec:) 原始流读取
        val v1Stream = connection.openStream("exec:$command")
            ?: throw IllegalStateException("Failed to open exec stream for raw bytes")

        val bytesOutput = ByteArrayOutputStream()
        try {
            while (true) {
                val data = v1Stream.read() ?: break
                if (data.isNotEmpty()) bytesOutput.write(data)
            }
        } finally {
            v1Stream.close()
        }

        bytesOutput.toByteArray()
    }

    @Deprecated("Use execRawBytes instead", ReplaceWith("execRawBytes(command)"))
    public suspend fun execV2RawBytes(command: String): ByteArray = execRawBytes(command)

    /**
     * 智能流式传输 Flow（不依赖 Feature，优先 V2 建流，建流失败无缝回退 V1）
     */
    public fun execStream(command: String): Flow<ShellStreamChunk> = flow {
        // 尝试打开 V2 流
        val v2Stream = connection.openStream("shell,v2,raw:$command")
        if (v2Stream != null) {
            var v2Success = false
            try {
                readShellV2Stream(v2Stream) { packet ->
                    v2Success = true
                    when (packet.id) {
                        ShellV2Packet.ID_STDOUT -> emit(ShellStreamChunk(ShellStreamType.STDOUT, packet.payload))
                        ShellV2Packet.ID_STDERR -> emit(ShellStreamChunk(ShellStreamType.STDERR, packet.payload))
                    }
                }
                if (v2Success) return@flow
            } catch (_: Exception) {
                // V2 中途解析异常，准备回退 V1
            } finally {
                v2Stream.close()
            }
        }

        // 降级使用 Exec V1 模式
        val v1Stream = connection.openStream("exec:$command") ?: return@flow
        try {
            while (true) {
                val data = v1Stream.read() ?: break
                if (data.isNotEmpty()) {
                    emit(ShellStreamChunk(ShellStreamType.STDOUT, data))
                }
            }
        } finally {
            v1Stream.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 通用 Shell V2 数据流读取与轮询辅助函数
     */
    private suspend inline fun readShellV2Stream(
        stream: AdbStream,
        crossinline onPacket: suspend (ShellV2Packet) -> Unit
    ) {
        val buffer = ShellV2Buffer()
        while (true) {
            val data = stream.read() ?: break
            if (data.isNotEmpty()) {
                buffer.append(data)
                while (true) {
                    val packet = buffer.pollPacket() ?: break
                    onPacket(packet)
                    if (packet.id == ShellV2Packet.ID_EXIT) return
                }
            }
        }
    }
}

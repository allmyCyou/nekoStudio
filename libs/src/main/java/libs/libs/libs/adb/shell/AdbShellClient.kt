package libs.libs.libs.adb.shell

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

public class AdbShellClient(
    @PublishedApi internal val connection: AdbConnection
) {
    public companion object {
        public const val DEFAULT_MAX_OUTPUT_BYTES: Int = 2 * 1024 * 1024 // 默认最大限制 2MB
        public const val DEFAULT_TIMEOUT_MS: Long = 15_000L              // 默认超时时间 15 秒
    }

    /**
     * 执行 Shell 指令（乐观尝试 Shell V2，失败/不支持时自动无缝降级至 V1）
     */
    public suspend fun exec(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputSize: Int = DEFAULT_MAX_OUTPUT_BYTES
    ): ShellCommandResult = withContext(Dispatchers.IO) {
        val v2Result = execV2(command, timeoutMs, maxOutputSize)
        
        if (v2Result.exitCode == -1 && v2Result.stderr.startsWith("Failed to open shell_v2 stream")) {
            return@withContext execV1(command, timeoutMs, maxOutputSize)
        }
        
        v2Result
    }

    /**
     * 以 Shell V2 模式发送命令 (`shell,v2,raw:`)
     */
    public suspend fun execV2(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputSize: Int = DEFAULT_MAX_OUTPUT_BYTES
    ): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var stream: AdbStream? = null

        try {
            withTimeout(timeoutMs) {
                stream = connection.openStream("shell,v2,raw:$command")
                    ?: return@withTimeout ShellCommandResult(
                        exitCode = -1, stdout = "", stderr = "Failed to open shell_v2 stream", durationMs = 0L
                    )

                val stdoutStream = BoundedOutputStream(maxOutputSize)
                val stderrStream = BoundedOutputStream(maxOutputSize)
                var exitCode = -1

                readShellV2Stream(stream!!) { packet ->
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

                ShellCommandResult(
                    exitCode = exitCode,
                    stdout = stdoutStream.toStringUtf8(),
                    stderr = stderrStream.toStringUtf8(),
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (_: TimeoutCancellationException) {
            ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Command execution timed out after ${timeoutMs}ms",
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shell V2 read failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime
            )
        } finally {
            stream?.close()
        }
    }

    /**
     * 以 Exec (V1) 模式发送命令
     */
    public suspend fun execV1(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputSize: Int = DEFAULT_MAX_OUTPUT_BYTES
    ): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val sentinel = "__ADB_EXIT_CODE_${startTime}__:"
        val wrappedCommand = "($command); printf \"\n$sentinel%d\" \$?"

        var stream: AdbStream? = null
        try {
            withTimeout(timeoutMs) {
                stream = connection.openStream("exec:$wrappedCommand")
                    ?: return@withTimeout ShellCommandResult(
                        exitCode = -1, stdout = "", stderr = "Failed to open exec stream", durationMs = 0L
                    )

                val outputStream = BoundedOutputStream(maxOutputSize)
                while (true) {
                    val data = stream!!.read() ?: break
                    if (data.isNotEmpty()) outputStream.write(data)
                }

                val rawOutput = outputStream.toStringUtf8()
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
        } catch (_: TimeoutCancellationException) {
            ShellCommandResult(-1, "", "Command execution timed out after ${timeoutMs}ms", System.currentTimeMillis() - startTime)
        } finally {
            stream?.close()
        }
    }

    /**
     * 流式传输 Flow（秒级首包响应，实时输出，不积压堆内存，完美支持 logcat/dumpsys）
     */
    public fun execStream(command: String): Flow<ShellStreamChunk> = flow {
        val v2Stream = connection.openStream("shell,v2,raw:$command")
        if (v2Stream != null) {
            var emittedAny = false
            try {
                readShellV2Stream(v2Stream) { packet ->
                    when (packet.id) {
                        ShellV2Packet.ID_STDOUT -> {
                            emittedAny = true
                            emit(ShellStreamChunk(ShellStreamType.STDOUT, packet.payload))
                        }
                        ShellV2Packet.ID_STDERR -> {
                            emittedAny = true
                            emit(ShellStreamChunk(ShellStreamType.STDERR, packet.payload))
                        }
                        ShellV2Packet.ID_EXIT -> emittedAny = true
                    }
                }
                if (emittedAny) return@flow
            } catch (e: Exception) {
                if (emittedAny) throw e
            } finally {
                v2Stream.close()
            }
        }

        // V1 降级通道
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

    public suspend fun execRawBytes(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputSize: Int = DEFAULT_MAX_OUTPUT_BYTES
    ): ByteArray = withContext(Dispatchers.IO) {
        val v2Stream = connection.openStream("shell,v2,raw:$command")
        if (v2Stream != null) {
            val stdoutStream = BoundedOutputStream(maxOutputSize)
            var v2Executed = false
            try {
                withTimeout(timeoutMs) {
                    readShellV2Stream(v2Stream) { packet ->
                        when (packet.id) {
                            ShellV2Packet.ID_STDOUT -> {
                                v2Executed = true
                                stdoutStream.write(packet.payload)
                            }
                            ShellV2Packet.ID_EXIT -> v2Executed = true
                        }
                    }
                }
                if (v2Executed) return@withContext stdoutStream.toByteArray()
            } catch (_: Exception) {
            } finally {
                v2Stream.close()
            }
        }

        val v1Stream = connection.openStream("exec:$command")
            ?: throw IllegalStateException("Failed to open exec stream for raw bytes")

        val bytesOutput = BoundedOutputStream(maxOutputSize)
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    val data = v1Stream.read() ?: break
                    if (data.isNotEmpty()) bytesOutput.write(data)
                }
            }
        } finally {
            v1Stream.close()
        }

        bytesOutput.toByteArray()
    }

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

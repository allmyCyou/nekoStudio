package libs.libs.libs.adb.shell

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

public class AdbShellClient(
    @PublishedApi internal val connection: AdbConnection
) {
    // 用于跟踪当前正在运行/活跃的 Shell 流，支持外部通过 exit() 主动中断
    private val activeStreamRef = AtomicReference<AdbStream?>(null)

    /**
     * 执行 Shell 指令（乐观尝试 Shell V2，失败/不支持时自动无缝降级至 V1）
     */
    public suspend fun exec(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        val v2Result = execV2(command)
        if (v2Result.exitCode == -1 && v2Result.stderr.startsWith("Failed to open shell_v2 stream")) {
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

        setAndRegisterActiveStream(stream)

        val outputStream = ByteArrayOutputStream()
        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) outputStream.write(data)
            }
        } finally {
            clearActiveStream(stream)
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

        setAndRegisterActiveStream(stream)

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
            clearActiveStream(stream)
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
        val v2Stream = connection.openStream("shell,v2,raw:$command")
        if (v2Stream != null) {
            setAndRegisterActiveStream(v2Stream)
            val stdoutStream = ByteArrayOutputStream()
            var v2Executed = false
            try {
                readShellV2Stream(v2Stream) { packet ->
                    when (packet.id) {
                        ShellV2Packet.ID_STDOUT -> {
                            v2Executed = true
                            stdoutStream.write(packet.payload)
                        }
                        ShellV2Packet.ID_EXIT -> v2Executed = true
                    }
                }
                if (v2Executed) return@withContext stdoutStream.toByteArray()
            } catch (_: Exception) {
                // 建流/解析前失败才进行 V1 降级
            } finally {
                clearActiveStream(v2Stream)
                v2Stream.close()
            }
        }

        val v1Stream = connection.openStream("exec:$command")
            ?: throw IllegalStateException("Failed to open exec stream for raw bytes")

        setAndRegisterActiveStream(v1Stream)
        val bytesOutput = ByteArrayOutputStream()
        try {
            while (true) {
                val data = v1Stream.read() ?: break
                if (data.isNotEmpty()) bytesOutput.write(data)
            }
        } finally {
            clearActiveStream(v1Stream)
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
        val v2Stream = connection.openStream("shell,v2,raw:$command")
        if (v2Stream != null) {
            setAndRegisterActiveStream(v2Stream)
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
                clearActiveStream(v2Stream)
                v2Stream.close()
            }
        }

        val v1Stream = connection.openStream("exec:$command") ?: return@flow
        setAndRegisterActiveStream(v1Stream)
        try {
            while (true) {
                val data = v1Stream.read() ?: break
                if (data.isNotEmpty()) {
                    emit(ShellStreamChunk(ShellStreamType.STDOUT, data))
                }
            }
        } finally {
            clearActiveStream(v1Stream)
            v1Stream.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * **主动关闭/退出当前正在运行的 Shell 流**
     */
    public suspend fun exit(): Unit = withContext(Dispatchers.IO) {
        val stream = activeStreamRef.getAndSet(null)
        stream?.close()
    }

    private fun setAndRegisterActiveStream(stream: AdbStream) {
        activeStreamRef.set(stream)
    }

    private fun clearActiveStream(stream: AdbStream) {
        activeStreamRef.compareAndSet(stream, null)
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

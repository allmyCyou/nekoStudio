package libs.libs.libs.adb.shell

import libs.libs.libs.adb.connect.AdbConnection
import libs.libs.libs.adb.connect.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

public class AdbShellClient(
    @PublishedApi internal val connection: AdbConnection
) {
    public companion object {
        public const val DEFAULT_MAX_OUTPUT_BYTES: Int = 4 * 1024
        public const val DEFAULT_TIMEOUT_MS: Long = 15_000L
    }

    private val sessionCounter = AtomicLong(0)
    
    // 维护当前正在运行的所有 Shell 活跃流映射 (sessionId -> AdbStream)
    private val activeStreams = ConcurrentHashMap<Long, AdbStream>()

    // ThreadLocal 复用 ShellV2Buffer，避免每次调用都 new Buffer
    private val threadLocalBuffer = ThreadLocal.withInitial { ShellV2Buffer(4 * 1024) }

    /**
     * 主动根据 sessionId 关闭指定 Shell 持续流（如终止某个 logcat）
     */
    public suspend fun exit(sessionId: Long): Boolean = withContext(Dispatchers.IO) {
        val stream = activeStreams.remove(sessionId) ?: return@withContext false
        try {
            stream.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 主动关闭 Client 管理的所有活跃 Shell 流
     */
    public suspend fun exitAll(): Unit = withContext(Dispatchers.IO) {
        val iterator = activeStreams.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            try {
                entry.value.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * 获取当前正在运行的 Shell 会话数量
     */
    public val activeSessionCount: Int get() = activeStreams.size

    // 秒级响应：流式传输 Flow (单行更新机制)

    /**
     * 启动按行更新的流式 Shell 会话，返回 [sessionId] 以及按行发射的 [Flow]
     * 每一个发射出的 [ShellStreamChunk] 均精确对应远端输出的【单行数据】
     */
    public fun execStreamWithSession(command: String): Pair<Long, Flow<ShellStreamChunk>> {
        val sessionId = sessionCounter.incrementAndGet()

        val streamFlow = flow {
            var emittedAny = false

            // 创建 STDOUT 与 STDERR 独立行缓冲区
            val stdoutLineBuffer = LineBuffer { lineBytes ->
                emittedAny = true
                emit(ShellStreamChunk(ShellStreamType.STDOUT, lineBytes))
            }

            val stderrLineBuffer = LineBuffer { lineBytes ->
                emittedAny = true
                emit(ShellStreamChunk(ShellStreamType.STDERR, lineBytes))
            }

            // 安全尝试打开 V2 流
            val v2Stream = runCatching { connection.openStream("shell,v2,raw:$command") }.getOrNull()
            if (v2Stream != null) {
                activeStreams[sessionId] = v2Stream
                try {
                    readShellV2StreamZeroAlloc(v2Stream) { id, buffer, offset, length ->
                        when (id) {
                            ShellV2Packet.ID_STDOUT -> stdoutLineBuffer.append(buffer, offset, length)
                            ShellV2Packet.ID_STDERR -> stderrLineBuffer.append(buffer, offset, length)
                            ShellV2Packet.ID_EXIT -> emittedAny = true
                        }
                    }
                    stdoutLineBuffer.flush()
                    stderrLineBuffer.flush()
                    if (emittedAny) return@flow
                } catch (e: Exception) {
                    stdoutLineBuffer.flush()
                    stderrLineBuffer.flush()
                    if (emittedAny) throw e
                } finally {
                    activeStreams.remove(sessionId)
                    v2Stream.close()
                }
            }

            // V1 降级通道：按行处理数据
            val v1Stream = runCatching { connection.openStream("exec:$command") }.getOrNull() ?: return@flow
            activeStreams[sessionId] = v1Stream
            try {
                while (true) {
                    val data = v1Stream.read() ?: break
                    if (data.isNotEmpty()) {
                        stdoutLineBuffer.append(data, 0, data.size)
                    }
                }
                stdoutLineBuffer.flush()
            } finally {
                activeStreams.remove(sessionId)
                v1Stream.close()
            }
        }.flowOn(Dispatchers.IO)

        return sessionId to streamFlow
    }

    /**
     * 兼容性按行更新 Flow 接口 (无需关注 sessionId)
     */
    public fun execStream(command: String): Flow<ShellStreamChunk> {
        return execStreamWithSession(command).second
    }

    /**
     * UI 友好型按行文本 Flow 接口
     * 每次 `collect` 直接接收包含单行 UTF-8 文本的 [String]
     */
    public fun execTextStream(command: String): Flow<String> {
        return execStream(command).map { chunk ->
            String(chunk.data, Charsets.UTF_8)
        }
    }

    // 一次性指令执行：防爆内存 + 超时保护

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

    public suspend fun execV2(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputSize: Int = DEFAULT_MAX_OUTPUT_BYTES
    ): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val sessionId = sessionCounter.incrementAndGet()
        var stream: AdbStream? = null

        try {
            withTimeout(timeoutMs) {
                stream = runCatching { connection.openStream("shell,v2,raw:$command") }.getOrNull()
                    ?: return@withTimeout ShellCommandResult(
                        exitCode = -1, stdout = "", stderr = "Failed to open shell_v2 stream: connection inactive or failed", durationMs = 0L
                    )

                activeStreams[sessionId] = stream

                val stdoutStream = BoundedOutputStream(maxOutputSize)
                val stderrStream = BoundedOutputStream(maxOutputSize)
                var exitCode = -1

                readShellV2StreamZeroAlloc(stream) { id, buf, offset, length ->
                    when (id) {
                        ShellV2Packet.ID_STDOUT -> stdoutStream.write(buf, offset, length)
                        ShellV2Packet.ID_STDERR -> stderrStream.write(buf, offset, length)
                        ShellV2Packet.ID_EXIT -> {
                            if (length > 0) {
                                exitCode = buf[offset].toInt() and 0xFF
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
                exitCode = -1, stdout = "", stderr = "Command execution timed out after ${timeoutMs}ms",
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            ShellCommandResult(
                exitCode = -1, stdout = "", stderr = "Shell V2 read failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime
            )
        } finally {
            activeStreams.remove(sessionId)
            stream?.close()
        }
    }

    public suspend fun execV1(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputSize: Int = DEFAULT_MAX_OUTPUT_BYTES
    ): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val sessionId = sessionCounter.incrementAndGet()
        val sentinel = "__ADB_EXIT_CODE_${startTime}__:"
        val wrappedCommand = "($command); printf \"\n$sentinel%d\" \$?"

        var stream: AdbStream? = null
        try {
            withTimeout(timeoutMs) {
                stream = runCatching { connection.openStream("exec:$wrappedCommand") }.getOrNull()
                    ?: return@withTimeout ShellCommandResult(
                        exitCode = -1, stdout = "", stderr = "Failed to open exec stream: connection inactive or failed", durationMs = 0L
                    )

                activeStreams[sessionId] = stream

                val outputStream = BoundedOutputStream(maxOutputSize)
                while (true) {
                    val data = stream.read() ?: break
                    if (data.isNotEmpty()) {
                        outputStream.write(data, 0, data.size)
                    }
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
            activeStreams.remove(sessionId)
            stream?.close()
        }
    }

    /**
     * 零堆分配读循环（Shell 协议解析层）
     */
    private suspend inline fun readShellV2StreamZeroAlloc(
        stream: AdbStream,
        onPacket: (id: Int, buffer: ByteArray, offset: Int, length: Int) -> Unit
    ) {
        val buffer = checkNotNull(threadLocalBuffer.get())
        buffer.reset()

        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) {
                    buffer.append(data)
                    while (true) {
                        var hasPacket = false
                        var isExit = false

                        buffer.pollPacket { id, buf, offset, length ->
                            hasPacket = true
                            if (id == ShellV2Packet.ID_EXIT) {
                                isExit = true
                            }
                            onPacket(id, buf, offset, length)
                        }

                        if (isExit) return
                        if (!hasPacket) break
                    }
                }
            }
        } finally {
            buffer.reset()
        }
    }
}

/**
 * 低开销高效字节流按行切分缓冲区
 */
internal class LineBuffer(
    private val onLine: suspend (ByteArray) -> Unit
) {
    private var buffer = ByteArray(2048)
    private var size = 0

    suspend fun append(src: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        ensureCapacity(size + length)
        System.arraycopy(src, offset, buffer, size, length)
        size += length

        var searchStart = 0
        var i = 0
        while (i < size) {
            if (buffer[i] == '\n'.code.toByte()) {
                var lineEnd = i
                if (lineEnd > searchStart && buffer[lineEnd - 1] == '\r'.code.toByte()) {
                    lineEnd--
                }
                val lineLen = lineEnd - searchStart
                val lineBytes = ByteArray(lineLen)
                if (lineLen > 0) {
                    System.arraycopy(buffer, searchStart, lineBytes, 0, lineLen)
                }
                onLine(lineBytes)
                searchStart = i + 1
            }
            i++
        }

        if (searchStart > 0) {
            val remaining = size - searchStart
            if (remaining > 0) {
                System.arraycopy(buffer, searchStart, buffer, 0, remaining)
            }
            size = remaining
        }
    }

    suspend fun flush() {
        if (size > 0) {
            var lineEnd = size
            if (lineEnd > 0 && buffer[lineEnd - 1] == '\r'.code.toByte()) {
                lineEnd--
            }
            val lineBytes = ByteArray(lineEnd)
            if (lineEnd > 0) {
                System.arraycopy(buffer, 0, lineBytes, 0, lineEnd)
            }
            onLine(lineBytes)
            size = 0
        }
    }

    private fun ensureCapacity(needed: Int) {
        if (buffer.size < needed) {
            var newCap = buffer.size * 2
            while (newCap < needed) newCap *= 2
            val newBuf = ByteArray(newCap)
            if (size > 0) {
                System.arraycopy(buffer, 0, newBuf, 0, size)
            }
            buffer = newBuf
        }
    }
}

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

public class AdbShellClient(
    @PublishedApi internal val connection: AdbConnection
) {
    public companion object {
        public const val DEFAULT_MAX_OUTPUT_BYTES: Int = 16 * 1024
        public const val DEFAULT_TIMEOUT_MS: Long = 15_000L
    }

    private val sessionCounter = AtomicLong(0)
    
    // 维护当前正在运行的所有 Shell 活跃流映射 (sessionId -> AdbStream)
    private val activeStreams = ConcurrentHashMap<Long, AdbStream>()

    // ThreadLocal 复用 ShellV2Buffer，避免每次调用都 new Buffer
    private val threadLocalBuffer = ThreadLocal.withInitial { ShellV2Buffer(16 * 1024) }

    /**
     * 主动根据 sessionId 关闭指定 Shell 持续流（如终止某个 logcat）
     * 内部通过调用 [AdbStream.close] 向设备端发送 ADB CLSE 报文，终止远端进程
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
     * 主动关闭 Client 管理的所有活跃 Shell 流（如页面销毁、连接断开时一键清理）
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

    // 秒级响应：流式传输 Flow (支持 Session 管理)

    /**
     * 启动流式 Shell 会话，返回分配的 [sessionId] 以及对应的 [Flow]
     * 完美支持 logcat/dumpsys 等秒级输出、持续性或海量日志指令
     */
    public fun execStreamWithSession(command: String): Pair<Long, Flow<ShellStreamChunk>> {
        val sessionId = sessionCounter.incrementAndGet()

        val streamFlow = flow {
            val v2Stream = connection.openStream("shell,v2,raw:$command")
            if (v2Stream != null) {
                activeStreams[sessionId] = v2Stream
                var emittedAny = false
                try {
                    readShellV2StreamZeroAlloc(v2Stream) { id, buffer, offset, length ->
                        when (id) {
                            ShellV2Packet.ID_STDOUT -> {
                                emittedAny = true
                                // 拷贝当前 Chunk 切片交给 Flow 下游消费（Buffer 内部数组会在后续读取中被覆写）
                                val payload = buffer.copyOfRange(offset, offset + length)
                                emit(ShellStreamChunk(ShellStreamType.STDOUT, payload))
                            }
                            ShellV2Packet.ID_STDERR -> {
                                emittedAny = true
                                val payload = buffer.copyOfRange(offset, offset + length)
                                emit(ShellStreamChunk(ShellStreamType.STDERR, payload))
                            }
                            ShellV2Packet.ID_EXIT -> emittedAny = true
                        }
                    }
                    if (emittedAny) return@flow
                } catch (e: Exception) {
                    if (emittedAny) throw e
                } finally {
                    activeStreams.remove(sessionId)
                    v2Stream.close()
                }
            }

            // V1 降级通道
            val v1Stream = connection.openStream("exec:$command") ?: return@flow
            activeStreams[sessionId] = v1Stream
            try {
                while (true) {
                    val data = v1Stream.read() ?: break
                    if (data.isNotEmpty()) {
                        emit(ShellStreamChunk(ShellStreamType.STDOUT, data))
                    }
                }
            } finally {
                activeStreams.remove(sessionId)
                v1Stream.close()
            }
        }.flowOn(Dispatchers.IO)

        return sessionId to streamFlow
    }

    /**
     * 兼容性简易 Flow 接口 (无需关注 sessionId)
     */
    public fun execStream(command: String): Flow<ShellStreamChunk> {
        return execStreamWithSession(command).second
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
                stream = connection.openStream("shell,v2,raw:$command")
                    ?: return@withTimeout ShellCommandResult(
                        exitCode = -1, stdout = "", stderr = "Failed to open shell_v2 stream", durationMs = 0L
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
                stream = connection.openStream("exec:$wrappedCommand")
                    ?: return@withTimeout ShellCommandResult(
                        exitCode = -1, stdout = "", stderr = "Failed to open exec stream", durationMs = 0L
                    )

                activeStreams[sessionId] = stream

                val outputStream = BoundedOutputStream(maxOutputSize)
                while (true) {
                    val data = stream.read() ?: break
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
            activeStreams.remove(sessionId)
            stream?.close()
        }
    }

    /**
     * 零堆分配读循环（Shell 协议解析层）
     */
    private suspend inline fun readShellV2StreamZeroAlloc(
        stream: AdbStream,
        crossinline onPacket: (id: Int, buffer: ByteArray, offset: Int, length: Int) -> Unit
    ) {
        val buffer = threadLocalBuffer.get()
        buffer.reset()

        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) {
                    buffer.append(data)
                    while (true) {
                        var hasPacket = false
                        var isExit = false

                        // 零拷贝指针解析
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

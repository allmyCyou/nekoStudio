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
    public val supportsShellV2: Boolean get() = connection.hasFeature("shell_v2")

    /**
     * 执行 Shell 指令（优先尝试 Shell V2，失败自动降级使用 V1）
     */
    public suspend fun exec(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        if (supportsShellV2) {
            val v2Result = execV2(command)
            // 当 V2 建流失败时，自动降级至 V1 重新尝试
            if (v2Result.exitCode == -1 && v2Result.stderr.contains("Failed to open shell_v2 stream")) {
                return@withContext execV1(command)
            }
            v2Result
        } else {
            execV1(command)
        }
    }

    /**
     * 以 Exec (V1) 模式发送命令
     */
    public suspend fun execV1(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val sentinel = "__ADB_EXIT_CODE_${startTime}__:"
        // 1. \n 被转义为真正的 \n，由远端 printf 解析为换行
        // 2. $? 确保运行时导出纯粹的 $? 由远端 Shell 解释 exit code
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
     * 读取无损 STDOUT 二进制字节数组（适用于二进制 Dump、截图等）
     */
    public suspend fun execV2RawBytes(command: String): ByteArray = withContext(Dispatchers.IO) {
        val stream = connection.openStream("shell,v2,raw:$command")
            ?: throw IllegalStateException("Failed to open shell_v2 stream")

        val stdoutStream = ByteArrayOutputStream()
        var exitCode = -1

        try {
            readShellV2Stream(stream) { packet ->
                when (packet.id) {
                    ShellV2Packet.ID_STDOUT -> stdoutStream.write(packet.payload)
                    ShellV2Packet.ID_EXIT -> {
                        if (packet.payload.isNotEmpty()) {
                            exitCode = packet.payload[0].toUByte().toInt()
                        }
                    }
                }
            }
        } finally {
            stream.close()
        }

        check(exitCode == 0) { "Execution failed with exit code $exitCode" }
        stdoutStream.toByteArray()
    }

    /**
     * 智能流式响应传输 (优先 Shell V2，失败无缝回退 V1)
     */
    public fun execStream(command: String): Flow<ShellStreamChunk> = flow {
        if (supportsShellV2) {
            val stream = connection.openStream("shell,v2,raw:$command")
            if (stream != null) {
                try {
                    readShellV2Stream(stream) { packet ->
                        when (packet.id) {
                            ShellV2Packet.ID_STDOUT -> emit(ShellStreamChunk(ShellStreamType.STDOUT, packet.payload))
                            ShellV2Packet.ID_STDERR -> emit(ShellStreamChunk(ShellStreamType.STDERR, packet.payload))
                        }
                    }
                } finally {
                    stream.close()
                }
                return@flow
            }
        }

        // 降级使用 Exec V1 模式
        val stream = connection.openStream("exec:$command") ?: return@flow
        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) {
                    emit(ShellStreamChunk(ShellStreamType.STDOUT, data))
                }
            }
        } finally {
            stream.close()
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

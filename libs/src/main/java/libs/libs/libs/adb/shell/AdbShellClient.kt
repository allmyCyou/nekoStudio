package libs.libs.libs.adb.shell

import libs.libs.libs.adb.connect.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalSerializationApi::class)
public class AdbShellClient(
    @PublishedApi internal val connection: AdbConnection,
    @PublishedApi internal val protoBuf: ProtoBuf = ProtoBuf
) {
    public val supportsShellV2: Boolean get() = connection.hasFeature("shell_v2")

    /**
     * 执行 Shell 指令（优先尝试 Shell V2，降级使用 V1）
     */
    public suspend fun exec(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        if (supportsShellV2) execV2(command) else execV1(command)
    }

    /**
     * 强行以 Exec (V1) 模式发送命令
     */
    public suspend fun execV1(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val sentinel = "__ADB_EXIT_CODE_${System.currentTimeMillis()}__:"
        // 修复转义：\n 表示换行，%d 直接写入，\$? 正确转义 Shell 变量 $? 避免 Kotlin 模板符号冲突
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
        if (!supportsShellV2) return@withContext execV1(command)

        val startTime = System.currentTimeMillis()
        val stream = connection.openStream("shell,v2,raw:$command")
            ?: return@withContext ShellCommandResult(
                exitCode = -1, stdout = "", stderr = "Failed to open shell_v2 stream", durationMs = 0L
            )

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
     * 读取无损 STDOUT 二进制字节数组（适合截屏、二进制文件 Dump）
     */
    public suspend fun execV2RawBytes(command: String): ByteArray = withContext(Dispatchers.IO) {
        val stream = connection.openStream("shell,v2,raw:$command")
            ?: throw IllegalStateException("Failed to open shell_v2 stream")

        val stdoutStream = ByteArrayOutputStream()
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
                            ShellV2Packet.ID_EXIT -> {
                                if (packet.payload.isNotEmpty()) {
                                    exitCode = packet.payload[0].toInt() and 0xFF
                                }
                            }
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
     * 流式响应传输
     */
    public fun execStream(command: String): Flow<ShellStreamChunk> = flow {
        if (supportsShellV2) {
            val stream = connection.openStream("shell,v2,raw:$command") ?: return@flow
            val v2Buffer = ShellV2Buffer()
            try {
                while (true) {
                    val data = stream.read() ?: break
                    if (data.isNotEmpty()) {
                        v2Buffer.append(data)
                        while (true) {
                            val packet = v2Buffer.pollPacket() ?: break
                            when (packet.id) {
                                ShellV2Packet.ID_STDOUT -> emit(ShellStreamChunk(ShellStreamType.STDOUT, packet.payload))
                                ShellV2Packet.ID_STDERR -> emit(ShellStreamChunk(ShellStreamType.STDERR, packet.payload))
                                ShellV2Packet.ID_EXIT -> return@flow
                            }
                        }
                    }
                }
            } finally {
                stream.close()
            }
        } else {
            val stream = connection.openStream("exec:$command") ?: return@flow
            try {
                while (true) {
                    val data = stream.read() ?: break
                    if (data.isNotEmpty()) emit(ShellStreamChunk(ShellStreamType.STDOUT, data))
                }
            } finally {
                stream.close()
            }
        }
    }.flowOn(Dispatchers.IO)
}

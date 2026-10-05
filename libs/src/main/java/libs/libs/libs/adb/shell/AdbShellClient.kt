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

    public suspend fun exec(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        if (supportsShellV2) {
            execV2(command)
        } else {
            execV1(command)
        }
    }

    public suspend fun execV1(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val sentinel = "__ADB_EXIT_CODE_${System.currentTimeMillis()}__:"
        // 使用 printf 确保跨 Android POSIX 环境的一致性
        val wrappedCommand = "($command); printf \"\\n$sentinel%%d\" $?"

        val stream = connection.openStream("exec:$wrappedCommand")
            ?: return@withContext ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to open exec (V1) stream",
                durationMs = 0L
            )

        val outputStream = ByteArrayOutputStream()
        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) {
                    outputStream.write(data)
                }
            }
        } finally {
            stream.close()
        }

        val rawOutput = outputStream.toString(Charsets.UTF_8.name())
        val sentinelIndex = rawOutput.lastIndexOf(sentinel)

        val (stdout, exitCode) = if (sentinelIndex != -1) {
            var stdoutRaw = rawOutput.substring(0, sentinelIndex)
            // 精确剥离我们附加的换行符，不破坏原始 stdout 的末尾换行
            if (stdoutRaw.endsWith("\r\n")) {
                stdoutRaw = stdoutRaw.substring(0, stdoutRaw.length - 2)
            } else if (stdoutRaw.endsWith("\n")) {
                stdoutRaw = stdoutRaw.substring(0, stdoutRaw.length - 1)
            }
            val exitCodeStr = rawOutput.substring(sentinelIndex + sentinel.length).trim()
            val code = exitCodeStr.toIntOrNull() ?: 0
            stdoutRaw to code
        } else {
            rawOutput to 0
        }

        ShellCommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = "",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    public suspend fun execV2(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        if (!supportsShellV2) return@withContext execV1(command)

        val startTime = System.currentTimeMillis()
        val stream = connection.openStream("shell,v2,raw:$command")
            ?: return@withContext ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to open shell_v2 stream",
                durationMs = 0L
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

    public suspend fun execRawBytes(command: String): ByteArray = withContext(Dispatchers.IO) {
        val stream = connection.openStream("exec:$command")
            ?: throw IllegalStateException("Failed to open stream for $command")

        val output = ByteArrayOutputStream()
        try {
            while (true) {
                val data = stream.read() ?: break
                if (data.isNotEmpty()) output.write(data)
            }
        } finally {
            stream.close()
        }
        output.toByteArray()
    }

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

        check(exitCode == 0) { "Shell V2 execution failed with exit code $exitCode" }
        stdoutStream.toByteArray()
    }

    public suspend inline fun <reified T> execProto(command: String): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = if (this@AdbShellClient.supportsShellV2) {
                this@AdbShellClient.execV2RawBytes(command)
            } else {
                this@AdbShellClient.execRawBytes(command)
            }
            protoBuf.decodeFromByteArray<T>(bytes)
        }
    }

    @Deprecated("Renamed to execProto for standard naming", ReplaceWith("execProto<T>(command)"))
    public suspend inline fun <reified T> execV2Proto(command: String): Result<T> = execProto(command)

    public suspend inline fun <reified Req, reified Resp> execProtoWithInput(
        command: String,
        requestPayload: Req
    ): Result<Resp> = withContext(Dispatchers.IO) {
        runCatching {
            val inputBytes = protoBuf.encodeToByteArray(requestPayload)

            val outputBytes = if (this@AdbShellClient.supportsShellV2) {
                val stream = connection.openStream("shell,v2,raw:$command")
                    ?: throw IllegalStateException("Failed to open shell_v2 stream")

                val stdoutStream = ByteArrayOutputStream()
                val v2Buffer = ShellV2Buffer()
                var exitCode = 0

                try {
                    // STDIN 进行 8KB Chunk 分包发送，防止超出底层流写限制
                    val chunkSize = 8192
                    var offset = 0
                    while (offset < inputBytes.size) {
                        val len = minOf(chunkSize, inputBytes.size - offset)
                        val chunk = inputBytes.copyOfRange(offset, offset + len)
                        stream.write(ShellV2Packet.createFrame(ShellV2Packet.ID_STDIN, chunk))
                        offset += len
                    }
                    stream.write(ShellV2Packet.createFrame(ShellV2Packet.ID_CLOSE_STDIN))

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

                check(exitCode == 0) { "Command execution failed with exit code $exitCode" }
                stdoutStream.toByteArray()
            } else {
                val stream = connection.openStream("exec:$command")
                    ?: throw IllegalStateException("Failed to open exec stream")

                val stdoutStream = ByteArrayOutputStream()
                try {
                    stream.write(inputBytes)
                    while (true) {
                        val data = stream.read() ?: break
                        if (data.isNotEmpty()) stdoutStream.write(data)
                    }
                } finally {
                    stream.close()
                }
                stdoutStream.toByteArray()
            }

            protoBuf.decodeFromByteArray<Resp>(outputBytes)
        }
    }

    public fun execStream(command: String): Flow<ShellStreamChunk> = flow {
        if (supportsShellV2) {
            val stream = connection.openStream("shell,v2,raw:$command")
            if (stream == null) {
                emit(ShellStreamChunk(ShellStreamType.ERROR, "Failed to open shell_v2 stream".toByteArray()))
                return@flow
            }

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
            val stream = connection.openStream("exec:$command")
            if (stream == null) {
                emit(ShellStreamChunk(ShellStreamType.ERROR, "Failed to open exec stream".toByteArray()))
                return@flow
            }

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
        }
    }.flowOn(Dispatchers.IO)

    public fun execV2Stream(command: String): Flow<ShellStreamChunk> = execStream(command)
}

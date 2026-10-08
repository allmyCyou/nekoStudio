package libs.libs.libs.adb.root

import libs.libs.libs.adb.connect.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class AdbRootClient(private val connection: AdbConnection) {

    /**
     * 向目标 adbd 发送 `root:` 服务请求，指示守护进程尝试以 root 身份重启
     */
    public suspend fun requestRoot(): AdbRootResult = withContext(Dispatchers.IO) {
        val rawResponse = sendServiceCommand("root:")
        parseRootResponse(rawResponse)
    }

    /**
     * 向目标 adbd 发送 `unroot:` 服务请求，指示守护进程恢复为普通 shell 身份重启
     */
    public suspend fun requestUnroot(): AdbRootResult = withContext(Dispatchers.IO) {
        val rawResponse = sendServiceCommand("unroot:")
        parseRootResponse(rawResponse)
    }

    private suspend fun sendServiceCommand(serviceCommand: String): String {
        // 1. 发送 CMD_OPEN 报文打开 "root:" 或 "unroot:" 服务通道
        val stream = connection.openStream(serviceCommand)
            ?: return "Failed to open $serviceCommand stream"

        val responseBuilder = StringBuilder()

        try {
            // 2. 读取 adbd 返回的状态反馈
            while (true) {
                val data = stream.read() ?: break
                if (data.isEmpty()) continue
                responseBuilder.append(String(data, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // 关键逻辑：如果读取过程中因 adbd 重启引发了 Socket 异常/TCP RST，
            // 但此时我们已经读取到了 "restarting adbd"，则这是符合期待的正常中断，不应追加错误提示
            val currentText = responseBuilder.toString()
            if (!currentText.contains("restarting adbd")) {
                responseBuilder.append("\n[Stream closed unexpectedly: ${e.message}]")
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
                // adbd 重启时关闭流可能会抛出 Socket closed 异常，可安全忽略
            }
        }

        return responseBuilder.toString().trim()
    }

    /**
     * 解析 adbd 返回的原始控制台控制文本
     */
    private fun parseRootResponse(rawMessage: String): AdbRootResult {
        val lowerText = rawMessage.lowercase()

        val status = when {
            lowerText.contains("restarting adbd as root") -> AdbRootStatus.RESTARTING_AS_ROOT
            lowerText.contains("already running as root") -> AdbRootStatus.ALREADY_ROOT
            lowerText.contains("already running as non-root") || 
            lowerText.contains("already running as default") -> AdbRootStatus.ALREADY_SHELL
            lowerText.contains("restarting adbd") -> AdbRootStatus.RESTARTING_AS_SHELL
            lowerText.contains("cannot run as root") -> AdbRootStatus.DISABLED_IN_PRODUCTION
            else -> AdbRootStatus.FAILED
        }

        return AdbRootResult(status = status, rawMessage = rawMessage)
    }
}

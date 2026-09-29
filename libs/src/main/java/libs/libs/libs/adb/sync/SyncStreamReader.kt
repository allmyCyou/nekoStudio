package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbStream

/**
 * 带有字节缓冲功能的 Sync 流读取器
 * 解决底层 AdbStream 返回的数据包跨帧与粘包问题
 */
public class SyncStreamReader(private val stream: AdbStream) {
    private var currentBuffer: ByteArray? = null
    private var bufferOffset = 0

    public suspend fun readExactBytes(length: Int): ByteArray {
        val result = ByteArray(length)
        var bytesCopied = 0

        while (bytesCopied < length) {
            val buffer = currentBuffer
            if (buffer != null && bufferOffset < buffer.size) {
                val available = buffer.size - bufferOffset
                val needed = length - bytesCopied
                val toCopy = minOf(available, needed)

                System.arraycopy(buffer, bufferOffset, result, bytesCopied, toCopy)
                bufferOffset += toCopy
                bytesCopied += toCopy

                if (bufferOffset >= buffer.size) {
                    currentBuffer = null
                    bufferOffset = 0
                }
            } else {
                val chunk = stream.read() ?: break
                if (chunk.isNotEmpty()) {
                    currentBuffer = chunk
                    bufferOffset = 0
                }
            }
        }

        check(bytesCopied == length) { 
            "Unexpected EOF: expected $length bytes, got $bytesCopied" 
        }
        return result
    }
}

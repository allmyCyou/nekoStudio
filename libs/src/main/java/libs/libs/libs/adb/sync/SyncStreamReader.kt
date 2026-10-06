package libs.libs.libs.adb.sync

import libs.libs.libs.adb.connect.AdbStream
import kotlinx.coroutines.yield
import java.io.OutputStream

/**
 * 带有字节缓冲功能的 Sync 流读取器
 */
public class SyncStreamReader(private val stream: AdbStream) {
    private var currentBuffer: ByteArray? = null
    private var bufferOffset = 0

    public suspend fun readExactBytes(length: Int): ByteArray {
        val result = ByteArray(length)
        readToInternal(result, 0, length)
        return result
    }

    /**
     * 直接写出数据到 target Stream，避免在 Pull 传输中频繁创建 ByteArray
     */
    public suspend fun readToStream(target: OutputStream, length: Int) {
        var bytesCopied = 0
        val tempBuf = ByteArray(minOf(length, 64 * 1024))

        while (bytesCopied < length) {
            val toRead = minOf(tempBuf.size, length - bytesCopied)
            readToInternal(tempBuf, 0, toRead)
            target.write(tempBuf, 0, toRead)
            bytesCopied += toRead
        }
    }

    private suspend fun readToInternal(dest: ByteArray, offset: Int, length: Int) {
        var bytesCopied = 0

        while (bytesCopied < length) {
            val buffer = currentBuffer
            if (buffer != null && bufferOffset < buffer.size) {
                val available = buffer.size - bufferOffset
                val needed = length - bytesCopied
                val toCopy = minOf(available, needed)

                System.arraycopy(buffer, bufferOffset, dest, offset + bytesCopied, toCopy)
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
                } else {
                    yield()
                }
            }
        }

        check(bytesCopied == length) { 
            "Unexpected EOF: expected $length bytes, got $bytesCopied" 
        }
    }
}

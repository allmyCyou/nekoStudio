package libs.libs.libs.adb.shell

import java.io.ByteArrayOutputStream

/**
 * 带有最大字节数限制的 ByteArrayOutputStream，防止海量日志（如 logcat/dumpsys）撑爆内存
 */
internal class BoundedOutputStream(private val maxBytes: Int) {
    private val stream = ByteArrayOutputStream()
    var isTruncated: Boolean = false
        private set

    fun write(bytes: ByteArray) {
        if (isTruncated) return
        val currentSize = stream.size()
        if (currentSize + bytes.size <= maxBytes) {
            stream.write(bytes)
        } else {
            val allowed = maxBytes - currentSize
            if (allowed > 0) {
                stream.write(bytes, 0, allowed)
            }
            isTruncated = true
        }
    }

    fun toStringUtf8(): String {
        val output = stream.toString(Charsets.UTF_8.name())
        return if (isTruncated) {
            "$output\n... [Output Truncated: Exceeded max limit of ${maxBytes / 1024 / 1024}MB]"
        } else output
    }

    fun toByteArray(): ByteArray = stream.toByteArray()
}

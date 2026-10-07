package libs.libs.libs.adb.shell

/**
 * 预分配定长 ByteArray 输出流，无需 ByteArrayOutputStream，防止内存扩容分配
 */
internal class BoundedOutputStream(private val maxBytes: Int) {
    // 预分配固定大小字节数组
    private val buffer = ByteArray(maxBytes)
    var size: Int = 0
        private set

    var isTruncated: Boolean = false
        private set

    fun write(src: ByteArray, offset: Int, length: Int) {
        if (isTruncated || length <= 0) return
        val remaining = maxBytes - size
        if (length <= remaining) {
            System.arraycopy(src, offset, buffer, size, length)
            size += length
        } else {
            if (remaining > 0) {
                System.arraycopy(src, offset, buffer, size, remaining)
                size += remaining
            }
            isTruncated = true
        }
    }

    /**
     * 只有在最终返回 ShellCommandResult 时，才进行一次 UTF-8 解码
     */
    fun toStringUtf8(): String {
        if (size == 0) return ""
        val output = String(buffer, 0, size, Charsets.UTF_8)
        return if (isTruncated) {
            "$output\n... [Output Truncated: Exceeded limit of ${maxBytes / 1024}KB]"
        } else output
    }

    fun reset() {
        size = 0
        isTruncated = false
    }
}

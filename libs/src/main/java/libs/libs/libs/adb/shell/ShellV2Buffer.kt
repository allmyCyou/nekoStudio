package libs.libs.libs.adb.shell

/**
 * 零 JVM 堆分配 Shell V2 解析缓冲区
 */
public class ShellV2Buffer(initialCapacity: Int = 16 * 1024) {
    private companion object {
        private const val MAX_PAYLOAD_SIZE = 4 * 1024 * 1024
    }

    private var buffer = ByteArray(initialCapacity)
    private var head = 0
    private var tail = 0

    val size: Int get() = tail - head

    public fun append(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        ensureCapacity(length)
        System.arraycopy(data, offset, buffer, tail, length)
        tail += length
    }

    /**
     * 零堆分配解包：通过函数内联 + 传递 (buffer, offset, length)
     * 1. 绝不调用 copyOfRange
     * 2. 绝不创建 ShellV2Packet 对象
     */
    public inline fun pollPacket(
        onPacket: (id: Int, buffer: ByteArray, offset: Int, length: Int) -> Unit
    ): Boolean {
        if (size < ShellV2Packet.HEADER_SIZE) return false

        val id = buffer[head].toInt() and 0xFF
        val len = (buffer[head + 1].toInt() and 0xFF) or
                ((buffer[head + 2].toInt() and 0xFF) shl 8) or
                ((buffer[head + 3].toInt() and 0xFF) shl 16) or
                ((buffer[head + 4].toInt() and 0xFF) shl 24)

        require(len in 0..MAX_PAYLOAD_SIZE) { "Invalid shell v2 payload size: $len" }

        val totalSize = ShellV2Packet.HEADER_SIZE + len
        if (size < totalSize) return false

        require(id == ShellV2Packet.ID_STDOUT || id == ShellV2Packet.ID_STDERR || id == ShellV2Packet.ID_EXIT) {
            "Invalid shell packet id: $id"
        }

        val payloadOffset = head + ShellV2Packet.HEADER_SIZE

        // 零分配回调：直接将内部数组引用和指针暴露给消费端
        onPacket(id, buffer, payloadOffset, len)

        head += totalSize
        if (head == tail) {
            head = 0
            tail = 0
        }
        return true
    }

    public fun reset() {
        head = 0
        tail = 0
    }

    private fun ensureCapacity(needed: Int) {
        if (buffer.size - tail >= needed) return

        if (buffer.size - size >= needed) {
            if (size > 0) System.arraycopy(buffer, head, buffer, 0, size)
            head = 0
            tail = size
        } else {
            var newCap = buffer.size * 2
            while (newCap - size < needed) newCap *= 2
            val newBuf = ByteArray(newCap)
            if (size > 0) System.arraycopy(buffer, head, newBuf, 0, size)
            buffer = newBuf
            head = 0
            tail = size
        }
    }
}

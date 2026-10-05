package libs.libs.libs.adb.shell

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 高性能零 GC Shell V2 拼包缓冲区
 * 采用双指针内存紧凑化机制（Memory Compaction）与位运算解析
 */
public class ShellV2Buffer {
    private var buffer = ByteArray(4096)
    private var head = 0
    private var tail = 0

    val size: Int get() = tail - head

    public fun append(data: ByteArray) {
        ensureCapacity(data.size)
        System.arraycopy(data, 0, buffer, tail, data.size)
        tail += data.size
    }

    public fun pollPacket(): ShellV2Packet? {
        if (size < ShellV2Packet.HEADER_SIZE) return null

        val id = buffer[head].toInt() and 0xFF
        
        // 位运算直接解析 Little-Endian Int，避免高频创建 ByteBuffer 对象
        val len = (buffer[head + 1].toInt() and 0xFF) or
                ((buffer[head + 2].toInt() and 0xFF) shl 8) or
                ((buffer[head + 3].toInt() and 0xFF) shl 16) or
                ((buffer[head + 4].toInt() and 0xFF) shl 24)

        val totalSize = ShellV2Packet.HEADER_SIZE + len
        if (size < totalSize) return null

        val payload = buffer.copyOfRange(head + ShellV2Packet.HEADER_SIZE, head + totalSize)
        head += totalSize

        // 缓冲区清空时重置指针
        if (head == tail) {
            head = 0
            tail = 0
        }

        return ShellV2Packet(id, payload)
    }

    private fun ensureCapacity(needed: Int) {
        // 1. tail 尾部空间足够，直接追加
        if (buffer.size - tail >= needed) return

        // 2. 尾部空间不足，但总体空闲空间足够：将未读数据移至数组开头 (Compaction)
        if (buffer.size - size >= needed) {
            if (size > 0) {
                System.arraycopy(buffer, head, buffer, 0, size)
            }
            head = 0
            tail = size
        } else {
            // 3. 总体空间不足，扩容数组
            var newCap = buffer.size * 2
            while (newCap - size < needed) {
                newCap *= 2
            }
            val newBuf = ByteArray(newCap)
            if (size > 0) {
                System.arraycopy(buffer, head, newBuf, 0, size)
            }
            buffer = newBuf
            head = 0
            tail = size
        }
    }
}
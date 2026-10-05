package libs.libs.libs.adb.shell

public class ShellV2Buffer {
    private var buffer = ByteArray(8192)
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
        val len = (buffer[head + 1].toInt() and 0xFF) or
                ((buffer[head + 2].toInt() and 0xFF) shl 8) or
                ((buffer[head + 3].toInt() and 0xFF) shl 16) or
                ((buffer[head + 4].toInt() and 0xFF) shl 24)

        require(len >= 0) { "Invalid shell v2 payload size: $len" }

        val totalSize = ShellV2Packet.HEADER_SIZE + len
        if (size < totalSize) return null

        require(id == ShellV2Packet.ID_STDOUT || id == ShellV2Packet.ID_STDERR || id == ShellV2Packet.ID_EXIT) {
            "Invalid device-to-host shell packet id: $id"
        }

        val payload = buffer.copyOfRange(head + ShellV2Packet.HEADER_SIZE, head + totalSize)
        head += totalSize

        if (head == tail) {
            head = 0
            tail = 0
        }
        return ShellV2Packet(id, payload)
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

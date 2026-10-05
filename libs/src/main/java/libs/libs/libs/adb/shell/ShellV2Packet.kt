package libs.libs.libs.adb.shell

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB Shell V2 数据包 (5 字节 Header + Payload)
 * Header: [1 byte ID][4 bytes Length (Little-Endian)]
 */
public data class ShellV2Packet(
    val id: Int,
    val payload: ByteArray
) {
    public companion object {
        public const val ID_STDIN: Int = 0
        public const val ID_STDOUT: Int = 1
        public const val ID_STDERR: Int = 2
        public const val ID_EXIT: Int = 3
        public const val ID_CLOSE_STDIN: Int = 4
        public const val ID_WINDOW_SIZE_CHANGE: Int = 5

        public const val HEADER_SIZE: Int = 5

        public fun createFrame(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(id.toByte())
            buf.putInt(payload.size)
            buf.put(payload)
            return buf.array()
        }
    }
}

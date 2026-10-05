package libs.libs.libs.adb.shell

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB Shell V2 包结构定义 (遵照 AOSP shell_protocol.h)
 */
public data class ShellV2Packet(
    val id: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ShellV2Packet
        if (id != other.id) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + payload.contentHashCode()
        return result
    }

    public companion object {
        public const val ID_STDIN: Int = 0
        public const val ID_STDOUT: Int = 1
        public const val ID_STDERR: Int = 2
        public const val ID_EXIT: Int = 3
        public const val ID_CLOSE_STDIN: Int = 4
        public const val ID_WINDOW_SIZE_CHANGE: Int = 5
        public const val ID_INVALID: Int = 255

        public const val HEADER_SIZE: Int = 5

        /**
         * 构建 Host -> Device 数据/控制帧
         */
        public fun createFrame(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(id.toByte())
            buf.putInt(payload.size)
            buf.put(payload)
            return buf.array()
        }

        /**
         * 构建 ID_WINDOW_SIZE_CHANGE 控制帧 Payload
         * AOSP 格式: "<rows>x<cols>,<xpixels>x<ypixels>"
         */
        public fun createResizePayload(rows: Int, cols: Int, xPixels: Int = 0, yPixels: Int = 0): ByteArray {
            return "${rows}x${cols},${xPixels}x${yPixels}".toByteArray(Charsets.UTF_8)
        }
    }
}

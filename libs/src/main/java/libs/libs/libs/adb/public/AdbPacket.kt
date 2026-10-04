package libs.libs.libs.adb.public

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 代表一个 ADB 协议底层数据包
 */
public data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0)
) {
    val dataLength: Int = payload.size
    val magic: Int = AdbCommand.calculateMagic(command)

    /**
     * 导出为 24 字节 Header + Payload 的完整 Buffer
     */
    public fun toByteArray(skipChecksum: Boolean = false): ByteArray {
        val checksum = if (skipChecksum) 0 else AdbCommand.calculateChecksum(payload)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLength)
            putInt(checksum)
            putInt(magic)
            put(payload)
        }
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPacket) return false
        return command == other.command &&
                arg0 == other.arg0 &&
                arg1 == other.arg1 &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        public const val HEADER_SIZE: Int = 24

        /**
         * 从 ByteArray 解析出 Header 结构
         */
        public fun parseHeader(headerBytes: ByteArray): Header {
            require(headerBytes.size >= HEADER_SIZE) { "Header length must be at least 24 bytes" }
            val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            return Header(
                command = buffer.int,
                arg0 = buffer.int,
                arg1 = buffer.int,
                dataLength = buffer.int,
                dataCheck = buffer.int,
                magic = buffer.int
            )
        }

        // --- 常用建包便捷工厂方法 ---

        public fun createCnxn(
            version: Int = AdbCommand.A_VERSION,
            maxPayload: Int = AdbCommand.MAX_PAYLOAD,
            systemIdentity: String = "host::\u0000"
        ): AdbPacket = AdbPacket(
            command = AdbCommand.CMD_CNXN,
            arg0 = version,
            arg1 = maxPayload,
            payload = systemIdentity.toByteArray(Charsets.UTF_8)
        )

        public fun createAuth(authType: Int, keyOrSignature: ByteArray): AdbPacket = AdbPacket(
            command = AdbCommand.CMD_AUTH,
            arg0 = authType,
            arg1 = 0,
            payload = keyOrSignature
        )

        public fun createOpen(localId: Int, destination: String): AdbPacket {
            val destBytes = destination.toByteArray(Charsets.UTF_8)
            // ADB OPEN 指令的 destination payload 必须以 \0 结尾
            val payload = if (destBytes.lastOrNull() == 0.toByte()) destBytes else destBytes + 0.toByte()
            return AdbPacket(
                command = AdbCommand.CMD_OPEN,
                arg0 = localId,
                arg1 = 0,
                payload = payload
            )
        }

        public fun createOkay(localId: Int, remoteId: Int): AdbPacket = AdbPacket(
            command = AdbCommand.CMD_OKAY,
            arg0 = localId,
            arg1 = remoteId
        )

        public fun createClose(localId: Int, remoteId: Int): AdbPacket = AdbPacket(
            command = AdbCommand.CMD_CLSE,
            arg0 = localId,
            arg1 = remoteId
        )
    }

    public data class Header(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataCheck: Int,
        val magic: Int
    ) {
        /**
         * 校验 Header 合法性（必须满足 magic 匹配且 payload 长度在安全范围内）
         */
        val isValid: Boolean get() = (command.inv() == magic) && (dataLength in 0..AdbCommand.MAX_PAYLOAD)

        /**
         * 校验接收到的 Payload Checksum 是否正确
         */
        public fun isChecksumValid(payload: ByteArray, skipChecksum: Boolean = false): Boolean {
            if (skipChecksum) return true
            return dataCheck == AdbCommand.calculateChecksum(payload)
        }
    }
}

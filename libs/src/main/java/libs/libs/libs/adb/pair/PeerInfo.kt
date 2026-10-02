package libs.libs.libs.adb.pair

import java.nio.ByteBuffer

public class PeerInfo(
    public val type: Byte = ADB_RSA_PUB_KEY,
    data: ByteArray = byteArrayOf()
) {
    public val data: ByteArray = ByteArray(MAX_PEER_INFO_SIZE - 1)

    init {
        System.arraycopy(data, 0, this.data, 0, data.size.coerceAtMost(MAX_PEER_INFO_SIZE - 1))
    }

    public fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(MAX_PEER_INFO_SIZE)
        buffer.put(type)
        buffer.put(data)
        return buffer.array()
    }

    companion object {
        public const val MAX_PEER_INFO_SIZE: Int = 8192 // 1 shl 13
        public const val ADB_RSA_PUB_KEY: Byte = 0
        public const val ADB_DEVICE_GUID: Byte = 1

        public fun fromByteArray(bytes: ByteArray): PeerInfo {
            require(bytes.size == MAX_PEER_INFO_SIZE) { "Invalid PeerInfo size: ${bytes.size}" }
            val buffer = ByteBuffer.wrap(bytes)
            val type = buffer.get()
            val data = ByteArray(MAX_PEER_INFO_SIZE - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

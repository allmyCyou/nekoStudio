package libs.libs.libs.adb.pair

public data class PairingPacket(
    val type: Int = Type.SPAKE2_MSG,
    val payload: ByteArray = byteArrayOf()
) {
    public object Type {
        public const val SPAKE2_MSG: Int = 0
        public const val PEER_INFO: Int = 1
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PairingPacket
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

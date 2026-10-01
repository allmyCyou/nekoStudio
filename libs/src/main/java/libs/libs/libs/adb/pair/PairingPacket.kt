package libs.libs.libs.adb.pair

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class PairingPacket(
    @ProtoNumber(1) val type: Int = Type.UNKNOWN,
    @ProtoNumber(2) val payload: ByteArray = byteArrayOf()
) {
    public object Type {
        public const val UNKNOWN: Int = 0
        public const val SPAKE2_MSG: Int = 1
        public const val PEER_INFO: Int = 2
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

package libs.libs.libs.adb.pair

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class PeerInfo(
    @ProtoNumber(1) val status: Int = Status.UNKNOWN,
    @ProtoNumber(2) val pubKey: ByteArray = byteArrayOf()
) {
    public object Status {
        public const val UNKNOWN: Int = 0
        public const val OK: Int = 1
        public const val FAIL: Int = 2
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerInfo
        return status == other.status && pubKey.contentEquals(other.pubKey)
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + pubKey.contentHashCode()
        return result
    }
}

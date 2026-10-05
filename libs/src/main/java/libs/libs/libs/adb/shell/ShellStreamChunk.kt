package libs.libs.libs.adb.shell

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class ShellStreamChunk(
    @ProtoNumber(1) val type: ShellStreamType,
    @ProtoNumber(2) val data: ByteArray,
    @ProtoNumber(3) val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ShellStreamChunk
        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

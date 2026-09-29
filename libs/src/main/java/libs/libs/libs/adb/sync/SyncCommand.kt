package libs.libs.libs.adb.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

public object SyncCommand {
    public const val ID_STAT: String = "STAT"
    public const val ID_LIST: String = "LIST"
    public const val ID_DENT: String = "DENT"
    public const val ID_SEND: String = "SEND"
    public const val ID_RECV: String = "RECV"
    public const val ID_DATA: String = "DATA"
    public const val ID_DONE: String = "DONE"
    public const val ID_OKAY: String = "OKAY"
    public const val ID_FAIL: String = "FAIL"

    public const val HEADER_SIZE: Int = 8

    public fun createHeader(id: String, value: Int): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(id.toByteArray(Charsets.US_ASCII))
        buffer.putInt(value)
        return buffer.array()
    }

    public fun parseHeader(data: ByteArray): Pair<String, Int> {
        require(data.size >= HEADER_SIZE) { "Invalid Sync header size: ${data.size}" }
        val id = String(data, 0, 4, Charsets.US_ASCII)
        val value = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return id to value
    }
}

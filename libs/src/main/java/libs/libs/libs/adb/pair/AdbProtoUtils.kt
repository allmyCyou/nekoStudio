package libs.libs.libs.adb.pair

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

public object AdbProtoUtils {

    /**
     * 编码 PairingPacket
     */
    public fun encodePairingPacket(packet: PairingPacket): ByteArray {
        val out = ByteArrayOutputStream()
        // Tag 1 (field_number = 1, wire_type = 0) -> 0x08
        out.write(0x08)
        writeVarint32(out, packet.type)

        if (packet.payload.isNotEmpty()) {
            // Tag 2 (field_number = 2, wire_type = 2) -> 0x12
            out.write(0x12)
            writeVarint32(out, packet.payload.size)
            out.write(packet.payload)
        }
        return out.toByteArray()
    }

    /**
     * 解码 PairingPacket
     */
    public fun decodePairingPacket(bytes: ByteArray): PairingPacket {
        if (bytes.isEmpty()) return PairingPacket()
        val input = ByteArrayInputStream(bytes)
        var type = PairingPacket.Type.SPAKE2_MSG 
        var payload = byteArrayOf()

        while (input.available() > 0) {
            val tag = readVarint32(input)
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == 0) type = readVarint32(input) else skipField(input, wireType)
                2 -> {
                    if (wireType == 2) {
                        payload = readExactBytes(input)
                    } else {
                        skipField(input, wireType)
                    }
                }
                else -> skipField(input, wireType)
            }
        }
        return PairingPacket(type, payload)
    }

    /**
     * 编码 PeerInfo
     */
    public fun encodePeerInfo(peerInfo: PeerInfo): ByteArray {
        val out = ByteArrayOutputStream()
        // Tag 1 (field_number = 1, wire_type = 0) -> 0x08
        out.write(0x08)
        writeVarint32(out, peerInfo.status)

        if (peerInfo.pubKey.isNotEmpty()) {
            // Tag 2 (field_number = 2, wire_type = 2) -> 0x12
            out.write(0x12)
            writeVarint32(out, peerInfo.pubKey.size)
            out.write(peerInfo.pubKey)
        }
        return out.toByteArray()
    }

    /**
     * 解码 PeerInfo
     */
    public fun decodePeerInfo(bytes: ByteArray): PeerInfo {
        if (bytes.isEmpty()) return PeerInfo()
        val input = ByteArrayInputStream(bytes)
        var status = PeerInfo.Status.UNKNOWN
        var pubKey = byteArrayOf()

        while (input.available() > 0) {
            val tag = readVarint32(input)
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == 0) status = readVarint32(input) else skipField(input, wireType)
                2 -> {
                    if (wireType == 2) {
                        pubKey = readExactBytes(input)
                    } else {
                        skipField(input, wireType)
                    }
                }
                else -> skipField(input, wireType)
            }
        }
        return PeerInfo(status, pubKey)
    }

    /**
     * 严格按指定的 Varint 长度从流中读取字节数组，若不足则立即抛出 EOFException
     */
    private fun readExactBytes(input: ByteArrayInputStream): ByteArray {
        val len = readVarint32(input)
        if (len < 0) throw IllegalArgumentException("非法负数 Protobuf 长度: $len")
        if (len == 0) return byteArrayOf()

        val buffer = ByteArray(len)
        var readTotal = 0
        while (readTotal < len) {
            val count = input.read(buffer, readTotal, len - readTotal)
            if (count == -1) {
                throw EOFException("Protobuf payload 数据截断：期望 $len 字节，实际仅读取 $readTotal 字节")
            }
            readTotal += count
        }
        return buffer
    }

    private fun readVarint32(input: ByteArrayInputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val b = input.read()
            if (b == -1) throw EOFException("Protobuf 字节流提前结束")
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Malformed varint32")
    }

    private fun writeVarint32(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.write(v)
                return
            } else {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }

    private fun skipField(input: ByteArrayInputStream, wireType: Int) {
        when (wireType) {
            0 -> readVarint32(input)
            1 -> skipExactBytes(input, 8)
            2 -> {
                val len = readVarint32(input)
                if (len > 0) skipExactBytes(input, len)
            }
            5 -> skipExactBytes(input, 4)
            else -> throw IllegalArgumentException("Unsupported wire type: $wireType")
        }
    }

    private fun skipExactBytes(input: ByteArrayInputStream, count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                // 如果 skip 返回 0，尝试 read 1 个字节验证是否已到 EOF
                if (input.read() == -1) throw EOFException("Protobuf 流在 skip 期间提前结束")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}

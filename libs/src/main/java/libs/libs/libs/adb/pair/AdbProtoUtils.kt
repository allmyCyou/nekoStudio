package libs.libs.libs.adb.pair

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

public object AdbProtoUtils {

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

    private fun readVarint32(input: ByteArrayInputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val b = input.read()
            if (b == -1) throw IllegalStateException("Unexpected EOF reading varint")
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Malformed varint32")
    }

    /**
     * 序列化 PairingPacket (Field 1: type [varint], Field 2: payload [bytes])
     */
    public fun encodePairingPacket(packet: PairingPacket): ByteArray {
        val out = ByteArrayOutputStream()
        // Field 1: type (tag = (1 << 3) | 0 = 0x08)
        out.write(0x08)
        writeVarint32(out, packet.type)

        // Field 2: payload (tag = (2 << 3) | 2 = 0x12)
        if (packet.payload.isNotEmpty()) {
            out.write(0x12)
            writeVarint32(out, packet.payload.size)
            out.write(packet.payload)
        }
        return out.toByteArray()
    }

    /**
     * 反序列化 PairingPacket
     */
    public fun decodePairingPacket(bytes: ByteArray): PairingPacket {
        val input = ByteArrayInputStream(bytes)
        var type = PairingPacket.Type.UNKNOWN
        var payload = byteArrayOf()

        while (input.available() > 0) {
            val tag = readVarint32(input)
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == 0) type = readVarint32(input) else skipField(input, wireType)
                2 -> {
                    if (wireType == 2) {
                        val len = readVarint32(input)
                        payload = ByteArray(len)
                        input.read(payload)
                    } else skipField(input, wireType)
                }
                else -> skipField(input, wireType)
            }
        }
        return PairingPacket(type, payload)
    }

    /**
     * 序列化 PeerInfo (Field 1: status [varint], Field 2: pubKey [bytes])
     */
    public fun encodePeerInfo(peerInfo: PeerInfo): ByteArray {
        val out = ByteArrayOutputStream()
        // Field 1: status (tag = 0x08)
        out.write(0x08)
        writeVarint32(out, peerInfo.status)

        // Field 2: pubKey (tag = 0x12)
        if (peerInfo.pubKey.isNotEmpty()) {
            out.write(0x12)
            writeVarint32(out, peerInfo.pubKey.size)
            out.write(peerInfo.pubKey)
        }
        return out.toByteArray()
    }

    /**
     * 反序列化 PeerInfo
     */
    public fun decodePeerInfo(bytes: ByteArray): PeerInfo {
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
                        val len = readVarint32(input)
                        pubKey = ByteArray(len)
                        input.read(pubKey)
                    } else skipField(input, wireType)
                }
                else -> skipField(input, wireType)
            }
        }
        return PeerInfo(status, pubKey)
    }

    private fun skipField(input: ByteArrayInputStream, wireType: Int) {
        when (wireType) {
            0 -> readVarint32(input)
            1 -> input.skip(8)
            2 -> {
                val len = readVarint32(input)
                input.skip(len.toLong())
            }
            5 -> input.skip(4)
            else -> throw IllegalArgumentException("Unsupported wire type: $wireType")
        }
    }
}

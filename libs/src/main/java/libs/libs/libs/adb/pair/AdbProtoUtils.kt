package libs.libs.libs.adb.pair

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

public object AdbProtoUtils {

    private fun readVarint32(input: ByteArrayInputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val b = input.read()
            if (b == -1) throw EOFException("流已结束，服务端已断开连接")
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Malformed varint32")
    }

    public fun encodePairingPacket(packet: PairingPacket): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x08) // Tag 1 (varint)
        writeVarint32(out, packet.type)

        if (packet.payload.isNotEmpty()) {
            out.write(0x12) // Tag 2 (length-delimited)
            writeVarint32(out, packet.payload.size)
            out.write(packet.payload)
        }
        return out.toByteArray()
    }

    public fun decodePairingPacket(bytes: ByteArray): PairingPacket {
        if (bytes.isEmpty()) return PairingPacket()
        val input = ByteArrayInputStream(bytes)
        var type = PairingPacket.Type.SPAKE2_MSG 
        var payload = byteArrayOf()

        try {
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
                            if (len > 0) {
                                val readBytes = input.read(payload)
                                if (readBytes < len) {
                                    if (readBytes == -1) throw EOFException("Protobuf payload 读取提前结束")
                                    payload = payload.copyOf(readBytes)
                                }
                            }
                        } else skipField(input, wireType)
                    }
                    else -> skipField(input, wireType)
                }
            }
        } catch (_: EOFException) {
        }
        return PairingPacket(type, payload)
    }

    public fun encodePeerInfo(peerInfo: PeerInfo): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x08)
        writeVarint32(out, peerInfo.status)

        if (peerInfo.pubKey.isNotEmpty()) {
            out.write(0x12)
            writeVarint32(out, peerInfo.pubKey.size)
            out.write(peerInfo.pubKey)
        }
        return out.toByteArray()
    }

    public fun decodePeerInfo(bytes: ByteArray): PeerInfo {
        if (bytes.isEmpty()) return PeerInfo()
        val input = ByteArrayInputStream(bytes)
        var status = PeerInfo.Status.UNKNOWN
        var pubKey = byteArrayOf()

        try {
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
                            if (len > 0) {
                                val readBytes = input.read(pubKey)
                                if (readBytes < len) {
                                    if (readBytes == -1) throw EOFException("Protobuf pubKey 读取提前结束")
                                    pubKey = pubKey.copyOf(readBytes)
                                }
                            }
                        } else skipField(input, wireType)
                    }
                    else -> skipField(input, wireType)
                }
            }
        } catch (_: EOFException) {
        }
        return PeerInfo(status, pubKey)
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

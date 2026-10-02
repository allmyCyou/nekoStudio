package libs.libs.libs.adb.pair

import cafe.cryptography.curve25519.CompressedEdwardsY
import cafe.cryptography.curve25519.Constants
import cafe.cryptography.curve25519.EdwardsPoint
import cafe.cryptography.curve25519.InvalidEncodingException
import cafe.cryptography.curve25519.Scalar
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private operator fun EdwardsPoint.minus(other: EdwardsPoint): EdwardsPoint = this.subtract(other)
private operator fun EdwardsPoint.plus(other: EdwardsPoint): EdwardsPoint = this.add(other)
private operator fun EdwardsPoint.times(scalar: Scalar): EdwardsPoint = this.multiply(scalar)

public class AdbSpake2Engine(
    private val passwordBytes: ByteArray
) : AutoCloseable {

    private val random = SecureRandom()
    // AOSP 规范：客户端与服务端标识末尾必须带 \0
    private val myNameBytes = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
    private val theirNameBytes = "adb pair server\u0000".toByteArray(Charsets.UTF_8)

    private var scalarX: Scalar? = null
    private var scalarW: Scalar? = null
    private var hardenedWBytes: ByteArray? = null

    private val myMsg = ByteArray(32)
    private var derivedSessionKey: ByteArray? = null

    // AES-GCM IV 计数器 (64位小端序)
    private var encIv: Long = 0L
    private var decIv: Long = 0L

    companion object {
        private val M_POINT_ENCODED = hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        private val N_POINT_ENCODED = hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")
        private val L_BIG_INTEGER = BigInteger("1000000000000000000000000000000014def9de2f79cd65812631a5cf5d3ed1", 16)

        // HKDF Info 标签
        private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)

        private val LIB_M: EdwardsPoint = CompressedEdwardsY(M_POINT_ENCODED).decompress()
        private val LIB_N: EdwardsPoint = CompressedEdwardsY(N_POINT_ENCODED).decompress()

        private fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val out = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                val hi = Character.digit(hex[i], 16)
                val lo = Character.digit(hex[i + 1], 16)
                out[i / 2] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }

    public fun generateClientHello(): ByteArray {
        val rawPrivateKey = ByteArray(64)
        random.nextBytes(rawPrivateKey)

        try {
            val sx = Scalar.fromBytesModOrderWide(rawPrivateKey)
            this.scalarX = sx

            val pHash = getSha512(passwordBytes)
            val sw = Scalar.fromBytesModOrderWide(pHash)
            this.scalarW = sw

            val rawWBytes = sw.toByteArray()
            val wHardened = hardenPassword(rawWBytes)
            this.hardenedWBytes = wHardened

            val pointXG = Constants.ED25519_BASEPOINT_TABLE.multiply(sx)
            val pointWM = LIB_M * sw
            val pointX = pointXG + pointWM

            val encodedX = pointX.compress().toByteArray()
            System.arraycopy(encodedX, 0, this.myMsg, 0, 32)
            return myMsg.clone()
        } finally {
            Arrays.fill(rawPrivateKey, 0.toByte())
        }
    }

    public fun processServerHelloAndDeriveKey(serverHello: ByteArray) {
        require(serverHello.size == 32) { "Server Hello 长度必须为 32 字节" }

        val sx = scalarX ?: throw IllegalStateException("Scalar X 未初始化")
        val sw = scalarW ?: throw IllegalStateException("Scalar W 未初始化")
        val wBytes = hardenedWBytes ?: throw IllegalStateException("Hardened W 未初始化")

        val peerMsg = serverHello.clone()
        val pointY = CompressedEdwardsY(peerMsg).decompress()

        val pointWN = LIB_N * sw
        val pointQ = pointY - pointWN
        val pointK = pointQ * sx
        val dhShared = pointK.compress().toByteArray()

        try {
            val md = MessageDigest.getInstance("SHA-512")
            updateWithLengthPrefix(md, myNameBytes)
            updateWithLengthPrefix(md, theirNameBytes)
            updateWithLengthPrefix(md, myMsg)
            updateWithLengthPrefix(md, peerMsg)
            updateWithLengthPrefix(md, dhShared)
            updateWithLengthPrefix(md, wBytes)

            val masterKey64 = md.digest()

            // 使用 HKDF-SHA256 派生 AES-128 密钥
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(masterKey64, null, HKDF_INFO))
            val secretKey = ByteArray(16)
            hkdf.generateBytes(secretKey, 0, 16)

            this.derivedSessionKey = secretKey
        } finally {
            Arrays.fill(dhShared, 0.toByte())
            Arrays.fill(wBytes, 0.toByte())
        }
    }

    /**
     * 加密 Payload（使用自增 Long 计数器生成 12 字节小端序 IV）
     */
    public fun encryptPayload(plainData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("会话密钥未建立")
        val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(encIv++).array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plainData)
    }

    /**
     * 解密 Payload
     */
    public fun decryptPayload(encryptedData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("会话密钥未建立")
        val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(decIv++).array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(encryptedData)
    }

    override fun close() {
        derivedSessionKey?.let { Arrays.fill(it, 0.toByte()) }
        hardenedWBytes?.let { Arrays.fill(it, 0.toByte()) }
        Arrays.fill(myMsg, 0.toByte())
    }

    private fun hardenPassword(rawWLittleEndian: ByteArray): ByteArray {
        var w = leBytesToBigInteger(rawWLittleEndian)
        if (!w.testBit(0)) w = w.add(L_BIG_INTEGER)
        if (!w.testBit(1)) w = w.add(L_BIG_INTEGER.shiftLeft(1))
        if (!w.testBit(2)) w = w.add(L_BIG_INTEGER.shiftLeft(2))
        return bigIntegerToLeBytes(w)
    }

    private fun leBytesToBigInteger(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun bigIntegerToLeBytes(n: BigInteger): ByteArray {
        val be = n.toByteArray()
        val result = ByteArray(32)
        val src = if (be.size > 32 && be[0] == 0.toByte()) be.copyOfRange(1, be.size) else be
        val offset = 32 - src.size
        for (i in src.indices) {
            result[32 - 1 - (offset + i)] = src[i]
        }
        return result
    }

    private fun updateWithLengthPrefix(md: MessageDigest, data: ByteArray) {
        val len = data.size.toLong()
        val lenLe = ByteArray(8)
        for (i in 0 until 8) {
            lenLe[i] = ((len ushr (i * 8)) and 0xFF).toByte()
        }
        md.update(lenLe)
        md.update(data)
    }

    private fun getSha512(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(input)
}

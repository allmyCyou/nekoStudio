package libs.libs.libs.adb.pair

import cafe.cryptography.curve25519.CompressedEdwardsY
import cafe.cryptography.curve25519.Constants
import cafe.cryptography.curve25519.EdwardsPoint
import cafe.cryptography.curve25519.InvalidEncodingException
import cafe.cryptography.curve25519.Scalar
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 严格对齐 BoringSSL / AOSP pairing_auth.cpp 的 ADB SPAKE2 (Ed25519) 引擎
 */
class AdbSpake2Engine(
    private val pairingCode: String,
    private val myName: String = "adb pair client",
    private val theirName: String = "adb pair server"
) {

    private val random = SecureRandom()
    private val myNameBytes = myName.toByteArray(Charsets.UTF_8)
    private val theirNameBytes = theirName.toByteArray(Charsets.UTF_8)

    // 内部保留的 32 字节 Little-Endian 状态
    private var scalarX: Scalar? = null
    private var scalarW: Scalar? = null
    private var hardenedWBytes: ByteArray? = null

    private val myMsg = ByteArray(32)
    private var state = State.INIT
    private var derivedSessionKey: ByteArray? = null

    private enum class State { INIT, MSG_GENERATED, KEY_GENERATED }

    companion object {
        // BoringSSL SPAKE2 Ed25519 标准生成元 M 和 N 的压缩坐标 (Hex)
        private val M_POINT_ENCODED = hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        private val N_POINT_ENCODED = hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")

        // Ed25519 群阶 L = 2^252 + 27742317777372353535851937790883648493
        private val L_BIG_INTEGER = BigInteger("1000000000000000000000000000000014def9de2f79cd65812631a5cf5d3ed1", 16)

        private val LIB_M: EdwardsPoint
        private val LIB_N: EdwardsPoint

        init {
            try {
                LIB_M = CompressedEdwardsY(M_POINT_ENCODED).decompress()
                LIB_N = CompressedEdwardsY(N_POINT_ENCODED).decompress()
            } catch (e: Exception) {
                throw ExceptionInInitializerError("AdbSpake2Engine 初始化失败: ${e.message}")
            }
        }

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

    /**
     * 第一阶段：生成 Client Hello 点 X (32 字节)
     * 公式: X = x * G + w * M
     */
    fun generateClientHello(): ByteArray {
        check(state == State.INIT) { "Client Hello 已经生成过。" }
 
        val passwordBytes = pairingCode.toByteArray(Charsets.UTF_8)
        val rawPrivateKey = ByteArray(64)
        random.nextBytes(rawPrivateKey)

        try {
            // 1. 生成随机私钥标量 x (64 字节 mod L 约简)
            val sx = Scalar.fromBytesModOrderWide(rawPrivateKey)
            this.scalarX = sx

            // 2. 口令 SHA-512 哈希 (64 字节) 并约简，计算加法混淆口令标量 w
            val pHash = getSha512(passwordBytes)
            val sw = Scalar.fromBytesModOrderWide(pHash)
            this.scalarW = sw

            // 提取 32 字节 Little-Endian 口令标量，并进行 BoringSSL harden (+L, +2L, +4L) 混淆
            val rawWBytes = sw.toByteArray()
            val wHardened = hardenPassword(rawWBytes)
            this.hardenedWBytes = wHardened

            // 3. 计算 X = x * G + w * M
            val pointXG = Constants.ED25519_BASEPOINT.mul(sx)
            val pointWM = LIB_M.mul(sw)
            val pointX = pointXG.add(pointWM)

            val encodedX = pointX.compress().toByteArray()
            System.arraycopy(encodedX, 0, this.myMsg, 0, 32)

            this.state = State.MSG_GENERATED
            return myMsg.clone()
        } finally {
            Arrays.fill(rawPrivateKey, 0.toByte())
        }
    }

    /**
     * 第二阶段：处理 Server Hello 点 Y (32 字节) 并导出会话密钥
     * 公式: K = x * (Y - w * N)
     */
    fun processServerHelloAndDeriveKey(serverHello: ByteArray) {
        check(state == State.MSG_GENERATED) { "必须先生成 Client Hello。" }
        require(serverHello.size == 32) { "Server Hello 长度必须为 32 字节。" }

        val sx = scalarX ?: throw IllegalStateException("Scalar X 未初始化")
        val sw = scalarW ?: throw IllegalStateException("Scalar W 未初始化")
        val wBytes = hardenedWBytes ?: throw IllegalStateException("Hardened W 未初始化")

        val peerMsg = serverHello.clone()
        val pointY = try {
            CompressedEdwardsY(peerMsg).decompress()
        } catch (e: InvalidEncodingException) {
            throw IllegalArgumentException("Server point Y 不是有效的 Ed25519 曲线点。", e)
        }

        // 计算 mask: w * N
        val pointWN = LIB_N.mul(sw)
        val pointQ = pointY.subtract(pointWN)

        // 计算共享秘密点 K = x * (Y - w * N)
        val pointK = pointQ.mul(sx)
        val dhShared = pointK.compress().toByteArray()

        try {
            val md = MessageDigest.getInstance("SHA-512")

            // Alice 顺序：myName, theirName, myMsg, peerMsg, dhShared, wBytes (32 字节)
            updateWithLengthPrefix(md, myNameBytes)
            updateWithLengthPrefix(md, theirNameBytes)
            updateWithLengthPrefix(md, myMsg)
            updateWithLengthPrefix(md, peerMsg)
            updateWithLengthPrefix(md, dhShared)
            updateWithLengthPrefix(md, wBytes)

            val masterKey64 = md.digest()

            // 取 SHA-512 结果的前 16 字节作为 AES-128-GCM 会话密钥
            val aesKey = masterKey64.copyOfRange(0, 16)
            this.derivedSessionKey = aesKey

            this.state = State.KEY_GENERATED
        } finally {
            Arrays.fill(dhShared, 0.toByte())
            Arrays.fill(wBytes, 0.toByte())
        }
    }

    fun encryptPayload(plainData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("会话密钥尚未建立")

        val iv = ByteArray(12)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val cipherText = cipher.doFinal(plainData)
        return iv + cipherText
    }

    fun decryptPayload(encryptedData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("会话密钥尚未建立")
        require(encryptedData.size > 12) { "加密 Payload 长度非法" }

        val iv = encryptedData.copyOfRange(0, 12)
        val cipherText = encryptedData.copyOfRange(12, encryptedData.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(cipherText)
    }

    /**
     * BoringSSL 规范中的口令混淆：当 w 的 low bit 为 0 时累加 L, 2L, 4L
     */
    private fun hardenPassword(rawWLittleEndian: ByteArray): ByteArray {
        var w = leBytesToBigInteger(rawWLittleEndian)

        if (!w.testBit(0)) {
            w = w.add(L_BIG_INTEGER)
        }
        if (!w.testBit(1)) {
            w = w.add(L_BIG_INTEGER.shiftLeft(1))
        }
        if (!w.testBit(2)) {
            w = w.add(L_BIG_INTEGER.shiftLeft(2))
        }

        return bigIntegerToLeBytes(w)
    }

    private fun leBytesToBigInteger(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed)
    }

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

    private fun getSha512(input: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        return md.digest(input)
    }
}

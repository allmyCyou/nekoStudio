package libs.libs.libs.adb.pair

import cafe.cryptography.curve25519.CompressedEdwardsY
import cafe.cryptography.curve25519.Constants
import cafe.cryptography.curve25519.EdwardsPoint
import cafe.cryptography.curve25519.InvalidEncodingException
import cafe.cryptography.curve25519.Scalar
import cafe.cryptography.subtle.ConstantTime
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 完全对齐 BoringSSL / AOSP pairing_auth.cpp 的 ADB SPAKE2 引擎
 * 适用于 Android 11+ 无线调试配对 (Pairing Protocol)
 */
class AdbSpake2Engine(
    private val pairingCode: String,
    private val myName: String = "adb pair client",
    private val theirName: String = "adb pair server"
) {

    private val random = SecureRandom()
    private val myNameBytes = myName.toByteArray(Charsets.UTF_8)
    private val theirNameBytes = theirName.toByteArray(Charsets.UTF_8)

    // 内部状态
    private val privateKey = ByteArray(32)
    private val myMsg = ByteArray(32)
    private val passwordScalar = ByteArray(32)

    private var state = State.INIT
    private var derivedSessionKey: ByteArray? = null

    private enum class State { INIT, MSG_GENERATED, KEY_GENERATED }

    companion object {
        private val M_POINT_ENCODED = hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
        private val N_POINT_ENCODED = hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")
        private val GROUP_ORDER     = hexToBytes("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010")

        private val LIB_M: EdwardsPoint
        private val LIB_N: EdwardsPoint

        init {
            try {
                LIB_M = CompressedEdwardsY(M_POINT_ENCODED).decompress()
                LIB_N = CompressedEdwardsY(N_POINT_ENCODED).decompress()
            } catch (e: Exception) {
                throw ExceptionInInitializerError("AdbSpake2Engine static init failed: ${e.message}")
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
     * 公式: X = 8x * G + 8w * M
     */
    fun generateClientHello(): ByteArray {
        check(state == State.INIT) { "Client Hello has already been generated." }

        val passwordBytes = pairingCode.toByteArray(Charsets.UTF_8)
        val rawPrivateKey = ByteArray(64)
        random.nextBytes(rawPrivateKey)

        try {
            // 1. 规整私钥并乘以 8 (leftShift3)
            val reducedPrivate = Scalar.fromBytesModOrderWide(rawPrivateKey).toByteArray()
            leftShift3(reducedPrivate)
            System.arraycopy(reducedPrivate, 0, this.privateKey, 0, 32)

            // 计算 x * G
            val nativeP = multiplyByRawScalar(Constants.ED25519_BASEPOINT, this.privateKey)

            // 2. 处理口令 Password Hash、加法混淆，并同样乘以 8 (Cofactor 8)
            val pHash = getSha512(passwordBytes)
            val reducedPassword = Scalar.fromBytesModOrderWide(pHash).toByteArray()
            val hardenedPassword = hardenPasswordScalar(reducedPassword)
            
            // 修正：口令标量加法混淆后需要做乘以 8 的位移
            leftShift3(hardenedPassword)
            System.arraycopy(hardenedPassword, 0, this.passwordScalar, 0, 32)

            // 3. 计算 w * M
            val nativeMask = multiplyByRawScalar(LIB_M, this.passwordScalar)
            val nativePStar = nativeP.add(nativeMask)

            val encoded = nativePStar.compress().toByteArray()
            System.arraycopy(encoded, 0, this.myMsg, 0, 32)

            this.state = State.MSG_GENERATED
            return myMsg.clone()
        } finally {
            Arrays.fill(rawPrivateKey, 0.toByte())
        }
    }

    /**
     * 第二阶段：处理 Server Hello 点 Y (32 字节) 并导出会话密钥
     * 公式: K = 8x * (Y - 8w * N)
     */
    fun processServerHelloAndDeriveKey(serverHello: ByteArray) {
        check(state == State.MSG_GENERATED) { "Client Hello must be generated first." }
        require(serverHello.size == 32) { "Server Hello point must be exactly 32 bytes." }

        val peerMsg = serverHello.clone()
        val nativeQStar = try {
            CompressedEdwardsY(peerMsg).decompress()
        } catch (e: InvalidEncodingException) {
            throw IllegalArgumentException("Server point Y is not on the Ed25519 curve.", e)
        }

        // 计算 peer's mask: 8w * N
        val nativePeersMask = multiplyByRawScalar(LIB_N, this.passwordScalar)
        val nativeQExt = nativeQStar.subtract(nativePeersMask)

        // 共享点 dhShared = 8x * (Y - 8w * N)
        val dhShared = multiplyByRawScalar(nativeQExt, this.privateKey).compress().toByteArray()

        try {
            val md = MessageDigest.getInstance("SHA-512")

            // Alice 顺序：myName, theirName, myMsg, peerMsg, dhShared, passwordScalar
            updateWithLengthPrefix(md, myNameBytes, myNameBytes.size)
            updateWithLengthPrefix(md, theirNameBytes, theirNameBytes.size)
            updateWithLengthPrefix(md, myMsg, myMsg.size)
            updateWithLengthPrefix(md, peerMsg, peerMsg.size)
            updateWithLengthPrefix(md, dhShared, dhShared.size)
            
            // 修正：写入 32 字节口令标量 (passwordScalar)，而非原始 64 字节哈希
            updateWithLengthPrefix(md, passwordScalar, passwordScalar.size)

            val masterKey64 = md.digest()

            val aesKey = ByteArray(16)
            System.arraycopy(masterKey64, 0, aesKey, 0, 16)
            this.derivedSessionKey = aesKey

            this.state = State.KEY_GENERATED
        } finally {
            Arrays.fill(dhShared, 0.toByte())
            Arrays.fill(passwordScalar, 0.toByte())
            Arrays.fill(privateKey, 0.toByte())
        }
    }

    fun encryptPayload(plainData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("Session key not established")

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
        val key = derivedSessionKey ?: throw IllegalStateException("Session key not established")
        require(encryptedData.size > 12) { "Invalid encrypted payload length" }

        val iv = encryptedData.copyOfRange(0, 12)
        val cipherText = encryptedData.copyOfRange(12, encryptedData.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(cipherText)
    }

    private fun leftShift3(n: ByteArray) {
        var carry = 0
        for (i in 0 until 32) {
            val nextCarry = (n[i].toInt() and 0xFF) ushr 5
            n[i] = ((n[i].toInt() shl 3) or carry).toByte()
            carry = nextCarry
        }
    }

    /**
     * 还原 BoringSSL 口令标量混淆加法 (+L, +2L, +4L)
     */
    private fun hardenPasswordScalar(reducedPasswordScalar: ByteArray): ByteArray {
        val passwordScalar = MutableScalar(reducedPasswordScalar)
        val order = MutableScalar(GROUP_ORDER)
        val tmp = MutableScalar()

        val firstByte = reducedPasswordScalar[0].toInt() and 0xFF
        val bit0 = firstByte and 1
        val bit1 = (firstByte ushr 1) and 1
        val bit2 = (firstByte ushr 2) and 1

        try {
            // 修正：BoringSSL 逻辑为当 bit 为 0 时添加 order
            tmp.reset()
            tmp.conditionalCopyFrom(order, tmp, ConstantTime.equal(bit0, 0))
            passwordScalar.addInPlace(tmp)
            order.dblInPlace()

            tmp.reset()
            tmp.conditionalCopyFrom(order, tmp, ConstantTime.equal(bit1, 0))
            passwordScalar.addInPlace(tmp)
            order.dblInPlace()

            tmp.reset()
            tmp.conditionalCopyFrom(order, tmp, ConstantTime.equal(bit2, 0))
            passwordScalar.addInPlace(tmp)

            return passwordScalar.getBytes().clone()
        } finally {
            passwordScalar.reset()
            order.reset()
            tmp.reset()
        }
    }

    private fun multiplyByRawScalar(point: EdwardsPoint, scalar: ByteArray): EdwardsPoint {
        require(scalar.size == 32) { "Scalar must be 32 bytes." }

        val table = Array(16) { EdwardsPoint.IDENTITY }
        for (i in 1 until 16) {
            table[i] = table[i - 1].add(point)
        }

        var result = EdwardsPoint.IDENTITY
        for (byteIndex in 31 downTo 0) {
            val value = scalar[byteIndex].toInt() and 0xFF
            result = multiplyBy16(result)
            result = result.add(selectPoint(table, (value ushr 4) and 0x0F))
            result = multiplyBy16(result)
            result = result.add(selectPoint(table, value and 0x0F))
        }
        return result
    }

    private fun multiplyBy16(point: EdwardsPoint): EdwardsPoint {
        var res = point
        for (i in 0 until 4) res = res.dbl()
        return res
    }

    private fun selectPoint(table: Array<EdwardsPoint>, digit: Int): EdwardsPoint {
        var selected = table[0]
        for (i in 1 until table.size) {
            selected = selected.ctSelect(table[i], ConstantTime.equal(digit, i))
        }
        return selected
    }

    private fun updateWithLengthPrefix(md: MessageDigest, data: ByteArray, len: Int) {
        val lenLe = ByteArray(8)
        var v = len.toLong() and 0xFFFFFFFFL
        for (i in 0 until 8) {
            lenLe[i] = (v and 0xFFL).toByte()
            v = v ushr 8
        }
        md.update(lenLe)
        md.update(data, 0, len)
    }

    private fun getSha512(input: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        return md.digest(input)
    }

    private class MutableScalar(initBytes: ByteArray? = null) {
        private val bytes = ByteArray(32)

        init {
            initBytes?.let { System.arraycopy(it, 0, bytes, 0, 32) }
        }

        fun getBytes() = bytes

        fun reset() = Arrays.fill(bytes, 0.toByte())

        fun dblInPlace(): MutableScalar {
            var carry = 0
            for (i in 0 until 32) {
                val carryOut = (bytes[i].toInt() and 0xFF) ushr 7
                bytes[i] = ((bytes[i].toInt() shl 1) or carry).toByte()
                carry = carryOut
            }
            return this
        }

        fun addInPlace(src: MutableScalar): MutableScalar {
            var carry = 0
            for (i in 0 until 32) {
                val tmp = (src.bytes[i].toInt() and 0xFF) + (this.bytes[i].toInt() and 0xFF) + carry
                this.bytes[i] = tmp.toByte()
                carry = tmp ushr 8
            }
            return this
        }

        fun conditionalCopyFrom(whenTrue: MutableScalar, whenFalse: MutableScalar, mask: Int) {
            val m = -mask
            for (i in 0 until 32) {
                val a = whenTrue.bytes[i].toInt() and 0xFF
                val b = whenFalse.bytes[i].toInt() and 0xFF
                this.bytes[i] = ((m and a) or (m.inv() and b)).toByte()
            }
        }
    }
}

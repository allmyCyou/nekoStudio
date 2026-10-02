package libs.libs.libs.adb.pair

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

public class AdbSpake2Engine(
    private val passwordBytes: ByteArray
) : AutoCloseable {

    private val random = SecureRandom()

    private val myNameBytes = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
    private val theirNameBytes = "adb pair server\u0000".toByteArray(Charsets.UTF_8)

    private val scalarX = ByteArray(32)
    private val scalarW = ByteArray(32)

    private val myMsg = ByteArray(32)
    private var derivedSessionKey: ByteArray? = null

    // AES-GCM IV 计数器 (64位 Little-Endian)
    private var encIv: Long = 0L
    private var decIv: Long = 0L

    public fun generateClientHello(): ByteArray {
        // 1. 生成 64 字节随机数并对 Group Order L 取模做 sc_reduce
        val randomBytes = ByteArray(64)
        random.nextBytes(randomBytes)
        scReduce(randomBytes, scalarX)
        Arrays.fill(randomBytes, 0.toByte())

        // 2. 对 passwordBytes 做 SHA-512 并对 64 字节结果做 sc_reduce
        val md = MessageDigest.getInstance("SHA-512")
        val pHash = md.digest(passwordBytes)
        scReduce(pHash, scalarW)
        Arrays.fill(pHash, 0.toByte())

        // 3. 计算 Client Hello: T = x*G + w*M
        val xG = scalarMultBase(scalarX)
        val wM = scalarMult(scalarW, M_POINT_BYTES)
        
        val pointT = pointAdd(xG, wM)
        System.arraycopy(pointT, 0, myMsg, 0, 32)

        return myMsg.clone()
    }

    public fun processServerHelloAndDeriveKey(serverHello: ByteArray) {
        require(serverHello.size == 32) { "Server Hello 长度必须为 32 字节" }

        val peerMsg = serverHello.clone()

        // 1. 计算 S = Y - w*N = Y + (-w)*N
        val wN = scalarMult(scalarW, N_POINT_BYTES)
        val minusWN = pointNegate(wN)
        val pointS = pointAdd(peerMsg, minusWN)

        // 2. 计算共享密钥 K = x * S
        val pointK = scalarMult(scalarX, pointS)

        // 3. 计算 AOSP SPAKE2 Master Key
        val md = MessageDigest.getInstance("SHA-512")
        updateWithLengthPrefix(md, myNameBytes)
        updateWithLengthPrefix(md, theirNameBytes)
        updateWithLengthPrefix(md, myMsg)
        updateWithLengthPrefix(md, peerMsg)
        updateWithLengthPrefix(md, pointK)
        updateWithLengthPrefix(md, scalarW)

        val masterKey = md.digest()

        // 4. 使用 HKDF-SHA256 衍生 16 字节 AES-128-GCM 密钥
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(masterKey, null, HKDF_INFO))
        val secretKey = ByteArray(16)
        hkdf.generateBytes(secretKey, 0, 16)

        this.derivedSessionKey = secretKey

        // 清理敏感数据
        Arrays.fill(masterKey, 0.toByte())
        Arrays.fill(pointK, 0.toByte())
    }

    public fun encryptPayload(plainData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("会话密钥未建立")
        val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(encIv++).array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plainData)
    }

    public fun decryptPayload(encryptedData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("会话密钥未建立")
        val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(decIv++).array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(encryptedData)
    }

    override fun close() {
        derivedSessionKey?.let { Arrays.fill(it, 0.toByte()) }
        Arrays.fill(scalarX, 0.toByte())
        Arrays.fill(scalarW, 0.toByte())
        Arrays.fill(myMsg, 0.toByte())
    }

    // --- 算法辅助计算（基于纯 BigInteger 域运算，摆脱 BouncyCastle 依赖） ---

    private fun scReduce(input: ByteArray, out32: ByteArray) {
        val bigInt = BigInteger(1, input.reversedArray())
        val reduced = bigInt.mod(ED25519_L)
        val leBytes = reduced.toByteArray().reversedArray()

        Arrays.fill(out32, 0.toByte())
        val copyLen = minOf(leBytes.size, 32)
        System.arraycopy(leBytes, 0, out32, 0, copyLen)
    }

    private fun scalarMultBase(scalar: ByteArray): ByteArray {
        return scalarMult(scalar, BASE_POINT_BYTES)
    }

    private fun scalarMult(scalar: ByteArray, pointBytes: ByteArray): ByteArray {
        // 使用绝对类型安全的 Curve25519 标量乘法实现
        val k = BigInteger(1, scalar.reversedArray())
        val p = parseCurvePoint(pointBytes)
        val result = p.multiply(k)
        return encodeCurvePoint(result)
    }

    private fun pointAdd(pointABytes: ByteArray, pointBBytes: ByteArray): ByteArray {
        val pA = parseCurvePoint(pointABytes)
        val pB = parseCurvePoint(pointBBytes)
        val pSum = pA.add(pB)
        return encodeCurvePoint(pSum)
    }

    private fun pointNegate(pointBytes: ByteArray): ByteArray {
        val res = pointBytes.clone()
        res[31] = (res[31].toInt() xor 0x80).toByte()
        return res
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

    // --- Ed25519 曲线坐标解析与编码 ---

    private fun parseCurvePoint(bytes: ByteArray): SimpleECPoint {
        val y = BigInteger(1, bytes.reversedArray()).clearBit(255)
        val x = recoverX(y)
        return SimpleECPoint(x, y)
    }

    private fun encodeCurvePoint(point: SimpleECPoint): ByteArray {
        val yBytes = point.y.toByteArray().reversedArray()
        val result = ByteArray(32)
        val copyLen = minOf(yBytes.size, 32)
        System.arraycopy(yBytes, 0, result, 0, copyLen)
        if (point.x.testBit(0)) {
            result[31] = (result[31].toInt() or 0x80).toByte()
        }
        return result
    }

    private fun recoverX(y: BigInteger): BigInteger {
        // x^2 = (y^2 - 1) / (d * y^2 + 1) mod P
        val y2 = y.multiply(y).mod(P)
        val num = y2.subtract(BigInteger.ONE).mod(P)
        val den = D.multiply(y2).add(BigInteger.ONE).mod(P)
        var x = num.multiply(den.modInverse(P)).modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)

        if (x.multiply(x).subtract(num.multiply(den.modInverse(P))).mod(P) != BigInteger.ZERO) {
            x = x.multiply(I).mod(P)
        }
        return x
    }

    private inner class SimpleECPoint(val x: BigInteger, val y: BigInteger) {
        fun add(other: SimpleECPoint): SimpleECPoint {
            // Twisted Edwards 曲线点加法公式
            val x1x2 = x.multiply(other.x).mod(P)
            val y1y2 = y.multiply(other.y).mod(P)
            val dx1x2y1y2 = D.multiply(x1x2).multiply(y1y2).mod(P)

            val x3 = (x.multiply(other.y).add(y.multiply(other.x))).multiply(BigInteger.ONE.add(dx1x2y1y2).modInverse(P)).mod(P)
            val y3 = (y1y2.add(x1x2)).multiply(BigInteger.ONE.subtract(dx1x2y1y2).modInverse(P)).mod(P)

            return SimpleECPoint(x3, y3)
        }

        fun multiply(scalar: BigInteger): SimpleECPoint {
            var res = SimpleECPoint(BigInteger.ZERO, BigInteger.ONE)
            var base = this
            var k = scalar
            while (k > BigInteger.ZERO) {
                if (k.testBit(0)) {
                    res = res.add(base)
                }
                base = base.add(base)
                k = k.shiftRight(1)
            }
            return res
        }
    }

    companion object {
        private val P = BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)
        private val ED25519_L = BigInteger("1000000000000000000000000000000014def9de2f79cd65812631a5cf5d3ed1", 16)
        private val D = BigInteger("-121665", 10).multiply(BigInteger("121666", 10).modInverse(P)).mod(P)
        private val I = BigInteger("2", 10).modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

        private val BASE_POINT_BYTES = hexToBytes("5866666666666666666666666666666666666666666666666666666666666666")
        private val M_POINT_BYTES = hexToBytes("d75a980182b10ab7d54377c1139e3a706c4d24fe0c1d0b348e8ad8780b22c8d4")
        private val N_POINT_BYTES = hexToBytes("015708e23d49d748e129620a7195adb078a13f575d59a7622cd0146505c54383")

        private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)

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
}

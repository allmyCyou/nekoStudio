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

    private val myNameBytes = "adb pair client".toByteArray(Charsets.UTF_8)
    private val theirNameBytes = "adb pair server".toByteArray(Charsets.UTF_8)

    private val scalarX = ByteArray(32)
    private val scalarW = ByteArray(32)

    private val myMsg = ByteArray(32)
    private var derivedSessionKey: ByteArray? = null

    private var encIv: Long = 0L
    private var decIv: Long = 0L

    public fun generateClientHello(): ByteArray {
        // 1. 生成 64 字节随机数做 sc_reduce
        val randomBytes = ByteArray(64)
        random.nextBytes(randomBytes)
        scReduce(randomBytes, scalarX)
        Arrays.fill(randomBytes, 0.toByte())

        // 2. 对 password 做 SHA-512 并做 sc_reduce
        val md = MessageDigest.getInstance("SHA-512")
        val pHash = md.digest(passwordBytes)
        scReduce(pHash, scalarW)
        Arrays.fill(pHash, 0.toByte())

        // 3. 计算 T = x*G + w*M
        val xG = scalarMultBase(scalarX)
        val wM = scalarMult(scalarW, M_POINT_BYTES)

        val pointT = pointAdd(xG, wM)
        System.arraycopy(pointT, 0, myMsg, 0, 32)

        return myMsg.clone()
    }

    public fun processServerHelloAndDeriveKey(serverHello: ByteArray) {
        require(serverHello.size == 32) { "Server Hello 长度必须为 32 字节" }

        val peerMsg = serverHello.clone()

        // 1. S = Y - w*N = Y + (-w)*N
        val wN = scalarMult(scalarW, N_POINT_BYTES)
        val minusWN = pointNegate(wN)
        val pointS = pointAdd(peerMsg, minusWN)

        // 2. 共享点 K = x * S
        val pointK = scalarMult(scalarX, pointS)

        // 3. 计算 Master Key
        val md = MessageDigest.getInstance("SHA-512")
        updateWithLengthPrefix(md, myNameBytes)
        updateWithLengthPrefix(md, theirNameBytes)
        updateWithLengthPrefix(md, myMsg)
        updateWithLengthPrefix(md, peerMsg)
        updateWithLengthPrefix(md, pointK)
        updateWithLengthPrefix(md, scalarW)

        val masterKey = md.digest()

        // 4. HKDF-SHA256 衍生 key
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(masterKey, null, HKDF_INFO))
        val secretKey = ByteArray(16)
        hkdf.generateBytes(secretKey, 0, 16)

        this.derivedSessionKey = secretKey

        Arrays.fill(masterKey, 0.toByte())
        Arrays.fill(pointK, 0.toByte())
    }

    private fun createIv(counter: Long): ByteArray {
        val iv = ByteArray(12)
        ByteBuffer.wrap(iv, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter)
        return iv
    }

    public fun encryptPayload(plainData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("会话密钥未建立")
        val iv = createIv(encIv++)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plainData)
    }

    public fun decryptPayload(encryptedData: ByteArray): ByteArray {
        val key = derivedSessionKey ?: throw IllegalStateException("会话密钥未建立")
        val iv = createIv(decIv++)

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

    // --- 底层 32 字节小端对齐与爱德华曲线点运算 ---

    private fun toLittleEndian32(bigInt: BigInteger, modulus: BigInteger): ByteArray {
        val v = bigInt.mod(modulus)
        val result = ByteArray(32)
        val raw = v.toByteArray()

        var rawIdx = raw.size - 1
        var outIdx = 0
        while (rawIdx >= 0 && outIdx < 32) {
            result[outIdx++] = raw[rawIdx--]
        }
        return result
    }

    private fun scReduce(input64: ByteArray, out32: ByteArray) {
        val bigInt = BigInteger(1, input64.reversedArray())
        val leBytes = toLittleEndian32(bigInt, ED25519_L)
        Arrays.fill(out32, 0.toByte())
        System.arraycopy(leBytes, 0, out32, 0, 32)
    }

    private fun scalarMultBase(scalar: ByteArray): ByteArray {
        return scalarMult(scalar, BASE_POINT_BYTES)
    }

    private fun scalarMult(scalar: ByteArray, pointBytes: ByteArray): ByteArray {
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
        val p = parseCurvePoint(pointBytes)
        val negP = SimpleECPoint.fromAffine(P.subtract(p.toAffine().first).mod(P), p.toAffine().second)
        return encodeCurvePoint(negP)
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

    private fun parseCurvePoint(bytes: ByteArray): SimpleECPoint {
        val copy = bytes.clone()
        val xBit = (copy[31].toInt() and 0x80) != 0
        copy[31] = (copy[31].toInt() and 0x7F).toByte()

        val y = BigInteger(1, copy.reversedArray()).mod(P)
        val x = recoverX(y, xBit)
        return SimpleECPoint.fromAffine(x, y)
    }

    private fun encodeCurvePoint(point: SimpleECPoint): ByteArray {
        val (x, y) = point.toAffine()
        val result = toLittleEndian32(y, P)
        if (x.testBit(0)) {
            result[31] = (result[31].toInt() or 0x80).toByte()
        }
        return result
    }

    private fun recoverX(y: BigInteger, xBit: Boolean): BigInteger {
        val y2 = y.multiply(y).mod(P)
        val num = y2.subtract(BigInteger.ONE).mod(P)
        val den = D.multiply(y2).add(BigInteger.ONE).mod(P)
        var x = num.multiply(den.modInverse(P)).modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)

        val check = x.multiply(x).subtract(num.multiply(den.modInverse(P))).mod(P)
        if (check != BigInteger.ZERO) {
            x = x.multiply(I).mod(P)
        }
        if (x.testBit(0) != xBit) {
            x = P.subtract(x).mod(P)
        }
        return x
    }

    /**
     * 扩展爱德华坐标系 (X:Y:Z:T)，满足 x = X/Z, y = Y/Z, x*y = T/Z
     * 消除点加过程中的 modInverse 逆元计算，提升性能 100 倍以上
     */
    private class SimpleECPoint(
        val X: BigInteger,
        val Y: BigInteger,
        val Z: BigInteger,
        val T: BigInteger
    ) {
        fun toAffine(): Pair<BigInteger, BigInteger> {
            val zInv = Z.modInverse(P)
            val x = X.multiply(zInv).mod(P)
            val y = Y.multiply(zInv).mod(P)
            return Pair(x, y)
        }

        fun add(other: SimpleECPoint): SimpleECPoint {
            val A = Y.subtract(X).multiply(other.Y.subtract(other.X)).mod(P)
            val B = Y.add(X).multiply(other.Y.add(other.X)).mod(P)
            val C = D2.multiply(T).multiply(other.T).mod(P)
            val DVal = Z.shiftLeft(1).multiply(other.Z).mod(P)

            val E = B.subtract(A).mod(P)
            val F = DVal.subtract(C).mod(P)
            val G = DVal.add(C).mod(P)
            val H = B.add(A).mod(P)

            val X3 = E.multiply(F).mod(P)
            val Y3 = G.multiply(H).mod(P)
            val T3 = E.multiply(H).mod(P)
            val Z3 = F.multiply(G).mod(P)

            return SimpleECPoint(X3, Y3, Z3, T3)
        }

        fun multiply(scalar: BigInteger): SimpleECPoint {
            var res = IDENTITY
            var base = this
            var k = scalar.mod(ED25519_L)
            while (k > BigInteger.ZERO) {
                if (k.testBit(0)) {
                    res = res.add(base)
                }
                base = base.add(base)
                k = k.shiftRight(1)
            }
            return res
        }

        companion object {
            val IDENTITY = SimpleECPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

            fun fromAffine(x: BigInteger, y: BigInteger): SimpleECPoint {
                val X = x.mod(P)
                val Y = y.mod(P)
                val Z = BigInteger.ONE
                val T = X.multiply(Y).mod(P)
                return SimpleECPoint(X, Y, Z, T)
            }
        }
    }

    companion object {
        private val P = BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)
        private val ED25519_L = BigInteger("1000000000000000000000000000000014def9de2f79cd65812631a5cf5d3ed1", 16)
        private val D = BigInteger("-121665", 10).multiply(BigInteger("121666", 10).modInverse(P)).mod(P)
        private val D2 = D.shiftLeft(1).mod(P)
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

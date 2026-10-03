package libs.libs.libs.adb.key

import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.util.encoders.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

public object AdbKeyUtils {

    private const val RSANUMWORDS = 64 // 2048 位 / 32 位 = 64

    /**
     * 将 BouncyCastle 的 RSA 公钥导出为标准 `adbkey.pub` 格式：
     * "<Base64(524B ADB RSAPublicKey)> <Comment>"
     */
    public fun convertToAdbPublicKeyString(
        pubKeyParams: RSAKeyParameters,
        comment: String = "\u0000adb@key"
    ): String {
        val n = pubKeyParams.modulus
        val e = pubKeyParams.exponent

        // 1. 计算 n0inv = -1 / N[0] mod 2^32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0invInt = n.mod(r32).modInverse(r32).negate().mod(r32).toInt()

        // 2. 计算 rr = (R^2) mod N (R = 2^2048)
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(n)

        // 3. 打包 524 字节结构体 (小端序 Little-Endian)
        val buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(RSANUMWORDS)
        buffer.putInt(n0invInt)
        buffer.put(toLittleEndianByteArray(n, 256))
        buffer.put(toLittleEndianByteArray(rr, 256))
        buffer.putInt(e.toInt())

        // 4. Base64 编码并拼接 comment (使用 BC Base64 避免 Android API 版本兼容问题)
        val base64Key = Base64.toBase64String(buffer.array())
        return if (comment.isBlank()) base64Key else "$base64Key$comment"
    }

    /**
     * 将 `adbkey.pub` 格式字符串解析为 RSAKeyParameters 和 Comment
     */
    public fun parseAdbPublicKeyString(pubKeyString: String): Pair<RSAKeyParameters, String> {
        val parts = pubKeyString.trim().split(Regex("\\s+"), limit = 2)
        val base64Part = parts[0]
        val comment = if (parts.size > 1) parts[1] else ""

        val bytes = Base64.decode(base64Part)
        require(bytes.size == 524) { "Invalid ADB public key structure length: ${bytes.size} (expected 524)" }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val len = buffer.int
        require(len == RSANUMWORDS) { "Invalid RSANUMWORDS: $len (expected$RSANUMWORDS)" }

        @Suppress("UNUSED_VARIABLE")
        val n0inv = buffer.int

        val nBytesLE = ByteArray(256)
        buffer.get(nBytesLE)
        val modulus = BigInteger(1, nBytesLE.reversedArray())

        val rrBytesLE = ByteArray(256)
        buffer.get(rrBytesLE)

        val expInt = buffer.int
        val exponent = BigInteger.valueOf(expInt.toLong() and 0xFFFFFFFFL)

        return Pair(RSAKeyParameters(false, modulus, exponent), comment)
    }

    public fun extractPublicKeyParameters(privateKey: AsymmetricKeyParameter): RSAKeyParameters {
        return when (privateKey) {
            is RSAPrivateCrtKeyParameters -> RSAKeyParameters(false, privateKey.modulus, privateKey.publicExponent)
            is RSAKeyParameters -> RSAKeyParameters(false, privateKey.modulus, privateKey.exponent)
            else -> throw IllegalArgumentException("Unsupported private key class: ${privateKey::class.java.name}")
        }
    }

    private fun toLittleEndianByteArray(bigInt: BigInteger, length: Int): ByteArray {
        val rawBytes = bigInt.toByteArray()
        val result = ByteArray(length)

        var srcPos = 0
        var copyLen = rawBytes.size

        if (copyLen > 0 && rawBytes[0] == 0.toByte()) {
            srcPos = 1
            copyLen--
        }

        val bytesToCopy = minOf(copyLen, length)
        for (i in 0 until bytesToCopy) {
            result[i] = rawBytes[srcPos + copyLen - 1 - i]
        }
        return result
    }
}

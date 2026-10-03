package libs.libs.libs.adb.pair

import libs.libs.libs.crypto.spake2.Spake2Context
import libs.libs.libs.crypto.spake2.Spake2Role
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

public class AdbSpake2Engine(
    private val passwordBytes: ByteArray
) : AutoCloseable {

    private val spake2Context = Spake2Context(
        Spake2Role.Alice,
        CLIENT_NAME,
        SERVER_NAME
    )

    private var derivedSessionKey: ByteArray? = null

    private var encIv: Long = 0L
    private var decIv: Long = 0L

    /**
     * 调用 Native BoringSSL SPAKE2 生成 32 字节 Client Hello 报文
     */
    public fun generateClientHello(): ByteArray {
        return spake2Context.generateMessage(passwordBytes)
    }

    /**
     * 处理 Server Hello 报文并导出 AES-128 会话密钥
     */
    public fun processServerHelloAndDeriveKey(serverHello: ByteArray) {
        require(serverHello.size == 32) { "Server Hello 长度必须为 32 字节" }

        // 1. 调用 Native SPAKE2 计算 64 字节 Key Material
        val keyMaterial = spake2Context.processMessage(serverHello)

        try {
            // 2. 通过 HKDF-SHA256 衍生出 16 字节 AES-128 会话密钥
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(keyMaterial, null, null))
            val secretKey = ByteArray(16)
            hkdf.generateBytes(secretKey, 0, 16)

            this.derivedSessionKey = secretKey
        } finally {
            Arrays.fill(keyMaterial, 0.toByte())
        }
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

    private fun createIv(counter: Long): ByteArray {
        val iv = ByteArray(12)
        // ADB 规定 IV 前 8 字节为 Little-Endian 递增计数器
        ByteBuffer.wrap(iv, 0, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter)
        return iv
    }

    override fun close() {
        derivedSessionKey?.let { Arrays.fill(it, 0.toByte()) }
        spake2Context.destroy()
    }

    companion object {
        private val CLIENT_NAME = "adb pair client".toByteArray(Charsets.UTF_8)
        private val SERVER_NAME = "adb pair server".toByteArray(Charsets.UTF_8)
    }
}

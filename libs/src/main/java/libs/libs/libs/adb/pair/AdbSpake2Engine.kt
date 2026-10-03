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

    private val derivedSessionKey = ByteArray(16)
    private var isCipherInitialized = false

    private var encIv: Long = 0L
    private var decIv: Long = 0L

    public fun generateClientHello(): ByteArray {
        return spake2Context.generateMessage(passwordBytes)
    }

    public fun processServerHelloAndDeriveKey(serverHello: ByteArray) {
        val keyMaterial = spake2Context.processMessage(serverHello)
            ?: throw IllegalStateException("SPAKE2 processMessage 失败，返回 null")

        try {
            // HKDF-SHA256 派生 AES-128 密钥 (Info: "adb pairing_auth aes-128-gcm key")
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(keyMaterial, null, HKDF_INFO))
            hkdf.generateBytes(derivedSessionKey, 0, derivedSessionKey.size)
            isCipherInitialized = true
        } finally {
            Arrays.fill(keyMaterial, 0.toByte())
        }
    }

    public fun encryptPayload(plainData: ByteArray): ByteArray {
        check(isCipherInitialized) { "会话密钥未建立" }
        val iv = createIv(encIv++)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedSessionKey, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plainData)
    }

    public fun decryptPayload(encryptedData: ByteArray): ByteArray {
        check(isCipherInitialized) { "会话密钥未建立" }
        val iv = createIv(decIv++)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedSessionKey, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(encryptedData)
    }

    private fun createIv(counter: Long): ByteArray {
        val iv = ByteArray(12)
        // GCM IV: 低 8 字节为 Little-Endian 编码的 uint64 计数器，高 4 字节为 0
        ByteBuffer.wrap(iv, 0, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter)
        return iv
    }

    override fun close() {
        Arrays.fill(derivedSessionKey, 0.toByte())
        spake2Context.destroy()
    }

    companion object {
        // C-Style 16 字节标识符，末尾带 \u0000
        private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
        private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8)

        // HKDF 派生密钥 Info 标识符
        private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)
    }
}

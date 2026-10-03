package libs.libs.libs.adb.key

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.signers.RSADigestSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.SecureRandom
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec

public class AdbKeyManager(
    public var privateKeyFile: File? = null,
    public var publicKeyFile: File? = null
) {

    @Volatile
    private var privateKey: AsymmetricKeyParameter? = null

    @Volatile
    private var publicKeyString: String? = null

    public val isLoaded: Boolean
        get() = privateKey != null && publicKeyString != null

    /**
     * 指定密钥存放目录并自动加载/初始化密钥。
     */
    @Synchronized
    public fun initFromDirectory(keyDir: File, comment: String = "nekoStudio@adbd"): AdbKeyPair {
        keyDir.mkdirs()
        val privFile = File(keyDir, "adbkey")
        val pubFile = File(keyDir, "adbkey.pub")
        return loadOrGenerateKeys(privFile, pubFile, comment)
    }

    /**
     * 核心加载逻辑：优先从磁盘读，读失败或文件损坏时才生成新密钥并存盘
     */
    @Synchronized
    public fun loadOrGenerateKeys(
        privFile: File,
        pubFile: File,
        comment: String = "nekoStudio@adbd"
    ): AdbKeyPair {
        this.privateKeyFile = privFile
        this.publicKeyFile = pubFile

        if (privFile.exists() && privFile.length() > 0) {
            try {
                val privPem = privFile.readText()
                val pubStr = if (pubFile.exists() && pubFile.length() > 0) pubFile.readText() else null
                
                loadKeys(privPem, pubStr)

                // 自动补齐丢失的公钥文件
                if (!pubFile.exists() || pubFile.length() == 0L) {
                    pubFile.parentFile?.mkdirs()
                    pubFile.writeText(getAdbPublicKeyString())
                }

                val privKey = this.privateKey!!
                val pubParams = AdbKeyUtils.extractPublicKeyParameters(privKey)
                return AdbKeyPair(privKey, pubParams, getAdbPublicKeyString())
            } catch (_: Exception) {
                // 文件损坏时，重新生成并覆盖坏文件
                return generateKeyPair(comment)
            }
        } else {
            return generateKeyPair(comment)
        }
    }

    /**
     * 检查并确保密钥加载。如果未加载，优先根据配置的路径加载，无路径或不存在才生成。
     */
    @Synchronized
    public fun ensureLoaded(comment: String = "nekoStudio@adbd") {
        if (isLoaded) return

        val privFile = privateKeyFile
        val pubFile = publicKeyFile ?: privFile?.let { File(it.parentFile, "${it.name}.pub") }

        if (privFile != null && pubFile != null) {
            loadOrGenerateKeys(privFile, pubFile, comment)
        } else {
            generateKeyPair(comment)
        }
    }

    /**
     * 设置文件路径并自动加载；带文件损坏保护机制
     */
    @Synchronized
    public fun setupFilesAndLoad(privFile: File, pubFile: File) {
        this.privateKeyFile = privFile
        this.publicKeyFile = pubFile

        if (privFile.exists() && privFile.length() > 0) {
            try {
                val privPem = privFile.readText()
                val pubStr = if (pubFile.exists() && pubFile.length() > 0) pubFile.readText() else null
                loadKeys(privPem, pubStr)

                if (!pubFile.exists() || pubFile.length() == 0L) {
                    pubFile.parentFile?.mkdirs()
                    pubFile.writeText(getAdbPublicKeyString())
                }
            } catch (_: Exception) {
                generateKeyPair()
            }
        }
    }

    /**
     * 生成全新 2048 位 RSA 密钥对。
     */
    @Synchronized
    public fun generateKeyPair(comment: String = "nekoStudio@adbd"): AdbKeyPair {
        val generator = RSAKeyPairGenerator()
        generator.init(
            RSAKeyGenerationParameters(
                BigInteger.valueOf(65537),
                SecureRandom(),
                2048,
                80
            )
        )

        val pair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val privKey = pair.private
        val pubKeyParams = pair.public as RSAKeyParameters

        val pubKeyStr = AdbKeyUtils.convertToAdbPublicKeyString(pubKeyParams, comment)

        this.privateKey = privKey
        this.publicKeyString = pubKeyStr

        val keyPair = AdbKeyPair(privKey, pubKeyParams, pubKeyStr)

        privateKeyFile?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(keyPair.toPem())
        }
        publicKeyFile?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(pubKeyStr)
        }

        return keyPair
    }

    @Synchronized
    public fun loadKeys(adbKeyPem: String, adbKeyPub: String? = null) {
        val privKey = AdbKeySerializer.privateKeyFromPem(adbKeyPem)
        val pubStr = if (!adbKeyPub.isNullOrBlank()) {
            adbKeyPub.trim()
        } else {
            val pubParams = AdbKeyUtils.extractPublicKeyParameters(privKey)
            AdbKeyUtils.convertToAdbPublicKeyString(pubParams)
        }
        this.privateKey = privKey
        this.publicKeyString = pubStr
    }

    public fun signToken(token: ByteArray): ByteArray {
        val privKey = privateKey ?: throw IllegalStateException("PrivateKey is not loaded")
        val signer = RSADigestSigner(SHA1Digest())
        signer.init(true, privKey)
        signer.update(token, 0, token.size)
        return signer.generateSignature()
    }

    public fun getAdbPublicKeyString(): String {
        return publicKeyString ?: throw IllegalStateException("PublicKey is not loaded")
    }

    public fun getAdbPublicKeyBytes(): ByteArray {
        val keyStr = getAdbPublicKeyString()
        return "$keyStr\u0000".toByteArray(Charsets.UTF_8)
    }

    public fun getKeyPair(): KeyPair {
        val privParams = (privateKey as? RSAPrivateCrtKeyParameters)
            ?: throw IllegalStateException("PrivateKey is not loaded or not a valid RSAPrivateCrtKeyParameters")

        val keyFactory = KeyFactory.getInstance("RSA")

        val privSpec = RSAPrivateCrtKeySpec(
            privParams.modulus,
            privParams.publicExponent,
            privParams.exponent,
            privParams.p,
            privParams.q,
            privParams.dp,
            privParams.dq,
            privParams.qInv
        )
        val pubSpec = RSAPublicKeySpec(
            privParams.modulus,
            privParams.publicExponent
        )

        val javaPrivateKey = keyFactory.generatePrivate(privSpec)
        val javaPublicKey = keyFactory.generatePublic(pubSpec)

        return KeyPair(javaPublicKey, javaPrivateKey)
    }
}

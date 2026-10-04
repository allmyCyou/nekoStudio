package libs.libs.libs.adb.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

public object AdbTlsCertificate {

    // 容错 Security Provider 列表 (兼容 JVM / Android OpenSSL 环境)
    private val SECURITY_PROVIDERS: List<Any?> = listOf(
        null,                   // 优先使用 JVM 默认 Provider
        BouncyCastleProvider(), // 显式 BouncyCastle
        "AndroidOpenSSL"        // Android 平台原生 Provider
    )

    /**
     * 根据 KeyPair 动态生成自签名 X.509 证书（符合 AOSP ADB TLS 客户端凭证规范）
     *
     * @param keyPair 密钥对 (支持 RSA / EC)
     * @param commonName 证书 CN (默认 "adb")
     * @param organization 证书 O (默认 "Android")
     * @param validityDays 有效天数 (默认 10 年)
     */
    public fun generateSelfSignedCertificate(
        keyPair: KeyPair,
        commonName: String = "adb",
        organization: String = "Android",
        validityDays: Int = 3650
    ): X509Certificate {
        val now = System.currentTimeMillis()
        // 提前 1 天生效，防止 Android 设备端与主机时钟微小偏差导致证书生效前报错
        val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(validityDays.toLong()))

        // 随机生成 64 位正整数序列号
        val serialNumber = BigInteger(64, SecureRandom()).abs()
        val subjectName = X500Name("CN=$commonName, O=$organization")

        val builder = JcaX509v3CertificateBuilder(
            subjectName,
            serialNumber,
            notBefore,
            notAfter,
            subjectName,
            keyPair.public
        )

        // 1. 基本约束扩展 (Basic Constraints)：标记为 CA 证书
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))

        // 2. 密钥用途扩展 (Key Usage)：许可数字签名、证书签名与 CRL 签名
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature)
        )

        // 3. 主体密钥标识符 (Subject Key Identifier / SKI)
        runCatching {
            val extensionUtils = JcaX509ExtensionUtils()
            builder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extensionUtils.createSubjectKeyIdentifier(keyPair.public)
            )
        }

        // 根据密钥类型自动推导签名算法
        val sigAlg = when (keyPair.private.algorithm.uppercase(Locale.US)) {
            "EC" -> "SHA256withECDSA"
            "RSA" -> "SHA256withRSA"
            else -> "SHA256withRSA"
        }

        val signer = JcaContentSignerBuilder(sigAlg).build(keyPair.private)
        val holder = builder.build(signer)

        // 4. 多 Provider 降级策略：解决 Android / JVM 环境下的 Security Provider 不兼容问题
        for (provider in SECURITY_PROVIDERS) {
            try {
                val converter = JcaX509CertificateConverter()
                when (provider) {
                    is Provider -> converter.setProvider(provider)
                    is String -> converter.setProvider(provider)
                }
                return converter.getCertificate(holder)
            } catch (_: Throwable) {
                // 尝试下一个 Provider
            }
        }

        throw CertificateException("All security providers failed to build X509 certificate.")
    }

    /**
     * 计算 ADB TLS 认证所需的公钥 SHA-256 大写 Hex 指纹
     */
    public fun getAdbTlsFingerprintHex(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return digest.joinToString("") { "%02X".format(it) }
    }
}

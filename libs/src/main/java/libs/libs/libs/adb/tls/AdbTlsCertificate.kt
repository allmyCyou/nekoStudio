package libs.libs.libs.adb.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

public object AdbTlsCertificate {

    private val bcProvider by lazy { BouncyCastleProvider() }

    /**
     * 完全对齐 AOSP (system/core/adb/crypto/x509_generator.cpp) 构建自签名 ADB TLS 客户端证书
     */
    public fun generateSelfSignedCertificate(
        keyPair: KeyPair,
        commonName: String = "adb",
        validityDays: Int = 3650
    ): X509Certificate {
        val now = System.currentTimeMillis()
        // 容忍 1 天的时钟偏差
        val startDate = Date(now - 24 * 60 * 60 * 1000L)
        // 默认 10 年有效期
        val endDate = Date(now + validityDays * 24 * 60 * 60 * 1000L)

        // AOSP 规范：Subject/Issuer 为 CN=adb, O=Android, C=US
        val dnName = X500Name("CN=$commonName, O=Android, C=US")
        val serialNumber = BigInteger(64, SecureRandom())

        val certBuilder = JcaX509v3CertificateBuilder(
            dnName,              // Issuer
            serialNumber,        // Serial
            startDate,           // Not Before
            endDate,             // Not After
            dnName,              // Subject
            keyPair.public       // Public Key
        )

        // 1. Basic Constraints: 声明为终端节点 (CA = false)
        certBuilder.addExtension(
            Extension.basicConstraints,
            true, // critical
            BasicConstraints(false)
        )

        // 2. Key Usage: 数字签名与密钥加密
        certBuilder.addExtension(
            Extension.keyUsage,
            true, // critical
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )

        // 3. Extended Key Usage: 关键！必须标注 Client Authentication (id_kp_clientAuth)
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth)
        )

        // 4. 使用 SHA256withRSA 进行自签名
        // 修复：直接将完整的 Provider 实例传给 Builder，规避字符串名称 "BC" 在 Android 系统中的冲突
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certBuilder.build(signer))
    }
}

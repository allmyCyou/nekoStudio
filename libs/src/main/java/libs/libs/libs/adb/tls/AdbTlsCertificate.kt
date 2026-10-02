package libs.libs.libs.adb.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

public object AdbTlsCertificate {

    /**
     * 根据 keyPair 动态生成自签名 X.509 证书（符合 AOSP ADB TLS 客户端凭证规范）
     */
    public fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val startDate = Date(now - 24L * 3600 * 1000) // 提前 1 天生效
        val endDate = Date(now + 10L * 365 * 24 * 3600 * 1000) // 10 年有效期

        // 随机生成 64 位正整数序列号
        val serialNumber = BigInteger(64, SecureRandom()).abs()
        val issuerName = X500Name("CN=adb, O=Android")

        val builder = JcaX509v3CertificateBuilder(
            issuerName,
            serialNumber,
            startDate,
            endDate,
            issuerName,
            keyPair.public
        )

        // 根据密钥算法动态选择签名算法
        val sigAlg = when (keyPair.private.algorithm.uppercase()) {
            "EC" -> "SHA256withECDSA"
            "RSA" -> "SHA256withRSA"
            else -> "SHA256withRSA"
        }

        val signer = JcaContentSignerBuilder(sigAlg).build(keyPair.private)
        val holder = builder.build(signer)

        return JcaX509CertificateConverter().getCertificate(holder)
    }
}

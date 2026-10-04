package libs.libs.libs.adb.tls

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
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
import java.security.cert.X509Certificate
import java.util.Date

public object AdbTlsCertificate {

    private val bcProvider by lazy { BouncyCastleProvider() }

    /**
     * 1:1 完全对齐 AOSP (system/core/adb/crypto/x509_generator.cpp) 构建自签名 ADB TLS 客户端证书
     */
    public fun generateSelfSignedCertificate(
        keyPair: KeyPair
    ): X509Certificate {
        val now = System.currentTimeMillis()
        // NotBefore: 当前时间往前推 1 天，防止客户端与设备间存在微小系统时钟偏差
        val startDate = Date(now - 86400000L)
        // NotAfter: 10 年 (AOSP kCertLifetimeSeconds = 10 * 365 * 24 * 60 * 60)
        val endDate = Date(now + 10L * 365 * 24 * 60 * 60 * 1000L)

        // 1. AOSP 规范：Subject/Issuer 字段及顺序 (C=US, O=Android, CN=Adb)
        val nameBuilder = X500NameBuilder(BCStyle.INSTANCE)
        nameBuilder.addRDN(BCStyle.C, "US")
        nameBuilder.addRDN(BCStyle.O, "Android")
        nameBuilder.addRDN(BCStyle.CN, "Adb") // 注意 CN 为 "Adb" (大写 A)
        val dnName = nameBuilder.build()

        // 2. AOSP 规范：Serial Number 固认为 1
        val serialNumber = BigInteger.ONE

        val certBuilder = JcaX509v3CertificateBuilder(
            dnName,              // Issuer
            serialNumber,        // Serial (1)
            startDate,           // Not Before
            endDate,             // Not After
            dnName,              // Subject
            keyPair.public       // Public Key
        )

        // 3. AOSP 规范：Basic Constraints -> critical, CA:TRUE
        certBuilder.addExtension(
            Extension.basicConstraints,
            true, // critical
            BasicConstraints(true) // CA = true
        )

        // 4. AOSP 规范：Key Usage -> critical, keyCertSign, cRLSign, digitalSignature
        certBuilder.addExtension(
            Extension.keyUsage,
            true, // critical
            KeyUsage(
                KeyUsage.keyCertSign or 
                KeyUsage.cRLSign or 
                KeyUsage.digitalSignature
            )
        )

        // 5. AOSP 规范：Subject Key Identifier -> hash
        val extensionUtils = JcaX509ExtensionUtils()
        certBuilder.addExtension(
            Extension.subjectKeyIdentifier,
            false, // non-critical
            extensionUtils.createSubjectKeyIdentifier(keyPair.public)
        )

        // 6. 签名生成证书 (使用 SHA256withRSA)
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certBuilder.build(signer))
    }
}

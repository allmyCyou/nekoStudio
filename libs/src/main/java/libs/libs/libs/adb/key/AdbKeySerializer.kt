package libs.libs.libs.adb.key

import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringReader
import java.io.StringWriter

public object AdbKeySerializer {

    public fun privateKeyToPem(privateKey: AsymmetricKeyParameter): String {
        val stringWriter = StringWriter()
        PemWriter(stringWriter).use { pemWriter ->
            val privateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(privateKey)
            pemWriter.writeObject(PemObject("PRIVATE KEY", privateKeyInfo.encoded))
        }
        return stringWriter.toString()
    }

    public fun privateKeyFromPem(pemString: String): AsymmetricKeyParameter {
        PemReader(StringReader(pemString)).use { pemReader ->
            val pemObject = pemReader.readPemObject()
                ?: throw IllegalArgumentException("Invalid PEM format: empty or unparseable content")

            return when (pemObject.type.trim().uppercase()) {
                "PRIVATE KEY" -> PrivateKeyFactory.createKey(pemObject.content)
                "RSA PRIVATE KEY" -> {
                    val rsa = RSAPrivateKey.getInstance(pemObject.content)
                    RSAPrivateCrtKeyParameters(
                        rsa.modulus,
                        rsa.publicExponent,
                        rsa.privateExponent,
                        rsa.prime1,
                        rsa.prime2,
                        rsa.exponent1,
                        rsa.exponent2,
                        rsa.coefficient
                    )
                }
                else -> throw IllegalArgumentException("Unsupported PEM type: ${pemObject.type}")
            }
        }
    }
}

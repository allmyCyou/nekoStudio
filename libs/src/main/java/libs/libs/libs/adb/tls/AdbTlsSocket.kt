package libs.libs.libs.adb.tls

import libs.libs.libs.adb.key.AdbKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

public class AdbTlsSocket(
    public val sslSocket: SSLSocket
) {
    public val inputStream: InputStream = sslSocket.inputStream
    public val outputStream: OutputStream = sslSocket.outputStream

    public fun close() {
        try {
            sslSocket.close()
        } catch (_: Exception) {}
    }

    public val isConnected: Boolean
        get() = sslSocket.isConnected && !sslSocket.isClosed && !sslSocket.isOutputShutdown

    public companion object {
        private const val KEY_PASSWORD = "adb_tls_password"
        private const val CLIENT_ALIAS = "adb_client_key"

        public suspend fun startTls(
            rawSocket: Socket,
            keyManager: AdbKeyManager,
            autoClose: Boolean = true,
            handshakeTimeoutMs: Int = 10000
        ): AdbTlsSocket = withContext(Dispatchers.IO) {
            val host = rawSocket.inetAddress?.hostAddress ?: "localhost"
            val port = rawSocket.port

            val sslContext = createSslContext(keyManager)

            val ssl = sslContext.socketFactory.createSocket(
                rawSocket, host, port, autoClose
            ) as SSLSocket

            ssl.enabledProtocols = arrayOf("TLSv1.3")

            ssl.soTimeout = handshakeTimeoutMs
            try {
                ssl.startHandshake()
            } finally {
                ssl.soTimeout = 0
            }

            AdbTlsSocket(ssl)
        }

        public suspend fun connect(
            keyManager: AdbKeyManager,
            host: String,
            port: Int,
            timeoutMs: Int = 10000
        ): AdbTlsSocket = withContext(Dispatchers.IO) {
            val rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            rawSocket.connect(InetSocketAddress(host, port), timeoutMs)

            startTls(rawSocket, keyManager, autoClose = true, handshakeTimeoutMs = timeoutMs)
        }

        private fun createSslContext(keyManager: AdbKeyManager): SSLContext {
            val keyPair = keyManager.getKeyPair()
            val cert = AdbTlsCertificate.generateSelfSignedCertificate(keyPair)

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(CLIENT_ALIAS, keyPair.private, KEY_PASSWORD.toCharArray(), arrayOf<X509Certificate>(cert))
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, KEY_PASSWORD.toCharArray())
            }

            val origKm = kmf.keyManagers.filterIsInstance<X509ExtendedKeyManager>().firstOrNull()
                ?: throw IllegalStateException("No X509ExtendedKeyManager found")

            val forceKm = ForceAliasKeyManager(origKm, CLIENT_ALIAS)

            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })

            return SSLContext.getInstance("TLSv1.3").apply {
                init(arrayOf(forceKm), trustAllCerts, SecureRandom())
            }
        }
    }

    private class ForceAliasKeyManager(
        private val delegate: X509ExtendedKeyManager,
        private val alias: String
    ) : X509ExtendedKeyManager() {

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = alias

        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = alias

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            delegate.getServerAliases(keyType, issuers)

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
            delegate.chooseServerAlias(keyType, issuers, socket)

        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            delegate.chooseEngineServerAlias(keyType, issuers, engine)

        override fun getCertificateChain(alias: String?): Array<out X509Certificate>? =
            delegate.getCertificateChain(alias ?: this.alias)

        override fun getPrivateKey(alias: String?): PrivateKey? =
            delegate.getPrivateKey(alias ?: this.alias)
    }
}

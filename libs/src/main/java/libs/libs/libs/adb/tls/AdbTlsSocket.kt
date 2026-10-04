package libs.libs.libs.adb.tls

import libs.libs.libs.adb.key.AdbKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
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

        /**
         * 将已建立连接的 Raw Socket 原位升级为 TLS SSLSocket 并完成 TLS 握手
         */
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

            // AOSP adbd 强制要求 TLS 1.3
            val supportedProtocols = ssl.supportedProtocols
            if ("TLSv1.3" in supportedProtocols) {
                ssl.enabledProtocols = arrayOf("TLSv1.3")
            }

            // 握手期间设置超时保护
            ssl.soTimeout = handshakeTimeoutMs

            try {
                ssl.startHandshake()
                // 握手成功后取消超时（0 代表无限等待，由业务层或 TCP KeepAlive 控制）
                ssl.soTimeout = 0
            } catch (e: SSLHandshakeException) {
                closeQuietly(ssl, rawSocket)
                throw AdbTlsException.HandshakeFailed(
                    "ADB TLS 握手失败：设备可能未完成无线调试配对，或拒绝了此证书凭证", e
                )
            } catch (e: SSLPeerUnverifiedException) {
                closeQuietly(ssl, rawSocket)
                throw AdbTlsException.PeerUnverified("设备端 TLS 身份无法验证", e)
            } catch (e: SSLException) {
                closeQuietly(ssl, rawSocket)
                throw AdbTlsException.ProtocolError("TLS 协议级别异常", e)
            } catch (e: SocketTimeoutException) {
                closeQuietly(ssl, rawSocket)
                throw AdbTlsException.HandshakeTimeout(
                    "TLS 握手超时：设备在 ${handshakeTimeoutMs}ms 内未响应", e
                )
            } catch (e: IOException) {
                closeQuietly(ssl, rawSocket)
                throw AdbTlsException.NetworkError("TLS 握手传输层异常: ${e.message}", e)
            }

            AdbTlsSocket(ssl)
        }

        /**
         * 直接建立加密 TLS Socket 连接
         */
        public suspend fun connect(
            keyManager: AdbKeyManager,
            host: String,
            port: Int,
            timeoutMs: Int = 10000
        ): AdbTlsSocket = withContext(Dispatchers.IO) {
            val rawSocket = Socket()
            try {
                rawSocket.tcpNoDelay = true
                rawSocket.keepAlive = true
                rawSocket.connect(InetSocketAddress(host, port), timeoutMs)

                startTls(rawSocket, keyManager, autoClose = true, handshakeTimeoutMs = timeoutMs)
            } catch (e: Throwable) {
                closeQuietly(rawSocket)
                throw if (e is AdbTlsException) e else AdbTlsException.NetworkError("连接 ADB 服务端失败: ${e.message}", e)
            }
        }

        private fun closeQuietly(vararg closeables: AutoCloseable?) {
            for (closeable in closeables) {
                try {
                    closeable?.close()
                } catch (_: Exception) {}
            }
        }

        /**
         * 构建用于 ADB TLS 认证的 SSLContext
         */
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

    /**
     * 强制指定 Client Key Alias，规避 JSSE 匹配逻辑导致私钥返回 null 的问题
     */
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

        // 无视 JSSE 传入的别名（如 "RSA"），强制返回我们的指定别名的证书链与私钥
        override fun getCertificateChain(alias: String?): Array<out X509Certificate>? =
            delegate.getCertificateChain(this.alias)

        override fun getPrivateKey(alias: String?): PrivateKey? =
            delegate.getPrivateKey(this.alias)
    }
}

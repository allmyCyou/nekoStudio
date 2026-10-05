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
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
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
        private const val CLIENT_ALIAS = "adb_client_key"

        public suspend fun startTls(
            rawSocket: Socket,
            keyManager: AdbKeyManager,
            autoClose: Boolean = true,
            handshakeTimeoutMs: Int = 10000
        ): AdbTlsSocket = withContext(Dispatchers.IO) {
            val port = rawSocket.port
            val sslContext = createSslContext(keyManager)

            // 关键修复：host 必须传入 null！
            // 防止 Conscrypt 将 IP 地址写进 SNI 扩展导致 adbd (BoringSSL) 拒绝连接并挂断。
            val ssl = sslContext.socketFactory.createSocket(
                rawSocket, null, port, autoClose
            ) as SSLSocket

            ssl.useClientMode = true

            val supportedProtocols = ssl.supportedProtocols
            if ("TLSv1.3" in supportedProtocols) {
                ssl.enabledProtocols = arrayOf("TLSv1.3")
            }

            ssl.soTimeout = handshakeTimeoutMs

            try {
                ssl.startHandshake()
                ssl.soTimeout = 0
            } catch (e: SSLHandshakeException) {
                closeQuietly(ssl, rawSocket)
                throw AdbTlsException.HandshakeFailed(
                    "TLS 握手失败（服务侧关闭连接或秘钥未配对）: ${e.message}", e
                )
            } catch (e: SSLPeerUnverifiedException) {
                closeQuietly(ssl, rawSocket)
                throw AdbTlsException.PeerUnverified("SSLPeerUnverifiedException: ${e.message}", e)
            } catch (e: SSLException) {
                closeQuietly(ssl, rawSocket)
                throw AdbTlsException.ProtocolError("SSLException: ${e.message}", e)
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

        private fun createSslContext(keyManager: AdbKeyManager): SSLContext {
            val keyPair = keyManager.getKeyPair()
            val cert = AdbTlsCertificate.generateSelfSignedCertificate(keyPair)

            val directKeyManager = object : X509ExtendedKeyManager() {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
                    return arrayOf(CLIENT_ALIAS)
                }

                override fun chooseClientAlias(
                    keyTypes: Array<out String>?,
                    issuers: Array<out Principal>?,
                    socket: Socket?
                ): String = CLIENT_ALIAS

                override fun chooseEngineClientAlias(
                    keyTypes: Array<out String>?,
                    issuers: Array<out Principal>?,
                    engine: SSLEngine?
                ): String = CLIENT_ALIAS

                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
                override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null

                override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
                override fun getPrivateKey(alias: String?): PrivateKey = keyPair.private
            }

            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })

            val sslContext = SSLContext.getInstance("TLSv1.3")
            sslContext.init(arrayOf(directKeyManager), trustAllCerts, SecureRandom())
            return sslContext
        }
    }
}

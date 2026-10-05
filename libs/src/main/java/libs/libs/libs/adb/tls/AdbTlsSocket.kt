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
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

public class AdbTlsSocket(
    public val sslSocket: SSLSocket
) {
    public val inputStream: InputStream = sslSocket.inputStream
    public val outputStream: OutputStream = sslSocket.outputStream

    public fun setSoTimeout(timeoutMs: Int) {
        runCatching { sslSocket.soTimeout = timeoutMs }
    }

    public fun close() {
        try {
            sslSocket.close()
        } catch (_: Exception) {}
    }

    public val isConnected: Boolean
        get() = sslSocket.isConnected && !sslSocket.isClosed && !sslSocket.isOutputShutdown

    public companion object {
        private const val CLIENT_ALIAS = "adb_client_key"

        /**
         * 将现有 Raw Socket 原地升级为 TLS Socket (支持 StartTLS)
         */
        public suspend fun startTls(
            rawSocket: Socket,
            keyManager: AdbKeyManager,
            autoClose: Boolean = true,
            handshakeTimeoutMs: Int = 10000,
            soTimeoutMs: Int = 0
        ): AdbTlsSocket = withContext(Dispatchers.IO) {
            val port = rawSocket.port
            val sslContext = createSslContext(keyManager)

            // 关键逻辑：host 传 null，防止 Conscrypt 填入 IP 地址导致 BoringSSL 拒绝 SNI 握手
            val ssl = sslContext.socketFactory.createSocket(
                rawSocket, null, port, autoClose
            ) as SSLSocket

            ssl.useClientMode = true

            // 协议协商：优先 TLSv1.3，保留 TLSv1.2 兼容性
            val supportedProtocols = ssl.supportedProtocols.toSet()
            val protocolsToEnable = mutableListOf<String>()
            if ("TLSv1.3" in supportedProtocols) protocolsToEnable.add("TLSv1.3")
            if ("TLSv1.2" in supportedProtocols) protocolsToEnable.add("TLSv1.2")
            if (protocolsToEnable.isNotEmpty()) {
                ssl.enabledProtocols = protocolsToEnable.toTypedArray()
            }

            ssl.soTimeout = handshakeTimeoutMs

            try {
                ssl.startHandshake()
                // 握手成功后恢复为指定的读写超时，避免无限卡死
                ssl.soTimeout = soTimeoutMs
            } catch (e: SocketTimeoutException) {
                closeQuietly(ssl, rawSocket)
                throw AdbTlsException.HandshakeTimeout("TLS 握手超时：设备在 ${handshakeTimeoutMs}ms 内未响应", e)
            } catch (e: Throwable) {
                closeQuietly(ssl, rawSocket)
                throw TlsErrorMapper.map(e)
            }

            AdbTlsSocket(ssl)
        }

        public suspend fun connect(
            keyManager: AdbKeyManager,
            host: String,
            port: Int,
            timeoutMs: Int = 10000,
            soTimeoutMs: Int = 0
        ): AdbTlsSocket = withContext(Dispatchers.IO) {
            val rawSocket = Socket()
            try {
                rawSocket.tcpNoDelay = true
                rawSocket.keepAlive = true
                rawSocket.connect(InetSocketAddress(host, port), timeoutMs)

                startTls(
                    rawSocket = rawSocket,
                    keyManager = keyManager,
                    autoClose = true,
                    handshakeTimeoutMs = timeoutMs,
                    soTimeoutMs = soTimeoutMs
                )
            } catch (e: Throwable) {
                closeQuietly(rawSocket)
                throw TlsErrorMapper.map(e)
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
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(CLIENT_ALIAS)
                override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = CLIENT_ALIAS
                override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = CLIENT_ALIAS
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

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(arrayOf(directKeyManager), trustAllCerts, SecureRandom())
            return sslContext
        }
    }
}

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
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
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
                    "SSLHandshakeException: ${e.message}", e
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
            val pubKeyFingerprint = getAdbTlsFingerprintHex(keyPair.public)

            // 直接构建内存级别的 KeyManager，不经过 PKCS12 / KeyManagerFactory 包装
            val directKeyManager = object : X509ExtendedKeyManager() {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
                    return if (keyType == null || keyType.contains("RSA", ignoreCase = true)) {
                        arrayOf(CLIENT_ALIAS)
                    } else emptyArray()
                }

                override fun chooseClientAlias(
                    keyTypes: Array<out String>?,
                    issuers: Array<out Principal>?,
                    socket: Socket?
                ): String = selectAlias(issuers)

                override fun chooseEngineClientAlias(
                    keyTypes: Array<out String>?,
                    issuers: Array<out Principal>?,
                    engine: SSLEngine?
                ): String = selectAlias(issuers)

                private fun selectAlias(issuers: Array<out Principal>?): String {
                    if (!issuers.isNullOrEmpty()) {
                        for (issuer in issuers) {
                            val targetFingerprintHex = parseAdbKeyFingerprintHexFromIssuer(issuer) ?: continue
                            if (pubKeyFingerprint.equals(targetFingerprintHex, ignoreCase = true)) {
                                return CLIENT_ALIAS
                            }
                        }
                    }
                    return CLIENT_ALIAS
                }

                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
                override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null

                override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)

                override fun getPrivateKey(alias: String?): PrivateKey = keyPair.private
            }

            // 信任设备端发来的任何服务端证书（adbd 使用自签名证书）
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })

            // 优先反射加载 Android 系统的 OpenSSL (Conscrypt) Provider
            val sslContext = try {
                val providerClass = Class.forName("org.conscrypt.OpenSSLProvider")
                val provider = providerClass.getDeclaredConstructor().newInstance() as Provider
                SSLContext.getInstance("TLSv1.3", provider)
            } catch (_: Throwable) {
                SSLContext.getInstance("TLSv1.3")
            }

            sslContext.init(arrayOf(directKeyManager), trustAllCerts, SecureRandom())
            return sslContext
        }

        /**
         * 计算公钥的 SHA-256 指纹 HEX 字符串
         */
        private fun getAdbTlsFingerprintHex(publicKey: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKey.encoded)
            return hash.joinToString("") { "%02X".format(it) }
        }

        /**
         * 从 adbd 的 CertificateRequest Issuer 中提取指纹
         * 格式通常为: "CN=<64-char-hex>, O=AdbKey-0"
         */
        private fun parseAdbKeyFingerprintHexFromIssuer(issuer: Principal): String? {
            val name = issuer.name ?: return null
            if (!name.contains("O=AdbKey-0")) return null
            val cnMatch = Regex("CN=([0-9a-fA-F]{64})").find(name)
            return cnMatch?.groupValues?.get(1)?.uppercase(Locale.US)
        }
    }
}

package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbPacket
import libs.libs.libs.adb.tls.AdbTlsCertificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.X509ExtendedKeyManager

public class AdbSocket {

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    public suspend fun connect(host: String, port: Int, timeoutMs: Int = 10000) = withContext(Dispatchers.IO) {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        
        // --- 保持长连接的关键配置 ---
        s.tcpNoDelay = true
        s.keepAlive = true // 开启 TCP 保活探针
        s.soTimeout = 0    // 无限超时，阻塞式 read 由协程的取消或 Socket 关闭来中断
        
        this@AdbSocket.socket = s
        this@AdbSocket.inputStream = s.getInputStream()
        this@AdbSocket.outputStream = s.getOutputStream()
    }

    public suspend fun startTls(keyManager: AdbKeyManager) = withContext(Dispatchers.IO) {
        val rawSocket = socket ?: throw IllegalStateException("Socket is not connected")
        val host = rawSocket.inetAddress?.hostAddress ?: "localhost"
        val port = rawSocket.port

        val keyPair = keyManager.getKeyPair()
        val cert = AdbTlsCertificate.generateSelfSignedCertificate(keyPair)

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(CLIENT_ALIAS, keyPair.private, KEY_PASSWORD.toCharArray(), arrayOf<X509Certificate>(cert))
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, KEY_PASSWORD.toCharArray())
        }

        val origKm = kmf.keyManagers.first { it is X509ExtendedKeyManager } as X509ExtendedKeyManager
        val forceKm = ForceAliasKeyManager(origKm, CLIENT_ALIAS)

        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val sslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(forceKm), trustAllCerts, SecureRandom())
        }

        val ssl = sslContext.socketFactory.createSocket(
            rawSocket, host, port, true
        ) as SSLSocket

        ssl.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        ssl.startHandshake()

        this@AdbSocket.socket = ssl
        this@AdbSocket.inputStream = ssl.inputStream
        this@AdbSocket.outputStream = ssl.outputStream
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
            delegate.getCertificateChain(alias)

        override fun getPrivateKey(alias: String?): PrivateKey? =
            delegate.getPrivateKey(alias)
    }

    private fun readExactly(buffer: ByteArray, length: Int) {
        val stream = inputStream ?: throw IllegalStateException("Socket is not connected")
        var bytesRead = 0
        while (bytesRead < length) {
            val count = stream.read(buffer, bytesRead, length - bytesRead)
            if (count == -1) {
                throw IllegalStateException("Socket stream closed unexpectedly")
            }
            bytesRead += count
        }
    }

    public suspend fun readPacket(): AdbPacket = withContext(Dispatchers.IO) {
        val headerBytes = ByteArray(AdbPacket.HEADER_SIZE)
        readExactly(headerBytes, AdbPacket.HEADER_SIZE)

        val header = AdbPacket.parseHeader(headerBytes)
        check(header.isValid) { "Invalid ADB packet header magic check failed" }

        val payload = if (header.dataLength > 0) {
            ByteArray(header.dataLength).also { readExactly(it, header.dataLength) }
        } else {
            ByteArray(0)
        }

        AdbPacket(
            command = header.command,
            arg0 = header.arg0,
            arg1 = header.arg1,
            payload = payload
        )
    }

    public suspend fun writePacket(packet: AdbPacket, skipChecksum: Boolean = false) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IllegalStateException("Socket is not connected")
        val bytes = packet.toByteArray(skipChecksum = skipChecksum)
        stream.write(bytes)
        stream.flush()
    }

    public fun close() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {
        } finally {
            inputStream = null
            outputStream = null
            socket = null
        }
    }

    public val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    companion object {
        private const val KEY_PASSWORD = "adb_tls_password"
        private const val CLIENT_ALIAS = "adb_client_key"
    }
}

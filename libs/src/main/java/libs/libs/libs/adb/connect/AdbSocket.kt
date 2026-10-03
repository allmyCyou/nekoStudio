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

    /**
     * 建立底层 TCP Socket 连接
     */
    public suspend fun connect(host: String, port: Int, timeoutMs: Int = 10000) = withContext(Dispatchers.IO) {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.tcpNoDelay = true // 禁用 Nagle 算法，减少短包交互延迟
        
        this@AdbSocket.socket = s
        this@AdbSocket.inputStream = s.getInputStream()
        this@AdbSocket.outputStream = s.getOutputStream()
    }

    /**
     * 将当前已连接的明文 Socket 原地升级为 TLS Socket
     */
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

        // 获取原生的 KeyManager 并用强制选择 Alias 的包装类进行封装
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

    /**
     * 强制返回指定 Alias 的 KeyManager，防止 JSSE 因 Issuer 匹配失败导致 chooseClientAlias 返回 null
     */
    private class ForceAliasKeyManager(
        private val delegate: X509ExtendedKeyManager,
        private val alias: String
    ) : X509ExtendedKeyManager() {

        override fun chooseClientAlias(keyType: Array<out String>, issuers: Array<out Principal>?, socket: Socket?): String = alias

        override fun chooseEngineClientAlias(keyType: Array<out String>, issuers: Array<out Principal>?, engine: SSLEngine?): String = alias

        override fun getClientAliases(keyType: String, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)

        override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? =
            delegate.getServerAliases(keyType, issuers)

        override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? =
            delegate.chooseServerAlias(keyType, issuers)

        override fun chooseEngineServerAlias(keyType: String, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            delegate.chooseEngineServerAlias(keyType, issuers)

        override fun getCertificateChain(alias: String): Array<out X509Certificate>? =
            delegate.getCertificateChain(alias)

        override fun getPrivateKey(alias: String): PrivateKey? =
            delegate.getPrivateKey(alias)
    }

    /**
     * 从 Socket 准确读取指定长度的 ByteArray
     */
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

    /**
     * 读取一个完整的 AdbPacket (24 字节 Header + Payload)
     */
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

    /**
     * 写入一个 AdbPacket 到 Socket
     */
    public suspend fun writePacket(packet: AdbPacket, skipChecksum: Boolean = false) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IllegalStateException("Socket is not connected")
        val bytes = packet.toByteArray(skipChecksum = skipChecksum)
        stream.write(bytes)
        stream.flush()
    }

    /**
     * 安全关闭底层连接
     */
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

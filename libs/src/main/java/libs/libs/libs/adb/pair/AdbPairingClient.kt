package libs.libs.libs.adb.pair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.tls.AdbTlsCertificate
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

public class AdbPairingClient(
    private val keyManager: AdbKeyManager
) : AdbPairing {

    override suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        listener: AdbPairingListener?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            listener?.onPairingStarted()

            require(pairingCode.length == 6 && pairingCode.all { it.isDigit() }) {
                "Pairing code must be a 6-digit number."
            }
            require(keyManager.isLoaded) {
                "AdbKeyManager must load or generate key pair before pairing."
            }

            Socket().use { rawSocket ->
                rawSocket.soTimeout = READ_TIMEOUT_MS
                rawSocket.tcpNoDelay = true
                rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                // 1. TLS 配置与连接
                val keyPair = keyManager.getKeyPair()
                val cert = AdbTlsCertificate.generateSelfSignedCertificate(keyPair)

                val keyStore = KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setKeyEntry("adb_pair_client", keyPair.private, KEY_PASSWORD.toCharArray(), arrayOf<X509Certificate>(cert))
                }

                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore, KEY_PASSWORD.toCharArray())
                }

                val sslContext = SSLContext.getInstance("TLSv1.3").apply {
                    init(kmf.keyManagers, arrayOf(AdbPairingTrustManager()), SecureRandom())
                }

                val sslSocket = sslContext.socketFactory.createSocket(
                    rawSocket, host, port, true
                ) as SSLSocket

                sslSocket.useClientMode = true
                sslSocket.enabledProtocols = arrayOf("TLSv1.3")

                sslSocket.use { tlsSocket ->
                    tlsSocket.startHandshake()

                    // 2. 导出 TLS 密钥材料并拼接配对码
                    val keyMaterial = exportKeyingMaterial(tlsSocket, EXPORTED_KEY_LABEL, 64)
                    val rawCodeBytes = pairingCode.toByteArray(Charsets.UTF_8)
                    val fullPassword = ByteArray(rawCodeBytes.size + keyMaterial.size)
                    System.arraycopy(rawCodeBytes, 0, fullPassword, 0, rawCodeBytes.size)
                    System.arraycopy(keyMaterial, 0, fullPassword, rawCodeBytes.size, keyMaterial.size)

                    val spake2Engine = AdbSpake2Engine(fullPassword)

                    val inputStream = DataInputStream(tlsSocket.inputStream)
                    val outputStream = DataOutputStream(tlsSocket.outputStream)

                    // 3. 发送 SPAKE2 Client Hello (Type = 0)
                    val clientHello = spake2Engine.generateClientHello()
                    sendPacket(outputStream, TYPE_SPAKE2_MSG, clientHello)

                    // 4. 接收 SPAKE2 Server Hello (Type = 0)
                    val (serverType, serverPayload) = receivePacket(inputStream)
                    require(serverType == TYPE_SPAKE2_MSG) { "Expected SPAKE2_MSG (0), got: $serverType" }
                    require(serverPayload.size == 32) { "Server Hello 长度不符: ${serverPayload.size}" }

                    spake2Engine.processServerHelloAndDeriveKey(serverPayload)

                    // 5. 加密并发送 PeerInfo (Type = 1)
                    val clientPeerInfo = PeerInfo(
                        type = PeerInfo.ADB_RSA_PUB_KEY,
                        data = keyManager.getAdbPublicKeyBytes()
                    )
                    val encryptedPeerInfo = spake2Engine.encryptPayload(clientPeerInfo.toByteArray())
                    sendPacket(outputStream, TYPE_PEER_INFO, encryptedPeerInfo)

                    // 6. 接收并解密 PeerInfo (Type = 1)
                    val (respType, respPayload) = receivePacket(inputStream)
                    require(respType == TYPE_PEER_INFO) { "Expected PEER_INFO (1), got: $respType" }

                    val decryptedResponse = spake2Engine.decryptPayload(respPayload)
                    val serverPeerInfo = PeerInfo.fromByteArray(decryptedResponse)

                    val peerPubKey = String(serverPeerInfo.data, Charsets.UTF_8).trimEnd('\u0000')
                    listener?.onPairingSuccess(peerPubKey)
                    true
                }
            }
        } catch (e: Exception) {
            listener?.onPairingFailed(e)
            false
        }
    }

    /**
     * 发送 6 字节包头 (Version:1, Type:1, PayloadSize:4 Big-Endian) + Payload
     */
    private fun sendPacket(outputStream: DataOutputStream, type: Byte, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buffer.put(HEADER_VERSION)
        buffer.put(type)
        buffer.putInt(payload.size)

        outputStream.write(buffer.array())
        outputStream.write(payload)
        outputStream.flush()
    }

    /**
     * 读取 6 字节包头 + Payload
     */
    private fun receivePacket(inputStream: DataInputStream): Pair<Byte, ByteArray> {
        val headerBytes = ByteArray(6)
        inputStream.readFully(headerBytes)

        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get()
        val payloadSize = buffer.int

        if (version != HEADER_VERSION) {
            throw IOException("Unsupported pairing packet version: $version")
        }
        if (payloadSize <= 0 || payloadSize > 65536) {
            throw IOException("Invalid payload size: $payloadSize")
        }

        val payload = ByteArray(payloadSize)
        inputStream.readFully(payload)
        return Pair(type, payload)
    }

    /**
     * 使用 HiddenApiBypass 反射获取 TLS Key Material
     */
    private fun exportKeyingMaterial(sslSocket: SSLSocket, label: String, length: Int): ByteArray {
        val conscryptClass = Class.forName("com.android.org.conscrypt.Conscrypt")
        
        // 静态方法调用：第一个参数为 null
        // 依次传入签名：(Class, MethodName, args...)
        return HiddenApiBypass.invoke(
            conscryptClass,
            null,
            "exportKeyingMaterial",
            sslSocket,
            label,
            null as ByteArray?,
            length
        ) as ByteArray
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
        private const val KEY_PASSWORD = "adb_pair_password"

        private const val HEADER_VERSION: Byte = 1
        private const val TYPE_SPAKE2_MSG: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
    }
}

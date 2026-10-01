package libs.libs.libs.adb.pair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.tls.AdbTlsCertificate
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

            val spake2Engine = AdbSpake2Engine(pairingCode)

            Socket().use { rawSocket ->
                rawSocket.soTimeout = READ_TIMEOUT_MS
                rawSocket.tcpNoDelay = true
                rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                // 1. 生成 TLS 自签名证书与客户端凭证
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
                    rawSocket,
                    host,
                    port,
                    true
                ) as SSLSocket

                sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")

                sslSocket.use { tlsSocket ->
                    tlsSocket.startHandshake()

                    val inputStream = DataInputStream(tlsSocket.inputStream)
                    val outputStream = DataOutputStream(tlsSocket.outputStream)

                    // 2. 发送 Client Hello (SPAKE2 阶段 1)
                    val rawClientHello = spake2Engine.generateClientHello()
                    val clientPacket = PairingPacket(
                        type = PairingPacket.Type.SPAKE2_MSG,
                        payload = rawClientHello
                    )
                    sendPacket(outputStream, clientPacket)

                    // 3. 接收 Server Hello (SPAKE2 阶段 2)
                    val serverPacket = receivePacket(inputStream)

                    // 校验包类型
                    require(serverPacket.type == PairingPacket.Type.SPAKE2_MSG) {
                        "Expected SPAKE2_MSG packet type, got: ${serverPacket.type}"
                    }

                    // 💡 增加对 Server Hello Payload 长度的针对性判断
                    if (serverPacket.payload.isEmpty()) {
                        throw IOException("手机端拒绝了 Client Hello (返回空数据)")
                    }

                    require(serverPacket.payload.size == 32) {
                        "Server Hello 数据包长度异常，期望 32 字节，实际收到 ${serverPacket.payload.size} 字节。"
                    }

                    spake2Engine.processServerHelloAndDeriveKey(serverPacket.payload)

                    // 4. 发送 PeerInfo 客户端公钥 (SPAKE2 加密)
                    val pubKeyBytes = keyManager.getAdbPublicKeyBytes()
                    val clientPeerInfo = PeerInfo(
                        status = PeerInfo.Status.OK,
                        pubKey = pubKeyBytes
                    )
                    val serializedPeerInfo = AdbProtoUtils.encodePeerInfo(clientPeerInfo)
                    val encryptedPeerInfo = spake2Engine.encryptPayload(serializedPeerInfo)

                    val infoPacket = PairingPacket(
                        type = PairingPacket.Type.PEER_INFO,
                        payload = encryptedPeerInfo
                    )
                    sendPacket(outputStream, infoPacket)

                    // 5. 接收对端 PeerInfo 响应
                    val responsePacket = receivePacket(inputStream)
                    require(responsePacket.type == PairingPacket.Type.PEER_INFO) {
                        "Expected PEER_INFO packet type, got: ${responsePacket.type}"
                    }

                    val decryptedResponse = spake2Engine.decryptPayload(responsePacket.payload)
                    val serverPeerInfo = AdbProtoUtils.decodePeerInfo(decryptedResponse)

                    if (serverPeerInfo.status == PeerInfo.Status.OK) {
                        val peerPubKey = if (serverPeerInfo.pubKey.isNotEmpty()) {
                            String(serverPeerInfo.pubKey, Charsets.UTF_8).trimEnd('\u0000')
                        } else {
                            keyManager.getAdbPublicKeyString()
                        }
                        listener?.onPairingSuccess(peerPubKey)
                        true
                    } else {
                        val error = IllegalStateException("Pairing rejected by peer (status: ${serverPeerInfo.status})")
                        listener?.onPairingFailed(error)
                        false
                    }
                }
            }
        } catch (e: Exception) {
            listener?.onPairingFailed(e)
            false
        }
    }

    /**
     * 写入带 4 字节 Little-Endian 长度标头的 PairingPacket 报文
     */
    private fun sendPacket(out: DataOutputStream, packet: PairingPacket) {
        val bytes = AdbProtoUtils.encodePairingPacket(packet)
        val lenBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bytes.size)
            .array()

        out.write(lenBytes)
        out.write(bytes)
        out.flush()
    }

    /**
     * 读取带 4 字节 Little-Endian 长度标头的 PairingPacket 报文
     */
    private fun receivePacket(input: DataInputStream): PairingPacket {
        val lenBytes = ByteArray(4)
        input.readFully(lenBytes)

        val len = ByteBuffer.wrap(lenBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

        require(len in 1..65536) { "Invalid packet length received: $len" }

        val buf = ByteArray(len)
        input.readFully(buf)
        return AdbProtoUtils.decodePairingPacket(buf)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
        private const val KEY_PASSWORD = "adb_pair_password"
    }
}

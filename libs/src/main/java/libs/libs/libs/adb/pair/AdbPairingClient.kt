package libs.libs.libs.adb.pair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.tls.AdbTlsCertificate
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

@OptIn(ExperimentalSerializationApi::class)
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

                // 1. 获取密钥对并利用 AdbTlsCertificate 生成自签名 X.509 客户端证书
                val keyPair = keyManager.getKeyPair()
                val cert = AdbTlsCertificate.generateSelfSignedCertificate(keyPair)

                // 2. 构建 PKCS12 内存 KeyStore
                val keyStore = KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setKeyEntry("adb_pair_client", keyPair.private, KEY_PASSWORD.toCharArray(), arrayOf<X509Certificate>(cert))
                }

                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore, KEY_PASSWORD.toCharArray())
                }

                // 3. 初始化 TLS 1.3 上下文（带上 kmf.keyManagers 客户端凭证）
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

                    // 4. 【SPAKE2 阶段 1】发送 Client Hello
                    val rawClientHello = spake2Engine.generateClientHello()
                    val clientPacket = PairingPacket(
                        type = PairingPacket.Type.SPAKE2_MSG,
                        payload = rawClientHello
                    )
                    sendPacket(outputStream, clientPacket)

                    // 5. 【SPAKE2 阶段 2】接收 Server Hello 并派生密钥
                    val serverPacket = receivePacket(inputStream)
                    require(serverPacket.type == PairingPacket.Type.SPAKE2_MSG) {
                        "Expected SPAKE2_MSG packet type, got: ${serverPacket.type}"
                    }
                    spake2Engine.processServerHelloAndDeriveKey(serverPacket.payload)

                    // 6. 【密文传输阶段】构建 PeerInfo Protobuf 消息，序列化后加密发送
                    val pubKeyBytes = keyManager.getAdbPublicKeyBytes()
                    val clientPeerInfo = PeerInfo(
                        status = PeerInfo.Status.OK,
                        pubKey = pubKeyBytes
                    )
                    val serializedPeerInfo = ProtoBuf.encodeToByteArray(clientPeerInfo)
                    val encryptedPeerInfo = spake2Engine.encryptPayload(serializedPeerInfo)

                    val infoPacket = PairingPacket(
                        type = PairingPacket.Type.PEER_INFO,
                        payload = encryptedPeerInfo
                    )
                    sendPacket(outputStream, infoPacket)

                    // 7. 【结果校验】读取并解密对端 PeerInfo 响应
                    val responsePacket = receivePacket(inputStream)
                    require(responsePacket.type == PairingPacket.Type.PEER_INFO) {
                        "Expected PEER_INFO packet type, got: ${responsePacket.type}"
                    }

                    val decryptedResponse = spake2Engine.decryptPayload(responsePacket.payload)
                    val serverPeerInfo = ProtoBuf.decodeFromByteArray<PeerInfo>(decryptedResponse)

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

    @OptIn(ExperimentalSerializationApi::class)
    private val protoBuf = ProtoBuf {
        // 关键：强制序列化默认值，避免 Protobuf 字段缺失导致 Parse 报错
        encodeDefaults = true 
    }

    /**
     * 写入带 4 字节 Little-Endian 长度前缀的 Protobuf 报文
     */
    private fun sendPacket(out: DataOutputStream, packet: PairingPacket) {
        val bytes = protoBuf.encodeToByteArray(packet)
        val lenBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bytes.size)
            .array()

        out.write(lenBytes)
        out.write(bytes)
        out.flush()
    }

    /**
     * 读取带 4 字节 Little-Endian 长度前缀的 Protobuf 报文
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
        return protoBuf.decodeFromByteArray(buf)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
        private const val KEY_PASSWORD = "adb_pair_password"
    }
}

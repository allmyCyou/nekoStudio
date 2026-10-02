package libs.libs.libs.adb.pair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.tls.AdbTlsCertificate
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
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

                sslSocket.useClientMode = true
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

                    require(serverPacket.type == PairingPacket.Type.SPAKE2_MSG) {
                        "Expected SPAKE2_MSG packet type, got: ${serverPacket.type}"
                    }

                    if (serverPacket.payload.isEmpty()) {
                        throw IOException("手机端返回了空 Payload (配对码错误或已被拒绝)")
                    }

                    require(serverPacket.payload.size == 32) {
                        "Server Hello 数据包长度异常，期望 32 字节，实际收到 ${serverPacket.payload.size} 字节。"
                    }

                    spake2Engine.processServerHelloAndDeriveKey(serverPacket.payload)

                    // 4. 发送 PeerInfo 客户端公钥
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
     * 发送配对帧 (4 字节小端序长度 + Protobuf Payload)
     */
    fun sendPacket(outputStream: OutputStream, packet: PairingPacket) {
        val protobufBytes = AdbProtoUtils.encodePairingPacket(packet)
        val length = protobufBytes.size

        // 小端序 (Little-Endian)
        val header = byteArrayOf(
            (length and 0xFF).toByte(),
            (length ushr 8 and 0xFF).toByte(),
            (length ushr 16 and 0xFF).toByte(),
            (length ushr 24 and 0xFF).toByte()
        )

        outputStream.write(header)
        outputStream.write(protobufBytes)
        outputStream.flush()
    }
    
    /**
     * 接收配对帧 (4 字节小端序长度 + Protobuf Payload)
     */
    fun receivePacket(inputStream: InputStream): PairingPacket {
        val header = ByteArray(4)
        readFully(inputStream, header)

        // 小端序 (Little-Endian) 解析
        val length = (header[0].toInt() and 0xFF) or
                     ((header[1].toInt() and 0xFF) shl 8) or
                     ((header[2].toInt() and 0xFF) shl 16) or
                     ((header[3].toInt() and 0xFF) shl 24)

        if (length < 0 || length > 65536) {
            throw IOException("收到异常的报文长度帧: $length")
        }

        val payload = ByteArray(length)
        if (length > 0) {
            readFully(inputStream, payload)
        }
        return AdbProtoUtils.decodePairingPacket(payload)
    }
    
    private fun readFully(inputStream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = inputStream.read(buffer, offset, buffer.size - offset)
            if (count == -1) throw EOFException("手机端主动关闭了 Socket 连接")
            offset += count
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
        private const val KEY_PASSWORD = "adb_pair_password"
    }
}

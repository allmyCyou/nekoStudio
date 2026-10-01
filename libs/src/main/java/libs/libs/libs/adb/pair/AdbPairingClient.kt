package libs.libs.libs.adb.pair

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import libs.libs.libs.adb.key.AdbKeyManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
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
                rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                val sslContext = SSLContext.getInstance("TLSv1.3").apply {
                    init(null, arrayOf(AdbPairingTrustManager()), SecureRandom())
                }

                val sslSocket = sslContext.socketFactory.createSocket(
                    rawSocket,
                    host,
                    port,
                    true
                ) as SSLSocket

                sslSocket.use { tlsSocket ->
                    tlsSocket.startHandshake()

                    val inputStream = DataInputStream(tlsSocket.inputStream)
                    val outputStream = DataOutputStream(tlsSocket.outputStream)

                    // 1. 【SPAKE2 阶段 1】发送 Client Hello
                    val rawClientHello = spake2Engine.generateClientHello()
                    val clientPacket = PairingPacket(
                        type = PairingPacket.Type.SPAKE2_MSG,
                        payload = rawClientHello
                    )
                    sendPacket(outputStream, clientPacket)

                    // 2. 【SPAKE2 阶段 2】接收 Server Hello 并派生密钥
                    val serverPacket = receivePacket(inputStream)
                    require(serverPacket.type == PairingPacket.Type.SPAKE2_MSG) {
                        "Expected SPAKE2_MSG packet type, got: ${serverPacket.type}"
                    }
                    spake2Engine.processServerHelloAndDeriveKey(serverPacket.payload)

                    // 3. 【密文传输阶段】构建 PeerInfo Protobuf 消息，序列化后加密发送
                    // 使用 keyManager.getAdbPublicKeyBytes() 确保包含末尾 '\0'
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

                    // 4. 【结果校验】读取并解密对端 PeerInfo 响应
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

    private fun sendPacket(out: DataOutputStream, packet: PairingPacket) {
        val bytes = ProtoBuf.encodeToByteArray(packet)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun receivePacket(input: DataInputStream): PairingPacket {
        val len = input.readInt()
        require(len in 1..65536) { "Invalid packet length received: $len" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return ProtoBuf.decodeFromByteArray(buf)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
    }
}

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
import java.util.Arrays
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

                // 1. 配置 TLS v1.3 双向认证通道
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

                sslSocket.soTimeout = READ_TIMEOUT_MS
                sslSocket.useClientMode = true
                sslSocket.enabledProtocols = arrayOf("TLSv1.3")

                sslSocket.use { tlsSocket ->
                    tlsSocket.startHandshake()

                    // 2. 导出 Keying Material 并组合配对密码 (要求 label 为 "adb-label\0"，长度 64 字节)
                    val keyMaterial = exportKeyingMaterial(tlsSocket, EXPORTED_KEY_LABEL, EXPORT_KEY_SIZE)
                    val rawCodeBytes = pairingCode.toByteArray(Charsets.UTF_8)
                    val fullPassword = ByteArray(rawCodeBytes.size + keyMaterial.size)
                    System.arraycopy(rawCodeBytes, 0, fullPassword, 0, rawCodeBytes.size)
                    System.arraycopy(keyMaterial, 0, fullPassword, rawCodeBytes.size, keyMaterial.size)

                    try {
                        AdbSpake2Engine(fullPassword).use { spake2Engine ->
                            val inputStream = DataInputStream(tlsSocket.inputStream)
                            val outputStream = DataOutputStream(tlsSocket.outputStream)

                            // 3. 发送 SPAKE2 Client Hello (PacketType = 0)
                            val clientHello = spake2Engine.generateClientHello()
                            sendPacket(outputStream, PairingPacket.Type.SPAKE2_MSG, clientHello)

                            // 4. 接收 SPAKE2 Server Hello (PacketType = 0)
                            val serverHelloPacket = receivePacket(inputStream)
                            require(serverHelloPacket.type == PairingPacket.Type.SPAKE2_MSG) {
                                "Expected SPAKE2_MSG (0), got: ${serverHelloPacket.type}"
                            }

                            spake2Engine.processServerHelloAndDeriveKey(serverHelloPacket.payload)

                            // 5. 构造 8192 字节 Client PeerInfo 并通过 AES-128-GCM 加密发送
                            val pubKeyStr = keyManager.getAdbPublicKeyString().trim() + "\n"
                            val clientPeerInfoBytes = AdbProtoUtils.createClientPeerInfo(pubKeyStr)
                            val encryptedPeerInfo = spake2Engine.encryptPayload(clientPeerInfoBytes)
                            sendPacket(outputStream, PairingPacket.Type.PEER_INFO, encryptedPeerInfo)

                            // 6. 接收并解密 Server PeerInfo 响应 (8208 字节：8192 字节 payload + 16 字节 GCM Auth Tag)
                            val respPacket = receivePacket(inputStream)
                            require(respPacket.type == PairingPacket.Type.PEER_INFO) {
                                "Expected PEER_INFO (1), got: ${respPacket.type}"
                            }

                            val decryptedResponse = spake2Engine.decryptPayload(respPacket.payload)
                            val serverPeerInfoStr = AdbProtoUtils.parseServerPeerInfo(decryptedResponse)

                            listener?.onPairingSuccess(serverPeerInfoStr)
                            true
                        }
                    } finally {
                        Arrays.fill(fullPassword, 0.toByte())
                    }
                }
            }
        } catch (e: Exception) {
            listener?.onPairingFailed(e)
            false
        }
    }

    private fun sendPacket(outputStream: DataOutputStream, type: Int, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(HEADER_VERSION)
        buffer.put(type.toByte())
        buffer.putInt(payload.size)

        outputStream.write(buffer.array())
        outputStream.write(payload)
        outputStream.flush()
    }

    private fun receivePacket(inputStream: DataInputStream): PairingPacket {
        val headerBytes = ByteArray(HEADER_SIZE)
        inputStream.readFully(headerBytes)

        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get().toInt()
        val payloadSize = buffer.int

        if (version != HEADER_VERSION) {
            throw IOException("Unsupported pairing packet version: $version")
        }
        if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD_SIZE) {
            throw IOException("Invalid payload size: $payloadSize")
        }

        val payload = ByteArray(payloadSize)
        inputStream.readFully(payload)
        return PairingPacket(type, payload)
    }

    // 如果其他项目使用此项目中的adb库，需要注意，项目中已在 APP 模块中主动调用 HiddenApiBypass.setHiddenApiExemptions 来豁免系统类
    private fun exportKeyingMaterial(sslSocket: SSLSocket, label: String, length: Int): ByteArray {
        val conscryptClass = try {
            Class.forName("com.android.org.conscrypt.Conscrypt")
        } catch (e: ClassNotFoundException) {
            Class.forName("org.conscrypt.Conscrypt")
        }

        val method = HiddenApiBypass.getDeclaredMethod(
            conscryptClass,
            "exportKeyingMaterial",
            SSLSocket::class.java,
            String::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType
        )

        return method.invoke(null, sslSocket, label, null as ByteArray?, length) as ByteArray
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
        private const val KEY_PASSWORD = "adb_pair_password"

        private const val HEADER_VERSION: Byte = 1
        private const val HEADER_SIZE = 6
        private const val MAX_PAYLOAD_SIZE = 2 * PeerInfo.MAX_PEER_INFO_SIZE // 16384 Bytes

        // 关键对齐：AOSP 标准定义为 "adb-label\0"，长度为 64 字节
        private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
        private const val EXPORT_KEY_SIZE = 64
    }
}

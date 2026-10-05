package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import libs.libs.libs.adb.tls.AdbTlsSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

public class AdbSocket {

    private var rawSocket: Socket? = null
    private var tlsSocket: AdbTlsSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val writeMutex = Mutex()

    public val isTls: Boolean get() = tlsSocket != null

    /**
     * 连接原始 TCP Socket（用于 StartTLS 模式）
     */
    public suspend fun connectRaw(
        host: String,
        port: Int,
        timeoutMs: Int = 10000
    ) = withContext(Dispatchers.IO) {
        close()
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        
        this@AdbSocket.rawSocket = socket
        this@AdbSocket.inputStream = socket.getInputStream()
        this@AdbSocket.outputStream = socket.getOutputStream()
    }

    /**
     * 直接建立原生 TLS 加密 Socket 连接（Direct TLS 模式）
     */
    public suspend fun connectTls(
        host: String,
        port: Int,
        keyManager: AdbKeyManager,
        timeoutMs: Int = 10000
    ) = withContext(Dispatchers.IO) {
        close()
        val tls = AdbTlsSocket.connect(keyManager, host, port, timeoutMs)
        this@AdbSocket.tlsSocket = tls
        this@AdbSocket.inputStream = tls.inputStream
        this@AdbSocket.outputStream = tls.outputStream
    }

    /**
     * 将当前的 Raw Socket 原地升级为 TLS Socket (StartTLS)
     */
    public suspend fun upgradeToTls(
        keyManager: AdbKeyManager,
        timeoutMs: Int = 10000
    ) = withContext(Dispatchers.IO) {
        val socket = rawSocket ?: throw IllegalStateException("Raw socket is not connected")
        val tls = AdbTlsSocket.startTls(socket, keyManager, autoClose = true, handshakeTimeoutMs = timeoutMs)
        
        this@AdbSocket.tlsSocket = tls
        this@AdbSocket.inputStream = tls.inputStream
        this@AdbSocket.outputStream = tls.outputStream
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

    public suspend fun readPacket(maxPayloadCap: Int = AdbCommand.CONNECT_MAXDATA): AdbPacket = withContext(Dispatchers.IO) {
        val headerBytes = ByteArray(AdbPacket.HEADER_SIZE)
        readExactly(headerBytes, AdbPacket.HEADER_SIZE)

        val header = AdbPacket.parseHeader(headerBytes)
        check(header.isValid) { "Invalid ADB packet header magic check failed" }
        check(header.dataLength in 0..maxPayloadCap) {
            "Invalid ADB payload length: ${header.dataLength} (max=$maxPayloadCap)"
        }

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

    public suspend fun writePacket(packet: AdbPacket, skipChecksum: Boolean = true) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IllegalStateException("Socket is not connected")
        val bytes = packet.toByteArray(skipChecksum = skipChecksum)

        writeMutex.withLock {
            stream.write(bytes)
            stream.flush()
        }
    }

    public fun close() {
        try {
            inputStream?.close()
            outputStream?.close()
            tlsSocket?.close()
            rawSocket?.close()
        } catch (_: Exception) {
        } finally {
            inputStream = null
            outputStream = null
            tlsSocket = null
            rawSocket = null
        }
    }

    public val isConnected: Boolean
        get() = (tlsSocket?.isConnected == true) || (rawSocket?.isConnected == true && rawSocket?.isClosed == false)
}

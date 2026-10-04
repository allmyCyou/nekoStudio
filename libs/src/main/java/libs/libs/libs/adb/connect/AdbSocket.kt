package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import libs.libs.libs.adb.tls.AdbTlsSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

public class AdbSocket {

    private var rawSocket: Socket? = null
    private var tlsSocket: AdbTlsSocket? = null

    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // 写操作并发锁，防止多协程写入时字节流交错破坏 ADB 帧结构
    private val writeMutex = Mutex()
    
    // 复用 Header Buffer，避免在分发循环中频繁引发 GC
    private val headerBuffer = ByteArray(AdbPacket.HEADER_SIZE)

    public val isTls: Boolean get() = tlsSocket != null

    public suspend fun connect(host: String, port: Int, timeoutMs: Int = 10000) = withContext(Dispatchers.IO) {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)

        s.tcpNoDelay = true
        s.keepAlive = true
        s.soTimeout = 0 // ADB 长连接设为 0，防止空闲接收超时

        this@AdbSocket.rawSocket = s
        this@AdbSocket.inputStream = s.getInputStream()
        this@AdbSocket.outputStream = s.getOutputStream()
    }

    public suspend fun startTls(keyManager: AdbKeyManager, handshakeTimeoutMs: Int = 10000) = withContext(Dispatchers.IO) {
        val s = rawSocket ?: throw IllegalStateException("Socket is not connected")
        check(tlsSocket == null) { "TLS has already been enabled on this socket" }

        val tls = AdbTlsSocket.startTls(s, keyManager, autoClose = true, handshakeTimeoutMs = handshakeTimeoutMs)
        this@AdbSocket.tlsSocket = tls
        this@AdbSocket.inputStream = tls.inputStream
        this@AdbSocket.outputStream = tls.outputStream
    }

    private fun readExactly(stream: InputStream, buffer: ByteArray, length: Int) {
        var bytesRead = 0
        while (bytesRead < length) {
            val count = stream.read(buffer, bytesRead, length - bytesRead)
            if (count == -1) {
                throw IOException("Socket stream closed unexpectedly (read -1)")
            }
            bytesRead += count
        }
    }

    public suspend fun readPacket(maxPayloadCap: Int = AdbCommand.CONNECT_MAXDATA): AdbPacket = withContext(Dispatchers.IO) {
        val stream = inputStream ?: throw IOException("Socket is not connected")
        
        readExactly(stream, headerBuffer, AdbPacket.HEADER_SIZE)

        val header = AdbPacket.parseHeader(headerBuffer)
        check(header.isValid) { "Invalid ADB packet header magic check failed" }
        check(header.dataLength in 0..maxPayloadCap) {
            "Invalid ADB payload length: ${header.dataLength} (max=$maxPayloadCap)"
        }

        val payload = if (header.dataLength > 0) {
            ByteArray(header.dataLength).also { readExactly(stream, it, header.dataLength) }
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

    public suspend fun writePacket(packet: AdbPacket, skipChecksum: Boolean = isTls) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IOException("Socket is not connected")
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
        get() = tlsSocket?.isConnected ?: (rawSocket?.isConnected == true && rawSocket?.isClosed == false)
}

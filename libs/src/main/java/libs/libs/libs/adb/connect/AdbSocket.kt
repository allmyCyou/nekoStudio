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

    // 写操作并发锁，防止多协程写入时字节流交错破坏 ADB 帧结构
    private val writeMutex = Mutex()

    public val isTls: Boolean get() = tlsSocket != null

    /**
     * 建立基础 TCP Socket 连接
     */
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

    /**
     * 将当前 Socket 原位升级为 TLS 加密流
     */
    public suspend fun startTls(keyManager: AdbKeyManager, handshakeTimeoutMs: Int = 10000) = withContext(Dispatchers.IO) {
        val s = rawSocket ?: throw IllegalStateException("Socket is not connected")
        check(tlsSocket == null) { "TLS has already been enabled on this socket" }

        val tls = AdbTlsSocket.startTls(s, keyManager, autoClose = true, handshakeTimeoutMs = handshakeTimeoutMs)
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

    /**
     * 读取并解析 AdbPacket，对 Header Magic 与 Payload 上限进行严格防爆校验
     */
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

    /**
     * 线程/协程安全地发送 AdbPacket 数据包
     */
    public suspend fun writePacket(packet: AdbPacket, skipChecksum: Boolean = isTls) = withContext(Dispatchers.IO) {
        val stream = outputStream ?: throw IllegalStateException("Socket is not connected")
        val bytes = packet.toByteArray(skipChecksum = skipChecksum)

        // 线程安全互斥写入，保证 Header + Payload 连续不中断
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

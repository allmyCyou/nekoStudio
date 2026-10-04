package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

public class AdbConnection(
    private val keyManager: AdbKeyManager,
    // 1. 结合 SupervisorJob，确保作用域在整个 Connection 声明周期内常驻且互不干扰
    private val connectionScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val socket = AdbSocket()
    private val localIdGenerator = AtomicInteger(1)
    
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    public val state: StateFlow<AdbConnectionState> = _state.asStateFlow()

    private var negotiatedVersion: Int = AdbCommand.A_VERSION

    private var _features: Set<String> = emptySet()
    public val features: Set<String> get() = _features

    private val activeStreams = ConcurrentHashMap<Int, AdbStream>()
    private val pendingOpenRequests = ConcurrentHashMap<Int, Channel<AdbPacket>>()

    private var dispatchJob: Job? = null

    public fun hasFeature(feature: String): Boolean = _features.contains(feature)

    public val isSkipChecksum: Boolean 
        get() = negotiatedVersion >= AdbCommand.A_VERSION_SKIP_CHECKSUM

    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = "host::host_model=NekoStudio;mobile_model=Android;",
        timeoutMs: Long = 10000L
    ): AdbConnectionState.Connected = withContext(Dispatchers.IO) {
        try {
            _state.value = AdbConnectionState.Connecting

            // 将“建立 Socket + CNXN/AUTH 握手”全过程限制在超时时间内
            withTimeout(timeoutMs) {
                socket.connect(host, port, timeoutMs.toInt())

                // 1. 发送 CNXN 握手 (TCP 网络连接使用 0x01000000)
                val systemBanner = "$systemIdentity\u0000".toByteArray(Charsets.UTF_8)
                val cnxnPacket = AdbPacket(
                    command = AdbCommand.CMD_CNXN,
                    arg0 = AdbCommand.A_VERSION,
                    arg1 = AdbCommand.MAX_PAYLOAD,
                    payload = systemBanner
                )
                sendPacket(cnxnPacket)

                // 2. 握手 & 鉴权阶段
                var isHandshakeDone = false
                var sentSignature = false

                while (!isHandshakeDone) {
                    val response = socket.readPacket()

                    when (response.command) {
                        AdbCommand.CMD_CNXN -> {
                            negotiatedVersion = response.arg0
                            val banner = String(response.payload, Charsets.UTF_8).trimEnd('\u0000')
                            _features = parseFeatures(banner)
                            val connectedState = AdbConnectionState.Connected(banner)
                            _state.value = connectedState
                            isHandshakeDone = true
                        }

                        AdbCommand.CMD_STLS -> {
                            _state.value = AdbConnectionState.Authenticating
                            val stlsResponsePacket = AdbPacket(
                                command = AdbCommand.CMD_STLS,
                                arg0 = response.arg0,
                                arg1 = 0,
                                payload = ByteArray(0)
                            )
                            sendPacket(stlsResponsePacket)
                            socket.startTls(keyManager)
                            sendPacket(cnxnPacket)
                        }

                        AdbCommand.CMD_AUTH -> {
                            _state.value = AdbConnectionState.Authenticating
                            if (response.arg0 == AdbCommand.AUTH_TOKEN) {
                                if (!sentSignature) {
                                    val signature = keyManager.signToken(response.payload)
                                    val authSignaturePacket = AdbPacket(
                                        command = AdbCommand.CMD_AUTH,
                                        arg0 = AdbCommand.AUTH_SIGNATURE,
                                        arg1 = 0,
                                        payload = signature
                                    )
                                    sendPacket(authSignaturePacket)
                                    sentSignature = true
                                } else {
                                    // 触发手机端“允许 USB 调试吗”弹窗
                                    val pubKeyBytes = keyManager.getAdbPublicKeyBytes()
                                    val authPubKeyPacket = AdbPacket(
                                        command = AdbCommand.CMD_AUTH,
                                        arg0 = AdbCommand.AUTH_RSAPUBLICKEY,
                                        arg1 = 0,
                                        payload = pubKeyBytes
                                    )
                                    sendPacket(authPubKeyPacket)
                                }
                            } else {
                                throw IllegalStateException("Unexpected AUTH arg0: ${response.arg0}")
                            }
                        }

                        else -> {
                            throw IllegalStateException("Unexpected packet during handshake: 0x${Integer.toHexString(response.command)}")
                        }
                    }
                }
            }

            // 3. 握手成功，启动后台 Loop 接收解复用数据
            startDispatchLoop()

            // 4. 显式返回 Connected 状态
            _state.value as AdbConnectionState.Connected

        } catch (e: Exception) {
            disconnect()
            val errorState = AdbConnectionState.Error(e)
            _state.value = errorState
            throw e
        }
    }

    private fun startDispatchLoop() {
        dispatchJob?.cancel()
        dispatchJob = connectionScope.launch {
            try {
                while (socket.isConnected) {
                    val packet = try {
                        socket.readPacket()
                    } catch (e: Exception) {
                        // 底层 Socket 被关闭或达到 EOF，退出循环
                        break
                    }

                    val targetLocalId = packet.arg1

                    // A. 判断是否为 pendingOpenRequests 中的响应
                    val pendingChannel = pendingOpenRequests[targetLocalId]
                    if (pendingChannel != null) {
                        pendingChannel.send(packet)
                        continue
                    }

                    // B. 判断是否为已有 activeStreams 的响应
                    val stream = activeStreams[targetLocalId]
                    if (stream != null) {
                        when (packet.command) {
                            AdbCommand.CMD_OKAY -> {
                                stream.writeAckChannel.trySend(Unit)
                            }
                            AdbCommand.CMD_WRTE -> {
                                stream.incomingChannel.send(packet)
                            }
                            AdbCommand.CMD_CLSE -> {
                                stream.closeInternal()
                                stream.incomingChannel.send(packet)
                            }
                        }
                    } else {
                        // C. 收到非法或已流失 Stream 的数据包，回回复 CMD_CLSE 释放设备端资源
                        if (packet.command == AdbCommand.CMD_WRTE) {
                            val closePacket = AdbPacket(
                                command = AdbCommand.CMD_CLSE,
                                arg0 = packet.arg1,
                                arg1 = packet.arg0,
                                payload = ByteArray(0)
                            )
                            runCatching { sendPacket(closePacket) }
                        }
                    }
                }
            } finally {
                // 只有完全退出循环（底层连接断开）时才执行彻底清理
                cleanupOnDisconnected()
            }
        }
    }

    private fun parseFeatures(banner: String): Set<String> {
        val featuresSegment = banner.split(';')
            .firstOrNull { it.startsWith("features=") } ?: return emptySet()

        return featuresSegment.removePrefix("features=")
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()
    }

    public suspend fun openStream(destination: String): AdbStream? = withContext(Dispatchers.IO) {
        check(state.value is AdbConnectionState.Connected) { "ADB Connection is not active" }

        val localId = localIdGenerator.getAndIncrement()

        val destBytes = if (destination.endsWith("\u0000")) {
            destination.toByteArray(Charsets.UTF_8)
        } else {
            "$destination\u0000".toByteArray(Charsets.UTF_8)
        }

        val openPacket = AdbPacket(
            command = AdbCommand.CMD_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = destBytes
        )

        val openChannel = Channel<AdbPacket>(1)
        pendingOpenRequests[localId] = openChannel

        try {
            sendPacket(openPacket)

            val response = openChannel.receiveCatching().getOrNull() ?: return@withContext null

            if (response.command == AdbCommand.CMD_OKAY) {
                val remoteId = response.arg0
                val stream = AdbStream(this@AdbConnection, localId, remoteId)
                activeStreams[localId] = stream
                return@withContext stream
            } else {
                return@withContext null
            }
        } finally {
            pendingOpenRequests.remove(localId)
            openChannel.close()
        }
    }

    internal fun removeStream(localId: Int) {
        activeStreams.remove(localId)
    }

    public suspend fun sendPacket(packet: AdbPacket) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            socket.writePacket(packet, skipChecksum = isSkipChecksum)
        }
    }

    private fun cleanupOnDisconnected() {
        pendingOpenRequests.forEach { (_, channel) -> channel.close() }
        pendingOpenRequests.clear()

        activeStreams.values.forEach { it.closeInternal() }
        activeStreams.clear()

        socket.close()

        if (_state.value !is AdbConnectionState.Disconnected) {
            _state.value = AdbConnectionState.Disconnected
        }
    }

    public fun disconnect() {
        dispatchJob?.cancel()
        dispatchJob = null
        cleanupOnDisconnected()
    }
}

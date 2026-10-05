package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

public class AdbConnection(
    private val keyManager: AdbKeyManager,
    private val connectionScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val socket = AdbSocket()
    private val localIdGenerator = AtomicInteger(1)
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    public val state: StateFlow<AdbConnectionState> = _state.asStateFlow()

    private var negotiatedVersion: Int = AdbCommand.A_VERSION
    public var negotiatedMaxPayloadSize: Int = AdbCommand.MAX_PAYLOAD
        private set

    private var _features: Set<String> = emptySet()
    public val features: Set<String> get() = _features

    private val activeStreams = ConcurrentHashMap<Int, AdbStream>()
    private val pendingOpenRequests = ConcurrentHashMap<Int, Channel<AdbPacket>>()

    private var dispatchJob: Job? = null
    private val isCleanedUp = AtomicBoolean(false)

    public fun hasFeature(feature: String): Boolean = _features.contains(feature)

    public val isSkipChecksum: Boolean 
        get() = true // TLS 链路上默认跳过 CRC32 校验

    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = "host::;",
        timeoutMs: Long = 10000L
    ): AdbConnectionState.Connected = withContext(Dispatchers.IO) {
        try {
            isCleanedUp.set(false)
            _state.value = AdbConnectionState.Connecting

            val connectedState: AdbConnectionState.Connected = withTimeout(timeoutMs) {
                // 直接建立 TLS 加密 Socket 连接
                socket.connect(host, port, keyManager, timeoutMs.toInt())

                val systemBanner = "$systemIdentity\u0000".toByteArray(Charsets.UTF_8)
                val cnxnPacket = AdbPacket(
                    command = AdbCommand.CMD_CNXN,
                    arg0 = AdbCommand.A_VERSION,
                    arg1 = AdbCommand.MAX_PAYLOAD,
                    payload = systemBanner
                )
                sendPacket(cnxnPacket)

                // TLS 链路上直接等待对端响应 CNXN 报文
                val response = socket.readPacket(AdbCommand.CONNECT_MAXDATA)
                if (response.command != AdbCommand.CMD_CNXN) {
                    throw IllegalStateException("Unexpected packet during TLS handshake: 0x${Integer.toHexString(response.command)}")
                }

                negotiatedVersion = minOf(response.arg0, AdbCommand.A_VERSION)
                val peerMaxData = response.arg1
                if (peerMaxData > 0) {
                    negotiatedMaxPayloadSize = minOf(peerMaxData, AdbCommand.CONNECT_MAXDATA)
                }

                val banner = String(response.payload, Charsets.UTF_8).trimEnd('\u0000')
                _features = parseFeatures(banner)
                
                val state = AdbConnectionState.Connected(banner)
                _state.value = state
                state
            }

            startDispatchLoop()
            connectedState

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
                        socket.readPacket(negotiatedMaxPayloadSize)
                    } catch (e: Exception) {
                        break
                    }

                    val targetLocalId = packet.arg1

                    // 1. 优先路由给已激活的 Stream
                    val stream = activeStreams[targetLocalId]
                    if (stream != null) {
                        when (packet.command) {
                            AdbCommand.CMD_OKAY -> stream.onOkayReceived(packet)
                            AdbCommand.CMD_WRTE -> stream.incomingChannel.send(packet)
                            AdbCommand.CMD_CLSE -> {
                                runCatching { stream.incomingChannel.send(packet) }
                                stream.closeInternal()
                            }
                        }
                        continue
                    }

                    // 2. 路由给正在等待 OPEN 响应的 Request
                    val pendingChannel = pendingOpenRequests[targetLocalId]
                    if (pendingChannel != null) {
                        pendingChannel.trySend(packet)
                        continue
                    }

                    // 3. 未注册 Stream 的离群响应清理
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
            } finally {
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

        val isDelayedAck = hasFeature("delayed_ack")
        val initialRxWindow = if (isDelayedAck) negotiatedMaxPayloadSize else 0

        val openPacket = AdbPacket(
            command = AdbCommand.CMD_OPEN,
            arg0 = localId,
            arg1 = initialRxWindow,
            payload = destBytes
        )

        val openChannel = Channel<AdbPacket>(1)
        pendingOpenRequests[localId] = openChannel

        try {
            sendPacket(openPacket)

            val response = openChannel.receiveCatching().getOrNull() ?: return@withContext null

            if (response.command == AdbCommand.CMD_OKAY) {
                val remoteId = response.arg0

                val initialTxCredit = if (isDelayedAck && response.payload.size == 4) {
                    ByteBuffer.wrap(response.payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                } else {
                    negotiatedMaxPayloadSize.toLong()
                }

                val stream = AdbStream(
                    connection = this@AdbConnection,
                    localId = localId,
                    remoteId = remoteId,
                    maxPayloadSize = negotiatedMaxPayloadSize,
                    initialAvailableSendBytes = initialTxCredit
                )

                // 关键修复：务必在移除 pendingOpenRequests 之前将 stream 写入 activeStreams！
                // 防止 dispatchLoop 在高并发下读到后续的 CMD_WRTE 时 activeStreams 为空而给对端误发 CMD_CLSE。
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
        if (!isCleanedUp.compareAndSet(false, true)) return

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

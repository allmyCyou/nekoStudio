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
import kotlinx.coroutines.withTimeout
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
        get() = socket.isTls || negotiatedVersion >= AdbCommand.A_VERSION_SKIP_CHECKSUM

    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = "host::nekoStudio@adbClient;",
        timeoutMs: Long = 10000L,
        directTls: Boolean = false
    ): AdbConnectionState.Connected = withContext(Dispatchers.IO) {
        try {
            isCleanedUp.set(false)
            _state.value = AdbConnectionState.Connecting

            val connectedState: AdbConnectionState.Connected = withTimeout(timeoutMs) {
                // 1. 建立基础 Socket 或 Direct TLS 连接
                if (directTls) {
                    socket.connectTls(host, port, keyManager, timeoutMs.toInt())
                } else {
                    socket.connectRaw(host, port, timeoutMs.toInt())
                }

                // 2. 发送第一个 CNXN 握手报文
                val cnxnPacket = AdbPacket.createCnxn(
                    version = AdbCommand.A_VERSION,
                    maxPayload = AdbCommand.CONNECT_MAXDATA,
                    features = AdbCommand.DEFAULT_FEATURES,
                    systemIdentity = systemIdentity
                )
                socket.writePacket(cnxnPacket, skipChecksum = socket.isTls)

                var response = socket.readPacket(AdbCommand.CONNECT_MAXDATA, skipChecksum = socket.isTls)

                // 3. 拦截 CMD_STLS (Android 11+ StartTLS 升级)
                if (response.command == AdbCommand.CMD_STLS) {
                    val stlsAck = AdbPacket.createStls(AdbCommand.A_STLS_VERSION)
                    socket.writePacket(stlsAck, skipChecksum = false)

                    socket.upgradeToTls(keyManager, timeoutMs.toInt())

                    // TLS 升级完成，重新发送加密通道后的 CNXN 握手
                    socket.writePacket(cnxnPacket, skipChecksum = true)
                    response = socket.readPacket(AdbCommand.CONNECT_MAXDATA, skipChecksum = true)
                }

                // 4. 拦截 CMD_AUTH 挑战 (传统/非 TLS RSA 签名鉴权)
                if (response.command == AdbCommand.CMD_AUTH) {
                    _state.value = AdbConnectionState.Authenticating
                    response = handleRsaAuthentication(response)
                }

                // 5. 校验最终握手响应
                if (response.command != AdbCommand.CMD_CNXN) {
                    throw IllegalStateException("Unexpected packet during handshake: 0x${Integer.toHexString(response.command)}")
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

    /**
     * 处理 CMD_AUTH Challenge/Response 鉴权流程
     */
    private suspend fun handleRsaAuthentication(initialAuthPacket: AdbPacket): AdbPacket {
        var currentPacket = initialAuthPacket

        while (currentPacket.command == AdbCommand.CMD_AUTH) {
            if (currentPacket.arg0 == AdbCommand.AUTH_TOKEN) {
                // 1. 使用 AdbKeyManager.signToken 对对端 Token 签名
                val token = currentPacket.payload
                val signature = keyManager.signToken(token)
                val authSigPacket = AdbPacket.createAuth(AdbCommand.AUTH_SIGNATURE, signature)
                socket.writePacket(authSigPacket, skipChecksum = socket.isTls)

                currentPacket = socket.readPacket(AdbCommand.CONNECT_MAXDATA, skipChecksum = socket.isTls)

                // 2. 签名验证失败时，使用 AdbKeyManager.getAdbPublicKeyBytes 发送 RSA 公钥以触发设备弹窗授权
                if (currentPacket.command == AdbCommand.CMD_AUTH && currentPacket.arg0 == AdbCommand.AUTH_TOKEN) {
                    val pubKeyBytes = keyManager.getAdbPublicKeyBytes()
                    val authPubKeyPacket = AdbPacket.createAuth(AdbCommand.AUTH_RSAPUBLICKEY, pubKeyBytes)
                    socket.writePacket(authPubKeyPacket, skipChecksum = socket.isTls)

                    // 阻塞等待用户在设备弹窗点击授权确认
                    currentPacket = socket.readPacket(AdbCommand.CONNECT_MAXDATA, skipChecksum = socket.isTls)
                }
            } else {
                throw IllegalStateException("Unsupported AUTH type: ${currentPacket.arg0}")
            }
        }
        return currentPacket
    }

    private fun startDispatchLoop() {
        dispatchJob?.cancel()
        dispatchJob = connectionScope.launch {
            try {
                while (socket.isConnected) {
                    val packet = try {
                        socket.readPacket(negotiatedMaxPayloadSize, skipChecksum = isSkipChecksum)
                    } catch (_: Exception) {
                        break
                    }

                    val targetLocalId = packet.arg1

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

                    val pendingChannel = pendingOpenRequests[targetLocalId]
                    if (pendingChannel != null) {
                        pendingChannel.trySend(packet)
                        continue
                    }

                    if (packet.command == AdbCommand.CMD_WRTE) {
                        val closePacket = AdbPacket.createClose(localId = packet.arg1, remoteId = packet.arg0)
                        runCatching { sendPacket(closePacket) }
                    }
                }
            } finally {
                cleanupOnDisconnected()
            }
        }
    }

    private fun parseFeatures(banner: String): Set<String> {
        val cleanBanner = banner.trim { it <= ' ' || it == '\u0000' }

        val featuresSegment = cleanBanner.split(';')
            .firstOrNull { it.trim().startsWith("features=") } ?: return emptySet()

        return featuresSegment.trim()
            .removePrefix("features=")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    public suspend fun openStream(destination: String): AdbStream? = withContext(Dispatchers.IO) {
        check(state.value is AdbConnectionState.Connected) { "ADB Connection is not active" }

        val localId = localIdGenerator.getAndIncrement()
        val isDelayedAck = hasFeature(AdbCommand.FEATURE_DELAYED_ACK)
        val initialRxWindow = if (isDelayedAck) negotiatedMaxPayloadSize else 0

        val openPacket = AdbPacket.createOpen(
            localId = localId,
            destination = destination,
            initialRxWindow = initialRxWindow
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

    public suspend fun sendPacket(packet: AdbPacket): Unit = withContext(Dispatchers.IO) {
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

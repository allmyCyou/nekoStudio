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

    /**
     * 连接入口：根据 directTls 参数彻底拆分到不同的握手流水线
     */
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
                if (directTls) {
                    connectDirectTlsFlow(host, port, systemIdentity, timeoutMs)
                } else {
                    connectTcpFlow(host, port, systemIdentity, timeoutMs)
                }
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

    // 分流 1：Direct TLS 连接流水线 (如 Android 11+ 无线调试 TLS 端口)
    private suspend fun connectDirectTlsFlow(
        host: String,
        port: Int,
        systemIdentity: String,
        timeoutMs: Long
    ): AdbConnectionState.Connected {
        // 1. 直接建立底层 TLS 双向认证连接
        socket.connectTls(host, port, keyManager, timeoutMs.toInt())

        // 2. 在 TLS 通道内直接发送 CNXN 握手（TLS 模式全程忽略 Checksum）
        val cnxnPacket = AdbPacket.createCnxn(
            version = AdbCommand.A_VERSION,
            maxPayload = AdbCommand.CONNECT_MAXDATA,
            features = AdbCommand.DEFAULT_FEATURES,
            systemIdentity = systemIdentity
        )
        socket.writePacket(cnxnPacket, skipChecksum = true)

        // 3. 读取 TLS 响应报文
        val response = socket.readPacket(AdbCommand.CONNECT_MAXDATA, skipChecksum = true)

        if (response.command != AdbCommand.CMD_CNXN) {
            throw IllegalStateException("Direct TLS Handshake failed: expected CMD_CNXN but received 0x${Integer.toHexString(response.command)}")
        }

        return finalizeHandshake(response)
    }

    // 分流 2：传统 TCP / StartTLS 动态升级流水线 (如 5555 端口 / USB)
    private suspend fun connectTcpFlow(
        host: String,
        port: Int,
        systemIdentity: String,
        timeoutMs: Long
    ): AdbConnectionState.Connected {
        // 1. 建立基础 Raw TCP Socket
        socket.connectRaw(host, port, timeoutMs.toInt())

        // 2. 发送初始 CNXN 报文（标准 TCP 模式下需计算 Checksum）
        val cnxnPacket = AdbPacket.createCnxn(
            version = AdbCommand.A_VERSION,
            maxPayload = AdbCommand.CONNECT_MAXDATA,
            features = AdbCommand.DEFAULT_FEATURES,
            systemIdentity = systemIdentity
        )
        socket.writePacket(cnxnPacket, skipChecksum = false)

        var response = socket.readPacket(AdbCommand.CONNECT_MAXDATA, skipChecksum = false)

        // 3. 分流处理：是否触发 Android 11+ StartTLS 协议升级
        if (response.command == AdbCommand.CMD_STLS) {
            val stlsAck = AdbPacket.createStls(AdbCommand.A_STLS_VERSION)
            socket.writePacket(stlsAck, skipChecksum = false)

            // 升级当前 Socket 为 TLS
            socket.upgradeToTls(keyManager, timeoutMs.toInt())

            // TLS 升级成功后，必须在加密通道内重发 CNXN 报文，后续全部 skipChecksum
            socket.writePacket(cnxnPacket, skipChecksum = true)
            response = socket.readPacket(AdbCommand.CONNECT_MAXDATA, skipChecksum = true)
        }

        // 4. 分流处理：传统 RSA 签名鉴权 (CMD_AUTH)
        if (response.command == AdbCommand.CMD_AUTH) {
            _state.value = AdbConnectionState.Authenticating
            response = handleRsaAuthentication(response)
        }

        // 5. 校验最终响应
        if (response.command != AdbCommand.CMD_CNXN) {
            throw IllegalStateException("TCP Handshake failed: expected CMD_CNXN but received 0x${Integer.toHexString(response.command)}")
        }

        return finalizeHandshake(response)
    }

    /**
     * 处理传统 TCP 模式下的 CMD_AUTH Challenge/Response 鉴权流程
     */
    private suspend fun handleRsaAuthentication(initialAuthPacket: AdbPacket): AdbPacket {
        var currentPacket = initialAuthPacket

        while (currentPacket.command == AdbCommand.CMD_AUTH) {
            if (currentPacket.arg0 == AdbCommand.AUTH_TOKEN) {
                // 尝试用私钥签名 Token
                val token = currentPacket.payload
                val signature = keyManager.signToken(token)
                val authSigPacket = AdbPacket.createAuth(AdbCommand.AUTH_SIGNATURE, signature)
                socket.writePacket(authSigPacket, skipChecksum = socket.isTls)

                currentPacket = socket.readPacket(AdbCommand.CONNECT_MAXDATA, skipChecksum = socket.isTls)

                // 签名不通过（对端未信任该公钥），发送 RSA 公钥触发设备弹窗
                if (currentPacket.command == AdbCommand.CMD_AUTH && currentPacket.arg0 == AdbCommand.AUTH_TOKEN) {
                    val pubKeyBytes = keyManager.getAdbPublicKeyBytes()
                    val authPubKeyPacket = AdbPacket.createAuth(AdbCommand.AUTH_RSAPUBLICKEY, pubKeyBytes)
                    socket.writePacket(authPubKeyPacket, skipChecksum = socket.isTls)

                    // 阻塞等待用户在手机弹窗点击确认授权
                    currentPacket = socket.readPacket(AdbCommand.CONNECT_MAXDATA, skipChecksum = socket.isTls)
                }
            } else {
                throw IllegalStateException("Unsupported AUTH type: ${currentPacket.arg0}")
            }
        }
        return currentPacket
    }

    /**
     * 统一解析 CMD_CNXN 响应并初始化连接参数与 State
     */
    private fun finalizeHandshake(cnxnResponse: AdbPacket): AdbConnectionState.Connected {
        negotiatedVersion = minOf(cnxnResponse.arg0, AdbCommand.A_VERSION)
        val peerMaxData = cnxnResponse.arg1
        if (peerMaxData > 0) {
            negotiatedMaxPayloadSize = minOf(peerMaxData, AdbCommand.CONNECT_MAXDATA)
        }

        val banner = String(cnxnResponse.payload, Charsets.UTF_8).trimEnd('\u0000')
        _features = parseFeatures(banner)

        val connectedState = AdbConnectionState.Connected(banner)
        _state.value = connectedState
        return connectedState
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

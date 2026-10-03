package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

public class AdbConnection(private val keyManager: AdbKeyManager) {

    private val socket = AdbSocket()
    private val localIdGenerator = AtomicInteger(1)
    
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    public val state: StateFlow<AdbConnectionState> = _state.asStateFlow()

    private var negotiatedVersion: Int = AdbCommand.A_VERSION

    private var _features: Set<String> = emptySet()
    public val features: Set<String> get() = _features

    private val activeStreams = ConcurrentHashMap<Int, AdbStream>()
    private val pendingOpenRequests = ConcurrentHashMap<Int, kotlinx.coroutines.channels.Channel<AdbPacket>>()

    private var dispatchJob: Job? = null
    private val connectionScope = CoroutineScope(Dispatchers.IO)

    public fun hasFeature(feature: String): Boolean = _features.contains(feature)

    public val isSkipChecksum: Boolean 
        get() = negotiatedVersion >= AdbCommand.A_VERSION_SKIP_CHECKSUM

    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String = "host::host_model=NekoStudio;mobile_model=Android;",
        timeoutMs: Int = 10000
    ) = withContext(Dispatchers.IO) {
        try {
            _state.value = AdbConnectionState.Connecting
            socket.connect(host, port, timeoutMs)

            // 1. 发送 CNXN 握手
            val systemBanner = "$systemIdentity\u0000".toByteArray(Charsets.UTF_8)
            val cnxnPacket = AdbPacket(
                command = AdbCommand.CMD_CNXN,
                arg0 = AdbCommand.A_VERSION_SKIP_CHECKSUM,
                arg1 = AdbCommand.MAX_PAYLOAD,
                payload = systemBanner
            )
            sendPacket(cnxnPacket)

            // 2. 握手 & RSA 鉴权 / TLS 协商阶段
            var isHandshakeDone = false
            var sentPublicKey = false

            while (!isHandshakeDone) {
                val response = socket.readPacket()

                when (response.command) {
                    AdbCommand.CMD_CNXN -> {
                        negotiatedVersion = response.arg0
                        val banner = String(response.payload, Charsets.UTF_8).trimEnd('\u0000')
                        _features = parseFeatures(banner)
                        _state.value = AdbConnectionState.Connected(banner)
                        isHandshakeDone = true
                    }

                    AdbCommand.CMD_STLS -> {
                        // 收到设备发来的 TLS 升级指令 (Android 11+ 无线调试)
                        _state.value = AdbConnectionState.Authenticating
                        socket.startTls(keyManager)
                        
                        // 完成 TLS 握手后，在 TLS 加密通道上重新发起 CNXN 协商
                        sendPacket(cnxnPacket)
                    }

                    AdbCommand.CMD_AUTH -> {
                        _state.value = AdbConnectionState.Authenticating

                        if (response.arg0 == AdbCommand.AUTH_TOKEN) {
                            if (!sentPublicKey) {
                                val signature = keyManager.signToken(response.payload)
                                val authSignaturePacket = AdbPacket(
                                    command = AdbCommand.CMD_AUTH,
                                    arg0 = AdbCommand.AUTH_SIGNATURE,
                                    arg1 = 0,
                                    payload = signature
                                )
                                sendPacket(authSignaturePacket)
                            } else {
                                throw IllegalStateException("ADB Authorization rejected by device.")
                            }
                        } else {
                            val pubKeyBytes = keyManager.getAdbPublicKeyBytes()
                            val authPubKeyPacket = AdbPacket(
                                command = AdbCommand.CMD_AUTH,
                                arg0 = AdbCommand.AUTH_RSAPUBLICKEY,
                                arg1 = 0,
                                payload = pubKeyBytes
                            )
                            sendPacket(authPubKeyPacket)
                            sentPublicKey = true
                        }
                    }

                    else -> {
                        throw IllegalStateException("Unexpected packet during handshake: 0x${Integer.toHexString(response.command)}")
                    }
                }
            }

            // 3. 握手成功后，启动后台解复用分发器 Loop
            startDispatchLoop()

        } catch (e: Exception) {
            disconnect()
            _state.value = AdbConnectionState.Error(e)
            throw e
        }
    }

    private fun startDispatchLoop() {
        dispatchJob?.cancel()
        dispatchJob = connectionScope.launch {
            try {
                while (socket.isConnected) {
                    val packet = socket.readPacket()
                    val targetLocalId = packet.arg1

                    val pendingChannel = pendingOpenRequests[targetLocalId]
                    if (pendingChannel != null) {
                        pendingChannel.send(packet)
                        continue
                    }

                    val stream = activeStreams[targetLocalId]
                    if (stream != null) {
                        when (packet.command) {
                            AdbCommand.CMD_OKAY -> {
                                stream.writeAckChannel.trySend(Unit)
                            }
                            AdbCommand.CMD_WRTE, AdbCommand.CMD_CLSE -> {
                                stream.incomingChannel.send(packet)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                activeStreams.values.forEach { it.closeInternal() }
                activeStreams.clear()
                _state.value = AdbConnectionState.Disconnected
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

        val openChannel = kotlinx.coroutines.channels.Channel<AdbPacket>(1)
        pendingOpenRequests[localId] = openChannel

        try {
            sendPacket(openPacket)

            val response = openChannel.receive()

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

    public fun disconnect() {
        dispatchJob?.cancel()
        dispatchJob = null
        activeStreams.values.forEach { it.closeInternal() }
        activeStreams.clear()
        pendingOpenRequests.clear()
        socket.close()
        _state.value = AdbConnectionState.Disconnected
    }
}

package libs.libs.libs.adb.connect

import libs.libs.libs.adb.key.AdbKeyManager
import libs.libs.libs.adb.public.AdbCommand
import libs.libs.libs.adb.public.AdbPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    private var dispatchJob: Job? = null
    private val isCleanedUp = AtomicBoolean(false)

    public fun hasFeature(feature: String): Boolean = _features.contains(feature)

    public val isSkipChecksum: Boolean 
        get() = socket.isTls 

    public suspend fun connect(
        host: String,
        port: Int = 5555,
        systemIdentity: String? = null,
        timeoutMs: Long = 10000L,
        directTls: Boolean = false
    ): AdbConnectionState.Connected = withContext(Dispatchers.IO) {
        try {
            isCleanedUp.set(false)
            _state.value = AdbConnectionState.Connecting

            val connectedState: AdbConnectionState.Connected = withTimeout(timeoutMs) {
                if (directTls) {
                    socket.connectTls(host, port, keyManager, timeoutMs.toInt())
                } else {
                    socket.connectRaw(host, port, timeoutMs.toInt())
                }

                // 1. 标准化构造 Host Features 声明 (确保不带尾部分号且包含 \0)
                val defaultFeatureList = listOf(
                    "shell_v2", "cmd", "stat_v2", "ls_v2", "fixed_push_mkdir",
                    "apex", "abb", "fixed_push_symlink_timestamp", "abb_exec",
                    "remount_shell", "track_app", "sendrecv_v2", "sendrecv_v2_brotli",
                    "sendrecv_v2_lz4", "sendrecv_v2_zstd", "sendrecv_v2_dry_run_send",
                    "openscreen_mdns", "devicetracker_proto_format", "devraw",
                    "app_info", "server_status", "delayed_ack"
                )
                
                val finalIdentity = systemIdentity ?: "host::features=${defaultFeatureList.joinToString(",")}"

                val cnxnPacket = AdbPacket.createCnxn(
                    version = AdbCommand.A_VERSION,
                    maxPayload = AdbCommand.CONNECT_MAXDATA,
                    features = defaultFeatureList,
                    systemIdentity = finalIdentity
                )
                
                socket.writePacket(cnxnPacket, skipChecksum = socket.isTls)

                var response = socket.readPacket(AdbCommand.CONNECT_MAXDATA)

                if (response.command == AdbCommand.CMD_STLS) {
                    val stlsAck = AdbPacket(
                        command = AdbCommand.CMD_STLS,
                        arg0 = 1,
                        arg1 = 0,
                        payload = ByteArray(0)
                    )
                    socket.writePacket(stlsAck, skipChecksum = false)
                    socket.upgradeToTls(keyManager, timeoutMs.toInt())
                    response = socket.readPacket(AdbCommand.CONNECT_MAXDATA)
                }

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
                    val stream = activeStreams[targetLocalId]

                    if (stream != null) {
                        when (packet.command) {
                            AdbCommand.CMD_OKAY -> {
                                if (!stream.isOpen) {
                                    stream.onOpenReceived(packet)
                                } else {
                                    stream.onOkayReceived(packet)
                                }
                            }
                            AdbCommand.CMD_WRTE -> {
                                stream.incomingChannel.trySend(packet)
                            }
                            AdbCommand.CMD_CLSE -> {
                                if (!stream.isOpen) {
                                    stream.onOpenFailed("Received CLSE during stream OPEN")
                                } else {
                                    runCatching { stream.incomingChannel.trySend(packet) }
                                    stream.closeInternal()
                                }
                            }
                        }
                        continue
                    }

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
            .firstOrNull { it.trim().startsWith("features=") } ?: return emptySet()

        return featuresSegment.trim()
            .removePrefix("features=")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    public suspend fun openStream(destination: String): AdbStream? = withContext(Dispatchers.IO) {
        check(state.value is AdbConnectionState.Connected) { "ADB Connection is not active" }

        // 2. 确保 A_OPEN 的 destination 字符串以 NUL ('\0') 字节结尾
        val formattedDestination = if (destination.endsWith("\u0000")) destination else "$destination\u0000"

        val localId = localIdGenerator.getAndIncrement()

        val stream = AdbStream(
            connection = this@AdbConnection,
            localId = localId,
            maxPayloadSize = negotiatedMaxPayloadSize
        )
        activeStreams[localId] = stream

        try {
            val isDelayedAck = hasFeature(AdbCommand.FEATURE_DELAYED_ACK)
            val initialRxWindow = if (isDelayedAck) negotiatedMaxPayloadSize else 0
            val openPacket = AdbPacket.createOpen(localId, formattedDestination, initialRxWindow)

            sendPacket(openPacket)

            val openSuccess = withTimeoutOrNull(5000L) { stream.awaitOpen() } != null

            if (openSuccess) {
                return@withContext stream
            } else {
                activeStreams.remove(localId)
                stream.closeInternal()
                return@withContext null
            }
        } catch (e: Exception) {
            activeStreams.remove(localId)
            stream.closeInternal()
            return@withContext null
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

package com.adb.kitty.ui.it.cpu

import com.adb.kitty.service.AdbSessionService

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.io.File

enum class SampleInterval(val label: String, val millis: Long) {
    ULTRA("100ms", 100L),    // 极速
    FAST("200ms", 200L),     // 高频
    MEDIUM("500ms", 500L),   // 均衡
    NORMAL("1000ms", 1000L), // 标准
    SLOW("2000ms", 2000L),   // 低耗
    LONG_3S("3000ms", 3000L),   // 轻量长测
    BATTERY_5S("5000ms", 5000L), // 电池专项
    STANDBY_10S("10s", 10000L)  // 待机挂测
}

@Immutable
data class CpuCoreMetric(
    val coreIndex: Int = 0,
    val curFreqGhz: Float = 0f,
    val minFreqGhz: Float = 0f,
    val maxFreqGhz: Float = 0f,
    val history: List<Float> = emptyList()
)

@Immutable
data class GpuMetric(
    val curFreqGhz: Float = 0f,
    val minFreqGhz: Float = 0f,
    val maxFreqGhz: Float = 0f,
    val utilizationPercent: Float = 0f,
    val history: List<Float> = emptyList()
)

@Immutable
data class NetworkMetric(
    val rxSpeedKbps: Float = 0f,
    val txSpeedKbps: Float = 0f,
    val rxTotalMb: Float = 0f,
    val txTotalMb: Float = 0f,
    val lossRatePercent: Float = 0f,
    val rxSpeedHistory: List<Float> = emptyList()
) {
    val totalMb: Float get() = rxTotalMb + txTotalMb
}

data class HistoryRecording(
    val file: File,
    val formattedDate: String,
    val durationSeconds: Int,
    val samples: List<PerformanceSample> = emptyList()
)

@Immutable
data class RomMetric(
    val totalGb: Float = 0f,
    val availGb: Float = 0f,
    val readSpeedMb: Float = 0f,
    val writeSpeedMb: Float = 0f,
    val readHistory: List<Float> = emptyList(),
    val writeHistory: List<Float> = emptyList()
) {
    val usedGb: Float get() = (totalGb - availGb).coerceAtLeast(0f)
    val usedPercent: Float get() = if (totalGb > 0) (usedGb / totalGb) * 100f else 0f
}

data class RawNetStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val txPackets: Long = 0L,
    val rxErrorsDrops: Long = 0L,
    val txErrorsDrops: Long = 0L
) {
    val totalBytes: Long get() = rxBytes + txBytes
    val totalPackets: Long get() = rxPackets + txPackets
    val totalErrorsDrops: Long get() = rxErrorsDrops + txErrorsDrops

    fun calcPacketLossRate(): Float {
        val denom = totalPackets + totalErrorsDrops
        return if (denom > 0) (totalErrorsDrops.toFloat() / denom.toFloat()) * 100f else 0f
    }

    operator fun minus(other: RawNetStats): RawNetStats {
        return RawNetStats(
            rxBytes = (rxBytes - other.rxBytes).coerceAtLeast(0L),
            txBytes = (txBytes - other.txBytes).coerceAtLeast(0L),
            rxPackets = (rxPackets - other.rxPackets).coerceAtLeast(0L),
            txPackets = (txPackets - other.txPackets).coerceAtLeast(0L),
            rxErrorsDrops = (rxErrorsDrops - other.rxErrorsDrops).coerceAtLeast(0L),
            txErrorsDrops = (txErrorsDrops - other.txErrorsDrops).coerceAtLeast(0L)
        )
    }
}

data class PerformanceSample(
    val timestampMs: Long,
    val fps: Float = 0f,
    val refreshRate: Float = 0f,

    val batteryTemp: Float = 0f,
    val batteryLevel: Int = 0,
    val batteryCurrentMa: Float = 0f,
    val batteryVoltageMv: Float = 0f,
    val batteryPowerW: Float = 0f,
    val batteryStatus: String = "Unknown",
    val batteryChargeType: String = "Unknown",
    val batteryHealth: String = "Unknown",
    val batteryCycleCount: Int = 0,
    val batteryFullMah: Float = 0f,
    val batteryFullDesignMah: Float = 0f,
    val batterySohPercent: Float = 0f,
    val batteryCurrentHistory: List<Float> = emptyList(),

    val ramTotalGb: Float = 0f,
    val ramAvailGb: Float = 0f,
    val zramTotalGb: Float = 0f,
    val zramAvailGb: Float = 0f,
    val romReadSpeedMb: Float = 0f,
    val romWriteSpeedMb: Float = 0f,
    val gpuFreqGhz: Float = 0f,
    val gpuLoadPercent: Float = 0f,
    val gpuMinFreqGhz: Float = 0f,
    val gpuMaxFreqGhz: Float = 0f,
    val cpuFreqsGhz: List<Float> = emptyList(),
    val cpuHwLimitsGhz: List<Pair<Float, Float>> = emptyList(),
    val cellRxSpeedKbps: Float = 0f,
    val cellTxSpeedKbps: Float = 0f,
    val cellTotalMb: Float = 0f,
    val cellLossRate: Float = 0f,
    val wlanRxSpeedKbps: Float = 0f,
    val wlanTxSpeedKbps: Float = 0f,
    val wlanTotalMb: Float = 0f,
    val wlanLossRate: Float = 0f
)

@Immutable
data class PerformanceUiState(
    val isRootConnected: Boolean = false,
    val renderFps: Float = 0f,
    val refreshRateHz: Float = 0f,

    val batteryTemp: Float = 0f,
    val batteryLevel: Int = 0,
    val batteryCurrentMa: Float = 0f,
    val batteryVoltageMv: Float = 0f,
    val batteryPowerW: Float = 0f,
    val batteryStatus: String = "Unknown",
    val batteryChargeType: String = "Unknown",
    val batteryHealth: String = "Unknown",
    val batteryCycleCount: Int = 0,
    val batteryFullMah: Float = 0f,
    val batteryFullDesignMah: Float = 0f,
    val batterySohPercent: Float = 0f,
    val batteryCurrentHistory: List<Float> = emptyList(),

    val romMetric: RomMetric = RomMetric(),

    val ramTotalGb: Float = 0f,
    val ramAvailGb: Float = 0f,
    val ramAvailHistory: List<Float> = emptyList(),
    val zramTotalGb: Float = 0f,
    val zramAvailGb: Float = 0f,
    val zramAvailHistory: List<Float> = emptyList(),

    val fpsHistory: List<Float> = emptyList(),
    val gpuMetric: GpuMetric = GpuMetric(),
    val cpuCores: List<CpuCoreMetric> = emptyList(),

    val cellMetric: NetworkMetric = NetworkMetric(),
    val wlanMetric: NetworkMetric = NetworkMetric(),
    
    val currentResolution: String = "",
    val supportedDisplayModes: List<String> = emptyList(),

    val isRecording: Boolean = false,
    val recordedDurationSeconds: Int = 0,
    val exportCsvContent: String? = null,
    
    val historyFiles: List<File> = emptyList(),
    val selectedHistory: HistoryRecording? = null,
    
    val sampleInterval: SampleInterval = SampleInterval.NORMAL
)

class PerformanceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PerformanceUiState())
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    val sampleIntervalMs = MutableStateFlow(SampleInterval.NORMAL)

    private var rootJob: Job? = null

    private var rootBinder: ICpuBinder? = null
    private val maxHistoryPoints = 30
    private val fpsHistory = ArrayDeque<Float>()
    private val gpuHistory = ArrayDeque<Float>()
    private val cpuHistories = HashMap<Int, ArrayDeque<Float>>()

    // 网络统计中间变量
    private var lastNetTimeMs: Long = 0L
    private var lastCellRaw = RawNetStats()
    private var lastWlanRaw = RawNetStats()
    
    private val cellRxSpeedHistory = ArrayDeque<Float>()
    private val wlanRxSpeedHistory = ArrayDeque<Float>()

    private var lastDiskTimeMs: Long = 0L
    private var lastDiskReadSectors: Long = 0L
    private var lastDiskWriteSectors: Long = 0L
    private val romReadHistory = ArrayDeque<Float>()
    private val romWriteHistory = ArrayDeque<Float>()

    // 录制会话基准（Base Reference）
    private var recBaseCellRaw: RawNetStats? = null
    private var recBaseWlanRaw: RawNetStats? = null

    // 录制相关私有变量
    private val recordingBuffer = mutableListOf<PerformanceSample>()
    private var recordingStartTimeMs: Long = 0L

    private val batteryCurrentHistory = ArrayDeque<Float>()

    // 静态常量
    private val SPACES_REGEX = Regex("\\s+")

    private val ramAvailHistory = ArrayDeque<Float>()
    private val zramAvailHistory = ArrayDeque<Float>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootBinder = ICpuBinder.Stub.asInterface(service)
            _uiState.update { it.copy(isRootConnected = true) }

            // Root 协程监控
            startPollingHardware()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootBinder = null
            _uiState.update { it.copy(isRootConnected = false) }
        }
    }

    fun setSampleInterval(interval: SampleInterval) {
        sampleIntervalMs.value = interval
        _uiState.update { it.copy(sampleInterval = interval) }
    }

    fun initAndBind(context: Context) {
        // 确保这里仅初始化 RootService，避免权限问题
        if (rootBinder == null) {
            // 在首次 bind 之前，显式配置 MainShell 使用 Mount Master 模式
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(10)
            )

            val intent = Intent(context, GhzRootService::class.java)
            RootService.bind(intent, serviceConnection)
        }
    }

    fun unRootService() {
        rootJob?.cancel()
        try {
            RootService.unbind(serviceConnection)
        } catch (e: Exception) { }
    }

    private fun readProcNetDev(): Pair<RawNetStats, RawNetStats> {
        val binder = rootBinder ?: return Pair(RawNetStats(), RawNetStats())
        return try {
            val stats = binder.networkStats
            if (stats != null && stats.size >= 12) {
                Pair(
                    RawNetStats(
                        rxBytes = stats[0],
                        txBytes = stats[1],
                        rxPackets = stats[2],
                        txPackets = stats[3],
                        rxErrorsDrops = stats[4],
                        txErrorsDrops = stats[5]
                    ),
                    RawNetStats(
                        rxBytes = stats[6],
                        txBytes = stats[7],
                        rxPackets = stats[8],
                        txPackets = stats[9],
                        rxErrorsDrops = stats[10],
                        txErrorsDrops = stats[11]
                    )
                )
            } else {
                Pair(RawNetStats(), RawNetStats())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(RawNetStats(), RawNetStats())
        }
    }

    private data class MemoryStats(
        val ramTotalGb: Float,
        val ramAvailGb: Float,
        val ramUsedGb: Float,
        val zramTotalGb: Float,
        val zramAvailGb: Float,
        val zramUsedGb: Float
    )

    private fun getMemoryStats(): MemoryStats {
        var memTotalKb = 0L
        var memAvailKb = 0L
        var swapTotalKb = 0L
        var swapFreeKb = 0L

        try {
            File("/proc/meminfo").forEachLine { line ->
             //   val parts = line.split("\\s+".toRegex())
                val parts = line.split(SPACES_REGEX)
                if (parts.size >= 2) {
                    when (parts[0]) {
                        "MemTotal:" -> memTotalKb = parts[1].toLongOrNull() ?: 0L
                        "MemAvailable:" -> memAvailKb = parts[1].toLongOrNull() ?: 0L
                        "SwapTotal:" -> swapTotalKb = parts[1].toLongOrNull() ?: 0L
                        "SwapFree:" -> swapFreeKb = parts[1].toLongOrNull() ?: 0L
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val ramTotal = memTotalKb / (1024f * 1024f)
        val ramAvail = memAvailKb / (1024f * 1024f)
        val ramUsed = (ramTotal - ramAvail).coerceAtLeast(0f)

        val zramTotal = swapTotalKb / (1024f * 1024f)
        val zramAvail = swapFreeKb / (1024f * 1024f)
        val zramUsed = (zramTotal - zramAvail).coerceAtLeast(0f)

        return MemoryStats(
            ramTotalGb = ramTotal,
            ramAvailGb = ramAvail,
            ramUsedGb = ramUsed,
            zramTotalGb = zramTotal,
            zramAvailGb = zramAvail,
            zramUsedGb = zramUsed
        )
    }

    // 手动点击：开始录制
    fun startRecording(context: Context) {
        // 持有 WakeLock
        val intent = Intent(context, AdbSessionService::class.java).apply {
            action = AdbSessionService.ACTION_START_RECORDING
        }
        context.startService(intent)

        recordingStartTimeMs = System.currentTimeMillis()
        val (curCell, curWlan) = readProcNetDev()
        recBaseCellRaw = curCell
        recBaseWlanRaw = curWlan

        synchronized(recordingBuffer) {
            recordingBuffer.clear()
        }
        _uiState.update {
            it.copy(
                isRecording = true,
                recordedDurationSeconds = 0,
                exportCsvContent = null
            )
        }
    }

    fun clearExportData() {
        _uiState.update { it.copy(exportCsvContent = null) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startPollingHardware() {
        rootJob?.cancel()
        rootJob = viewModelScope.launch(Dispatchers.IO) {
            sampleIntervalMs
                .flatMapLatest { interval ->
                    flow {
                        while (rootBinder != null && isActive) {
                            emit(Unit)
                            delay(interval.millis)
                        }
                    }
                }
                .collect {
                    val binder = rootBinder ?: return@collect

                    try {
                        val nowMs = System.currentTimeMillis()

                        // 1. CPU
                        val cpuFreqs = binder.cpuCurrentFreqs
                        val cpuMetrics = cpuFreqs.mapIndexed { index, curGhz ->
                            val limits = binder.getCpuCoreLimits(index)
                            val deque = cpuHistories.getOrPut(index) { ArrayDeque() }
                            pushHistory(deque, curGhz)

                            CpuCoreMetric(
                                coreIndex = index,
                                curFreqGhz = curGhz,
                                minFreqGhz = limits[0],
                                maxFreqGhz = limits[1],
                                history = deque.toList()
                            )
                        }

                        // 2. GPU
                        val gpuData = binder.gpuMetrics
                        pushHistory(gpuHistory, gpuData[0])
                        val gpuMetric = GpuMetric(
                            curFreqGhz = gpuData[0],
                            minFreqGhz = gpuData[1],
                            maxFreqGhz = gpuData[2],
                            utilizationPercent = gpuData[3],
                            history = gpuHistory.toList()
                        )

                        // 3. Battery & Memory
                        val batBundle = try { binder.batteryMetrics } catch (e: Exception) { null }
                        val temp = batBundle?.getFloat("battery_temp") ?: 0f
                        val batLevel = batBundle?.getInt("battery_level") ?: 0
                        val batCurrentMa = batBundle?.getFloat("battery_current_ma") ?: 0f
                        val batVoltageMv = batBundle?.getFloat("battery_voltage_mv") ?: 0f
                        val batPowerW = batBundle?.getFloat("battery_power_w") ?: 0f
                        val batStatus = batBundle?.getString("battery_status") ?: "Unknown"
                        val batChargeType = batBundle?.getString("battery_charge_type") ?: "Unknown"
                        val batHealth = batBundle?.getString("battery_health") ?: "Unknown"
                        val batCycleCount = batBundle?.getInt("battery_cycle_count") ?: 0
                        val batFullMah = batBundle?.getFloat("battery_charge_full_mah") ?: 0f
                        val batFullDesignMah = batBundle?.getFloat("battery_charge_full_design_mah") ?: 0f
                        val batSohPercent = batBundle?.getFloat("battery_soh_percent") ?: 0f

                        val memStats = getMemoryStats()

                        pushHistory(batteryCurrentHistory, batCurrentMa)

                        // 4. Display & FPS
                        val activeHz = try { binder.activeRefreshRate } catch (e: Exception) { 60f }
                        val currentRes = try { binder.currentResolution ?: "" } catch (e: Exception) { "" }
                        val supportedModes = try { binder.supportedDisplayModes ?: emptyList() } catch (e: Exception) { emptyList() }

                        val hwFps = try { binder.measuredFps } catch (e: Exception) { 0f }
                        val realFps = if (hwFps > 0f) hwFps else 0f

                        pushHistory(fpsHistory, realFps)
                        pushHistory(ramAvailHistory, memStats.ramAvailGb)
                        pushHistory(zramAvailHistory, memStats.zramAvailGb)

                        // 5. Network (/proc/net/dev)
                        val (curCellRaw, curWlanRaw) = readProcNetDev()
                        val timeDeltaSec = if (lastNetTimeMs > 0) ((nowMs - lastNetTimeMs) / 1000f).coerceAtLeast(0.1f) else 1.0f

                        val cellDelta = curCellRaw - lastCellRaw
                        val wlanDelta = curWlanRaw - lastWlanRaw

                        val cellRxSpeedKbps = (cellDelta.rxBytes / 1024f) / timeDeltaSec
                        val cellTxSpeedKbps = (cellDelta.txBytes / 1024f) / timeDeltaSec
                        val wlanRxSpeedKbps = (wlanDelta.rxBytes / 1024f) / timeDeltaSec
                        val wlanTxSpeedKbps = (wlanDelta.txBytes / 1024f) / timeDeltaSec

                        pushHistory(cellRxSpeedHistory, cellRxSpeedKbps)
                        pushHistory(wlanRxSpeedHistory, wlanRxSpeedKbps)

                        lastNetTimeMs = nowMs
                        lastCellRaw = curCellRaw
                        lastWlanRaw = curWlanRaw

                        // 判定是否处在录制状态：如果是在录制状态，流量总量与丢包率仅计入录制阶段的 Delta 数据
                        val isRecordingActive = _uiState.value.isRecording
                    
                        val activeCellStats = if (isRecordingActive && recBaseCellRaw != null) curCellRaw - recBaseCellRaw!! else curCellRaw
                        val activeWlanStats = if (isRecordingActive && recBaseWlanRaw != null) curWlanRaw - recBaseWlanRaw!! else curWlanRaw

                        val cellMetric = NetworkMetric(
                            rxSpeedKbps = cellRxSpeedKbps,
                            txSpeedKbps = cellTxSpeedKbps,
                            rxTotalMb = activeCellStats.rxBytes / (1024f * 1024f),
                            txTotalMb = activeCellStats.txBytes / (1024f * 1024f),
                            lossRatePercent = activeCellStats.calcPacketLossRate(),
                            rxSpeedHistory = cellRxSpeedHistory.toList()
                        )

                        val wlanMetric = NetworkMetric(
                            rxSpeedKbps = wlanRxSpeedKbps,
                            txSpeedKbps = wlanTxSpeedKbps,
                            rxTotalMb = activeWlanStats.rxBytes / (1024f * 1024f),
                            txTotalMb = activeWlanStats.txBytes / (1024f * 1024f),
                            lossRatePercent = activeWlanStats.calcPacketLossRate(),
                            rxSpeedHistory = wlanRxSpeedHistory.toList()
                        )

                        val rawDiskStats = binder.diskStats // 返回 [readSectors, writeSectors, totalBytes, availBytes]
                    
                        var romMetric = RomMetric()
                        if (rawDiskStats != null && rawDiskStats.size >= 4) {
                            val curReadSectors = rawDiskStats[0]
                            val curWriteSectors = rawDiskStats[1]
                            val totalBytes = rawDiskStats[2]
                            val availBytes = rawDiskStats[3]

                            val romTotalGb = totalBytes / (1024f * 1024f * 1024f)
                            val romAvailGb = availBytes / (1024f * 1024f * 1024f)

                            val timeDeltaSec = if (lastDiskTimeMs > 0) {
                                ((nowMs - lastDiskTimeMs) / 1000f).coerceAtLeast(0.1f)
                            } else 1.0f

                            val deltaReadSectors = (curReadSectors - lastDiskReadSectors).coerceAtLeast(0L)
                            val deltaWriteSectors = (curWriteSectors - lastDiskWriteSectors).coerceAtLeast(0L)

                            // Linux 内核规范中，/proc/diskstats 1 个扇区固定按 512 字节 (0.5 KB) 计算
                            val readSpeedMb = if (lastDiskTimeMs > 0) ((deltaReadSectors * 512f) / (1024f * 1024f)) / timeDeltaSec else 0f
                            val writeSpeedMb = if (lastDiskTimeMs > 0) ((deltaWriteSectors * 512f) / (1024f * 1024f)) / timeDeltaSec else 0f

                            pushHistory(romReadHistory, readSpeedMb)
                            pushHistory(romWriteHistory, writeSpeedMb)

                            lastDiskTimeMs = nowMs
                            lastDiskReadSectors = curReadSectors
                            lastDiskWriteSectors = curWriteSectors

                            romMetric = RomMetric(
                                totalGb = romTotalGb,
                                availGb = romAvailGb,
                                readSpeedMb = readSpeedMb,
                                writeSpeedMb = writeSpeedMb,
                                readHistory = romReadHistory.toList(),
                                writeHistory = romWriteHistory.toList()
                            )
                        }

                        var durationSec = 0
                        if (isRecordingActive) {
                            durationSec = ((nowMs - recordingStartTimeMs) / 1000).toInt()
                            val cpuHwLimits = cpuMetrics.map { Pair(it.minFreqGhz, it.maxFreqGhz) }

                            synchronized(recordingBuffer) {
                                recordingBuffer.add(
                                    PerformanceSample(
                                        timestampMs = nowMs,
                                        fps = realFps,
                                        refreshRate = activeHz,
                                        batteryTemp = temp,
                                        batteryLevel = batLevel,
                                        batteryCurrentMa = batCurrentMa,
                                        batteryVoltageMv = batVoltageMv,
                                        batteryPowerW = batPowerW,
                                        batteryStatus = batStatus,
                                        batteryChargeType = batChargeType,
                                        batteryHealth = batHealth,
                                        batteryCycleCount = batCycleCount,
                                        batteryFullMah = batFullMah,
                                        batteryFullDesignMah = batFullDesignMah,
                                        batterySohPercent = batSohPercent,
                                        ramTotalGb = memStats.ramTotalGb,
                                        ramAvailGb = memStats.ramAvailGb,
                                        zramTotalGb = memStats.zramTotalGb,
                                        zramAvailGb = memStats.zramAvailGb,
                                        romReadSpeedMb = romMetric.readSpeedMb,
                                        romWriteSpeedMb = romMetric.writeSpeedMb,
                                        gpuFreqGhz = gpuData[0],
                                        gpuLoadPercent = gpuData[3],
                                        gpuMinFreqGhz = gpuData[1],
                                        gpuMaxFreqGhz = gpuData[2],
                                        cpuFreqsGhz = cpuFreqs.toList(),
                                        cpuHwLimitsGhz = cpuHwLimits,
                                        cellRxSpeedKbps = cellRxSpeedKbps,
                                        cellTxSpeedKbps = cellTxSpeedKbps,
                                        cellTotalMb = cellMetric.totalMb,
                                        cellLossRate = cellMetric.lossRatePercent,
                                        wlanRxSpeedKbps = wlanRxSpeedKbps,
                                        wlanTxSpeedKbps = wlanTxSpeedKbps,
                                        wlanTotalMb = wlanMetric.totalMb,
                                        wlanLossRate = wlanMetric.lossRatePercent
                                    )
                                )
                            }
                        }

                        _uiState.update { state ->
                            state.copy(
                                isRootConnected = true,
                                batteryTemp = temp,
                                batteryLevel = batLevel,
                                batteryCurrentMa = batCurrentMa,
                                batteryVoltageMv = batVoltageMv,
                                batteryPowerW = batPowerW,
                                batteryStatus = batStatus,
                                batteryChargeType = batChargeType,
                                batteryHealth = batHealth,
                                batteryCycleCount = batCycleCount,
                                batteryFullMah = batFullMah,
                                batteryFullDesignMah = batFullDesignMah,
                                batterySohPercent = batSohPercent,
                                batteryCurrentHistory = batteryCurrentHistory.toList(),
                                renderFps = realFps,
                                refreshRateHz = activeHz,
                                currentResolution = currentRes,
                                supportedDisplayModes = supportedModes,
                                fpsHistory = fpsHistory.toList(),
                                gpuMetric = gpuMetric,
                                cpuCores = cpuMetrics,
                                romMetric = romMetric,
                                ramTotalGb = memStats.ramTotalGb,
                                ramAvailGb = memStats.ramAvailGb,
                                ramAvailHistory = ramAvailHistory.toList(),
                                zramTotalGb = memStats.zramTotalGb,
                                zramAvailGb = memStats.zramAvailGb,
                                zramAvailHistory = zramAvailHistory.toList(),
                                cellMetric = cellMetric,
                                wlanMetric = wlanMetric,
                                recordedDurationSeconds = if (isRecordingActive) durationSec else 0
                            )
                        }
                    } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun generateCsvData(): String {
        val samples = synchronized(recordingBuffer) { recordingBuffer.toList() }
        if (samples.isEmpty()) return ""

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()

        sb.append("Time,Timestamp(ms),FPS,RefreshRate(Hz),BatteryTemp(°C),BatteryLevel(%),BatteryCurrent(mA),BatteryVoltage(mV),BatteryPower(W),BatteryStatus,BatteryChargeType,BatteryHealth,BatteryCycleCount,BatteryFull(mAh),BatteryFullDesign(mAh),BatterySoH(%),RAM_Avail(GB),RAM_Total(GB),ZRAM_Avail(GB),ZRAM_Total(GB),ROM_Read(MB/s),ROM_Write(MB/s),GPU_Freq(GHz),GPU_Load(%),GPU_Min(GHz),GPU_Max(GHz),Cell_RxSpeed(KB/s),Cell_TxSpeed(KB/s),Cell_Total(MB),Cell_Loss(%),Wlan_RxSpeed(KB/s),Wlan_TxSpeed(KB/s),Wlan_Total(MB),Wlan_Loss(%)")

        val maxCpuCount = samples.maxOfOrNull { it.cpuFreqsGhz.size } ?: 0
        for (i in 0 until maxCpuCount) {
            sb.append(",CPU${i}_Cur(GHz),CPU${i}_Min(GHz),CPU${i}_Max(GHz)")
        }
        sb.append("\n")

        for (sample in samples) {
            val timeStr = dateFormat.format(Date(sample.timestampMs))
            sb.append(String.format(Locale.US, "%s,%d,%.2f,%.2f,%.1f,%d,%.1f,%.1f,%.2f,%s,%s,%s,%d,%.1f,%.1f,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.3f,%.1f,%.3f,%.3f,%.1f,%.1f,%.2f,%.2f,%.1f,%.1f,%.2f,%.2f",
                timeStr, sample.timestampMs, sample.fps, sample.refreshRate,
                sample.batteryTemp, sample.batteryLevel, sample.batteryCurrentMa,
                sample.batteryVoltageMv, sample.batteryPowerW, sample.batteryStatus,
                sample.batteryChargeType, sample.batteryHealth, sample.batteryCycleCount,
                sample.batteryFullMah, sample.batteryFullDesignMah, sample.batterySohPercent,
                sample.ramAvailGb, sample.ramTotalGb, sample.zramAvailGb, sample.zramTotalGb,
                sample.romReadSpeedMb, sample.romWriteSpeedMb,
                sample.gpuFreqGhz, sample.gpuLoadPercent, sample.gpuMinFreqGhz, sample.gpuMaxFreqGhz,
                sample.cellRxSpeedKbps, sample.cellTxSpeedKbps, sample.cellTotalMb, sample.cellLossRate,
                sample.wlanRxSpeedKbps, sample.wlanTxSpeedKbps, sample.wlanTotalMb, sample.wlanLossRate
            ))

            for (i in 0 until maxCpuCount) {
                val cur = sample.cpuFreqsGhz.getOrNull(i) ?: 0f
                val limits = sample.cpuHwLimitsGhz.getOrNull(i) ?: Pair(0f, 0f)
                sb.append(String.format(Locale.US, ",%.3f,%.3f,%.3f", cur, limits.first, limits.second))
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    private fun pushHistory(deque: ArrayDeque<Float>, value: Float) {
        if (deque.size >= maxHistoryPoints) deque.removeFirst()
        deque.addLast(value)
    }

    override fun onCleared() {
        rootJob?.cancel()
        try {
            RootService.unbind(serviceConnection)
        } catch (e: Exception) { }
    }
    
    private fun getCpuFolder(context: Context): File {
        val cpuFolder = File(context.getExternalFilesDir(null), "cpu")
        if (!cpuFolder.exists()) {
            cpuFolder.mkdirs()
        }
        return cpuFolder
    }

    // 刷新已保存的历史录制文件列表
    fun refreshSavedFiles(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = getCpuFolder(context)
            val files = folder.listFiles { file -> file.extension.lowercase() == "csv" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            _uiState.update { it.copy(historyFiles = files) }
        }
    }

    // 停止录制并自动保存到 cpu 目录
    fun stopRecordingAndSave(context: Context): File? {
        // 释放 WakeLock
        val intent = Intent(context, AdbSessionService::class.java).apply {
            action = AdbSessionService.ACTION_STOP_RECORDING
        }
        context.startService(intent)

        val csv = generateCsvData()
        _uiState.update { 
            it.copy(
                isRecording = false, 
                recordedDurationSeconds = 0, 
                exportCsvContent = csv 
            ) 
        }

        if (csv.isEmpty()) return null

        return try {
            val folder = getCpuFolder(context)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(folder, "cpu_record_$timeStamp.csv")
            file.writeText(csv)
            
            refreshSavedFiles(context)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // 删除指定的历史文件
    fun deleteHistoryFile(context: Context, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            if (file.exists()) {
                file.delete()
            }
            if (_uiState.value.selectedHistory?.file == file) {
                _uiState.update { it.copy(selectedHistory = null) }
            }
            refreshSavedFiles(context)
        }
    }

    // 从 CSV 文件解析出离线采样数据并用于图形展示
    fun loadHistoryFromFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = parseCsvFile(file)
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(file.lastModified()))
            
            val record = HistoryRecording(
                file = file,
                formattedDate = dateStr,
                durationSeconds = samples.size,
                samples = samples
            )

            _uiState.update { it.copy(selectedHistory = record) }
        }
    }

    // 清除选中的历史解析数据（返回列表）
    fun clearSelectedHistory() {
        _uiState.update { it.copy(selectedHistory = null) }
    }

    private fun parseCsvFile(file: File): List<PerformanceSample> {
        val samples = mutableListOf<PerformanceSample>()
        if (!file.exists()) return samples

        try {
            val lines = file.readLines()
            if (lines.size <= 1) return samples

            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val tokens = line.split(",")

                if (tokens.size >= 34) {
                    val timestampMs = tokens[1].toLongOrNull() ?: 0L
                    val fps = tokens[2].toFloatOrNull() ?: 0f
                    val refreshRate = tokens[3].toFloatOrNull() ?: 60f

                    val batteryTemp = tokens[4].toFloatOrNull() ?: 0f
                    val batteryLevel = tokens[5].toIntOrNull() ?: 0
                    val batteryCurrentMa = tokens[6].toFloatOrNull() ?: 0f
                    val batteryVoltageMv = tokens[7].toFloatOrNull() ?: 0f
                    val batteryPowerW = tokens[8].toFloatOrNull() ?: 0f
                    val batteryStatus = tokens.getOrNull(9) ?: "Unknown"
                    val batteryChargeType = tokens.getOrNull(10) ?: "Unknown"
                    val batteryHealth = tokens.getOrNull(11) ?: "Unknown"
                    val batteryCycleCount = tokens[12].toIntOrNull() ?: 0
                    val batteryFullMah = tokens[13].toFloatOrNull() ?: 0f
                    val batteryFullDesignMah = tokens[14].toFloatOrNull() ?: 0f
                    val batterySohPercent = tokens[15].toFloatOrNull() ?: 0f

                    val ramAvailGb = tokens[16].toFloatOrNull() ?: 0f
                    val ramTotalGb = tokens[17].toFloatOrNull() ?: 0f
                    val zramAvailGb = tokens[18].toFloatOrNull() ?: 0f
                    val zramTotalGb = tokens[19].toFloatOrNull() ?: 0f
                    val romReadSpeed = tokens[20].toFloatOrNull() ?: 0f
                    val romWriteSpeed = tokens[21].toFloatOrNull() ?: 0f
                    val gpuFreqGhz = tokens[22].toFloatOrNull() ?: 0f
                    val gpuLoadPercent = tokens[23].toFloatOrNull() ?: 0f
                    val gpuMinFreqGhz = tokens[24].toFloatOrNull() ?: 0f
                    val gpuMaxFreqGhz = tokens[25].toFloatOrNull() ?: 0f

                    val cellRxSpeed = tokens[26].toFloatOrNull() ?: 0f
                    val cellTxSpeed = tokens[27].toFloatOrNull() ?: 0f
                    val cellTotalMb = tokens[28].toFloatOrNull() ?: 0f
                    val cellLoss = tokens[29].toFloatOrNull() ?: 0f
                    val wlanRxSpeed = tokens[30].toFloatOrNull() ?: 0f
                    val wlanTxSpeed = tokens[31].toFloatOrNull() ?: 0f
                    val wlanTotalMb = tokens[32].toFloatOrNull() ?: 0f
                    val wlanLoss = tokens[33].toFloatOrNull() ?: 0f

                    val cpuFreqs = mutableListOf<Float>()
                    val cpuHwLimits = mutableListOf<Pair<Float, Float>>()

                    var idx = 34
                    while (idx + 2 < tokens.size) {
                        val cur = tokens[idx].toFloatOrNull() ?: 0f
                        val min = tokens[idx + 1].toFloatOrNull() ?: 0f
                        val max = tokens[idx + 2].toFloatOrNull() ?: 0f

                        cpuFreqs.add(cur)
                        cpuHwLimits.add(Pair(min, max))
                        idx += 3
                    }

                    samples.add(
                        PerformanceSample(
                            timestampMs = timestampMs,
                            fps = fps,
                            refreshRate = refreshRate,
                            batteryTemp = batteryTemp,
                            batteryLevel = batteryLevel,
                            batteryCurrentMa = batteryCurrentMa,
                            batteryVoltageMv = batteryVoltageMv,
                            batteryPowerW = batteryPowerW,
                            batteryStatus = batteryStatus,
                            batteryChargeType = batteryChargeType,
                            batteryHealth = batteryHealth,
                            batteryCycleCount = batteryCycleCount,
                            batteryFullMah = batteryFullMah,
                            batteryFullDesignMah = batteryFullDesignMah,
                            batterySohPercent = batterySohPercent,
                            ramAvailGb = ramAvailGb,
                            ramTotalGb = ramTotalGb,
                            zramAvailGb = zramAvailGb,
                            zramTotalGb = zramTotalGb,
                            romReadSpeedMb = romReadSpeed,
                            romWriteSpeedMb = romWriteSpeed,
                            gpuFreqGhz = gpuFreqGhz,
                            gpuLoadPercent = gpuLoadPercent,
                            gpuMinFreqGhz = gpuMinFreqGhz,
                            gpuMaxFreqGhz = gpuMaxFreqGhz,
                            cpuFreqsGhz = cpuFreqs,
                            cpuHwLimitsGhz = cpuHwLimits,
                            cellRxSpeedKbps = cellRxSpeed,
                            cellTxSpeedKbps = cellTxSpeed,
                            cellTotalMb = cellTotalMb,
                            cellLossRate = cellLoss,
                            wlanRxSpeedKbps = wlanRxSpeed,
                            wlanTxSpeedKbps = wlanTxSpeed,
                            wlanTotalMb = wlanTotalMb,
                            wlanLossRate = wlanLoss
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return samples
    }
}

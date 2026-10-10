package com.adb.kitty.ui.it.cpu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

val CoreColors = listOf(
    Color(0xFF2196F3), Color(0xFF03A9F4), Color(0xFF00BCD4), Color(0xFF009688),
    Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFFE91E63)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompletePerformanceMonitorBottomSheet(
    uiState: PerformanceUiState,
    onIntervalSelected: (SampleInterval) -> Unit = {},
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onExportCsv: (csvContent: String) -> Unit,
    onDismissRequest: () -> Unit,
    onUnbinRoot: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 顶栏：标题 + 录制按钮 + ROOT 状态标识
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Qualcomm 硬件性能监控",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 仅在手动点击时触发录制/停止
                    Button(
                        onClick = {
                            if (uiState.isRecording) onStopRecording() else onStartRecording()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isRecording) Color.Red else MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = if (uiState.isRecording) "⏹ 停止 (${uiState.recordedDurationSeconds}s)" else "🔴 开始录制",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            onUnbinRoot()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = "解绑 Root",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("协程刷新频率:", fontSize = 11.sp, color = Color.Gray)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(SampleInterval.entries) { interval ->
                        FilterChip(
                            selected = (uiState.sampleInterval == interval),
                            onClick = { onIntervalSelected(interval) },
                            label = { Text(interval.label, fontSize = 10.sp) },
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
            }

            // 录制停止后弹出的保存 CSV 提示卡片
            AnimatedVisibility(visible = uiState.exportCsvContent != null) {
                uiState.exportCsvContent?.let { csv ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("✅ 数据录制已完成", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("采样已停止，准备保存为电子表格", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            }
                            Button(
                                onClick = { onExportCsv(csv) },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Text("💾 导出 CSV", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 1. 全局系统指标卡片 (FPS / 刷新率 / 电池温度)
            SystemSummaryCard(state = uiState)

            NetworkStatusCard(
                cell = uiState.cellMetric,
                wlan = uiState.wlanMetric,
                isRecording = uiState.isRecording
            )

            MemoryStatusCard(
                ramTotalGb = uiState.ramTotalGb,
                ramAvailGb = uiState.ramAvailGb,
                ramHistory = uiState.ramAvailHistory,
                zramTotalGb = uiState.zramTotalGb,
                zramAvailGb = uiState.zramAvailGb,
                zramHistory = uiState.zramAvailHistory
            )

            RomStatusCard(rom = uiState.romMetric)

            BatteryStatusCard(
                batteryLevel = uiState.batteryLevel,
                batteryTemp = uiState.batteryTemp,
                batteryCurrentMa = uiState.batteryCurrentMa,
                batteryVoltageMv = uiState.batteryVoltageMv,
                batteryStatus = uiState.batteryStatus,
                batteryChargeType = uiState.batteryChargeType,
                batteryHealth = uiState.batteryHealth,
                batteryCycleCount = uiState.batteryCycleCount,
                batteryFullMah = uiState.batteryFullMah,
                batteryFullDesignMah = uiState.batteryFullDesignMah,
                batterySohPercent = uiState.batterySohPercent,
                historyData = uiState.batteryCurrentHistory
            )

            // 2. 屏幕显示参数卡片 (屏幕 API 读取当前分辨率与支持模式)
            DisplayInfoCard(uiState = uiState)

            // 3. GPU 指标卡片
            GpuMetricCard(gpu = uiState.gpuMetric)

            // 4. CPU 核心集群网格
            Text(
                text = "CPU 核心数 (${uiState.cpuCores.size} 核 / HW)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            uiState.cpuCores.forEach { core ->
                SingleCoreCard(
                    core = core,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun RomStatusCard(rom: RomMetric) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. ROM 存储空间部分
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("💾 ROM 内部存储 (/proc/diskstats)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(
                            Locale.US,
                            "已用: %.2f GB / 总量: %.2f GB (可用: %.2f GB)",
                            rom.usedGb, rom.totalGb, rom.availGb
                        ),
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.1f%%", rom.usedPercent),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9C27B0)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // 2. 磁盘读写速度部分
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("磁盘 I/O 实时速率", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = String.format(Locale.US, "📖 读: %.2f MB/s  ✍️ 写: %.2f MB/s", rom.readSpeedMb, rom.writeSpeedMb),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF9800)
                )
            }

            MetricLineChart(
                data = rom.readHistory,
                maxVal = (rom.readHistory.maxOrNull() ?: 10f).coerceAtLeast(5f),
                lineColor = Color(0xFFFF9800),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            )
        }
    }
}

@Composable
fun NetworkStatusCard(
    cell: NetworkMetric,
    wlan: NetworkMetric,
    isRecording: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🌐 网络状态监控 (/proc/net/dev)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isRecording) {
                    Text(
                        text = "● 录制期间增量统计中",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                }
            }

            // WLAN 状态区
            NetworkSectionItem(
                title = "📶 WLAN 网络",
                metric = wlan,
                lineColor = Color(0xFF00BCD4)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // 蜂窝网络状态区
            NetworkSectionItem(
                title = "📱 蜂窝移动网络",
                metric = cell,
                lineColor = Color(0xFFE91E63)
            )
        }
    }
}

fun formatMb(mb: Float): String {
    return if (mb >= 1024f) {
        String.format(Locale.US, "%.2f GB", mb / 1024f)
    } else {
        String.format(Locale.US, "%.2f MB", mb)
    }
}

@Composable
private fun NetworkSectionItem(
    title: String,
    metric: NetworkMetric,
    lineColor: Color
) {
    val maxRxKbps = metric.rxSpeedHistory.maxOrNull() ?: 0f
    val (chartData, chartMaxVal) = when {
        maxRxKbps >= 1024f * 1024f -> { // GB/s
            val scale = 1024f * 1024f
            metric.rxSpeedHistory.map { it / scale } to (maxRxKbps / scale).coerceAtLeast(0.1f)
        }
        maxRxKbps >= 1024f -> { // MB/s
            val scale = 1024f
            metric.rxSpeedHistory.map { it / scale } to (maxRxKbps / scale).coerceAtLeast(0.5f)
        }
        else -> { // KB/s
            metric.rxSpeedHistory to maxRxKbps.coerceAtLeast(50f)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            // 2. 实时速率文本自动换算单位 (KB/s, MB/s, GB/s)
            Text(
                text = "↓ ${formatKbps(metric.rxSpeedKbps)}  ↑ ${formatKbps(metric.txSpeedKbps)}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = lineColor
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val totalStr = formatMb(metric.totalMb)
            val rxStr = formatMb(metric.rxTotalMb)
            val txStr = formatMb(metric.txTotalMb)

            Text(
                text = "总量: $totalStr (↓$rxStr/↑$txStr)",
                fontSize = 10.sp,
                color = Color.Gray
            )
            Text(
                text = String.format(Locale.US, "丢包率: %.2f%%", metric.lossRatePercent),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = if (metric.lossRatePercent > 1f) Color.Red else Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        MetricLineChart(
            data = chartData,
            maxVal = chartMaxVal,
            lineColor = lineColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
    }
}

@Composable
fun MemoryStatusCard(
    ramTotalGb: Float,
    ramAvailGb: Float,
    ramHistory: List<Float>,
    zramTotalGb: Float,
    zramAvailGb: Float,
    zramHistory: List<Float>
) {
    val ramUsedGb = (ramTotalGb - ramAvailGb).coerceAtLeast(0f)
    val zramUsedGb = (zramTotalGb - zramAvailGb).coerceAtLeast(0f)
    val ramUsedPercent = if (ramTotalGb > 0) (ramUsedGb / ramTotalGb) * 100f else 0f
    val zramUsedPercent = if (zramTotalGb > 0) (zramUsedGb / zramTotalGb) * 100f else 0f

    val minRam = ramHistory.minOrNull() ?: 0f
    val maxRam = ramHistory.maxOrNull() ?: 0f
    val relativeRamHistory = ramHistory.map { it - minRam }
    val ramDeltaMax = (maxRam - minRam).coerceAtLeast(0.3f)

    val minZram = zramHistory.minOrNull() ?: 0f
    val maxZram = zramHistory.maxOrNull() ?: 0f
    val relativeZramHistory = zramHistory.map { it - minZram }
    val zramDeltaMax = (maxZram - minZram).coerceAtLeast(0.3f)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // RAM 物理内存
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("RAM 物理内存 (/proc/meminfo)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(Locale.US, "已用: %.3f GB / 总量: %.3f GB (可用: %.3f GB)", ramUsedGb, ramTotalGb, ramAvailGb),
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.1f%%", ramUsedPercent),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2196F3)
                )
            }
            MetricLineChart(
                data = relativeRamHistory,
                maxVal = ramDeltaMax,
                lineColor = Color(0xFF2196F3),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // ZRAM 虚拟内存
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ZRAM 虚拟内存 (/proc/meminfo)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(Locale.US, "已用: %.3f GB / 总量: %.3f GB (可用: %.3f GB)", zramUsedGb, zramTotalGb, zramAvailGb),
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.1f%%", zramUsedPercent),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00BCD4)
                )
            }
            MetricLineChart(
                data = relativeZramHistory,
                maxVal = zramDeltaMax,
                lineColor = Color(0xFF00BCD4),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            )
        }
    }
}

@Composable
fun BatteryStatusCard(
    batteryLevel: Int,
    batteryTemp: Float,
    batteryCurrentMa: Float,
    batteryVoltageMv: Float,
    batteryStatus: String,
    batteryChargeType: String = "",
    batteryHealth: String = "",
    batteryCycleCount: Int = 0,
    batteryFullMah: Float = 0f,
    batteryFullDesignMah: Float = 0f,
    batterySohPercent: Float = 0f,
    historyData: List<Float>
) {
    val isCharging = batteryStatus.contains("Charging", ignoreCase = true)
    val isFull = batteryStatus.contains("Full", ignoreCase = true)

    val statusColor = when {
        isFull -> Color(0xFF2196F3)
        isCharging -> Color(0xFF4CAF50)
        batteryTemp > 45f -> Color(0xFFFF5252)
        else -> Color(0xFFFF9800)
    }

    // 计算实时功率 W = V * A
    val displayCurrentMa = abs(batteryCurrentMa)
    val powerWatts = (batteryVoltageMv / 1000f) * (displayCurrentMa / 1000f)

    val invalidTypes = setOf("Unknown", "N/A")
    val showChargeType = isCharging && batteryChargeType !in invalidTypes

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. 顶栏：标题 + 充放电状态与健康度 Pill 标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔋 电池功耗与健康度", fontSize = 12.sp, fontWeight = FontWeight.Bold)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (batteryHealth.isNotEmpty() && batteryHealth != "Unknown") {
                        Text(
                            text = batteryHealth,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00BCD4),
                            modifier = Modifier
                                .background(Color(0xFF00BCD4).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = batteryStatus.ifEmpty { "Unknown" },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier
                            .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )

                    if (showChargeType) {
                        Text(
                            text = batteryChargeType,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800),
                            modifier = Modifier
                            .background(Color(0xFFFF9800).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // 2. 电量百分比与可视化进度条
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("剩余电量", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        text = "$batteryLevel%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            batteryLevel <= 15 -> Color(0xFFFF5252)
                            batteryLevel <= 30 -> Color(0xFFFF9800)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                LinearProgressIndicator(
                    progress = { (batteryLevel / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = when {
                        batteryLevel <= 15 -> Color(0xFFFF5252)
                        batteryLevel <= 30 -> Color(0xFFFF9800)
                        else -> Color(0xFF4CAF50)
                    },
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    strokeCap = StrokeCap.Round
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // 3. 实时电学参数网格：电压 / 电流 / 实时功率 / 温度
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BatteryMetricItem(
                    label = "电压",
                    value = String.format(Locale.US, "%.2f V", batteryVoltageMv / 1000f)
                )
                BatteryMetricItem(
                    label = "电流",
                    value = String.format(Locale.US, "%.0f mA", displayCurrentMa),
                    valueColor = statusColor
                )
                BatteryMetricItem(
                    label = "实时功率",
                    value = String.format(Locale.US, "%.2f W", powerWatts),
                    valueColor = Color(0xFFE91E63)
                )
                BatteryMetricItem(
                    label = "电池温度",
                    value = String.format(Locale.US, "%.1f °C", batteryTemp),
                    valueColor = if (batteryTemp > 45f) Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // 4. 电池健康度与硬件容量网格：SoH % / 循环次数 / 满电容量 / 设计容量
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BatteryMetricItem(
                    label = "SoH 健康度",
                    value = if (batterySohPercent > 0f) String.format(Locale.US, "%.1f%%", batterySohPercent) else "N/A",
                    valueColor = Color(0xFF4CAF50)
                )
                BatteryMetricItem(
                    label = "循环次数",
                    value = if (batteryCycleCount > 0) "$batteryCycleCount 次" else "N/A",
                    valueColor = Color(0xFF00BCD4)
                )
                BatteryMetricItem(
                    label = "实际满电量",
                    value = if (batteryFullMah > 0f) String.format(Locale.US, "%.0f mAh", batteryFullMah) else "N/A"
                )
                BatteryMetricItem(
                    label = "设计总容量",
                    value = if (batteryFullDesignMah > 0f) String.format(Locale.US, "%.0f mAh", batteryFullDesignMah) else "N/A"
                )
            }

            // 5. 实时电流趋势图
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("实时电流趋势", fontSize = 10.sp, color = Color.Gray)
                    val absHistory = historyData.map { abs(it) }
                    val maxCurrent = absHistory.maxOrNull() ?: 0f
                    Text("峰值: ${maxCurrent.toInt()} mA", fontSize = 10.sp, color = Color.Gray)
                }

                MetricLineChart(
                    data = historyData.map { abs(it) },
                    maxVal = (historyData.map { abs(it) }.maxOrNull() ?: 1000f).coerceAtLeast(500f),
                    lineColor = statusColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                )
            }
        }
    }
}

@Composable
private fun BatteryMetricItem(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(label, fontSize = 10.sp, color = Color.Gray)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
fun SystemSummaryCard(state: PerformanceUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("帧率波动", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        text = String.format(Locale.US, "%.2f FPS", state.renderFps),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("屏幕刷新率", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        text = String.format(Locale.US, "%.2f Hz", state.refreshRateHz),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("电池温度", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        text = String.format(Locale.US, "%.1f °C", state.batteryTemp),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.batteryTemp > 45f) Color.Red else Color(0xFFFF5722)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            MetricLineChart(
                data = state.fpsHistory,
                maxVal = state.refreshRateHz.coerceAtLeast(60f),
                lineColor = Color(0xFF4CAF50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            )
        }
    }
}

@Composable
fun DisplayInfoCard(uiState: PerformanceUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📺 屏幕参数 (DisplayManager)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "当前: ${uiState.currentResolution}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            Text(
                text = "支持的分辨率与帧率模式：",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OptInFlowRow(modes = uiState.supportedDisplayModes)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptInFlowRow(modes: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        modes.forEach { mode ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                )
            ) {
                Text(
                    text = mode,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
fun GpuMetricCard(gpu: GpuMetric) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GPU 核心", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${gpu.utilizationPercent.toInt()}% Load",
                        fontSize = 10.sp,
                        color = Color(0xFF9C27B0),
                        modifier = Modifier
                            .background(Color(0xFF9C27B0).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = String.format(Locale.US, "%.3f GHz", gpu.curFreqGhz),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF9C27B0)
                )
            }
            Text(
                text = String.format(Locale.US, "Limit: %.3f - %.3f GHz", gpu.minFreqGhz, gpu.maxFreqGhz),
                fontSize = 10.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            MetricLineChart(
                data = gpu.history,
                maxVal = gpu.maxFreqGhz.coerceAtLeast(0.1f),
                lineColor = Color(0xFF9C27B0),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            )
        }
    }
}

@Composable
fun SingleCoreCard(core: CpuCoreMetric, modifier: Modifier = Modifier) {
    val color = CoreColors.getOrElse(core.coreIndex) { Color.Gray }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CPU ${core.coreIndex}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    text = String.format(Locale.US, "%.3f GHz", core.curFreqGhz),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = color
                )
            }
            Text(
                text = String.format(Locale.US, "HW: %.3f-%.3fG", core.minFreqGhz, core.maxFreqGhz),
                fontSize = 9.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(6.dp))
            MetricLineChart(
                data = core.history,
                maxVal = core.maxFreqGhz.coerceAtLeast(0.1f),
                lineColor = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            )
        }
    }
}

@Composable
fun MetricLineChart(
    data: List<Float>,
    maxVal: Float,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas
        val width = size.width
        val height = size.height
        val stepX = width / (data.size - 1)

        val path = Path()
        val fillPath = Path()

        data.forEachIndexed { i, value ->
            val x = i * stepX
            val normalized = (value / maxVal).coerceIn(0f, 1f)
            val y = height - (normalized * height)

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo((data.size - 1) * stepX, height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.35f), Color.Transparent)
            )
        )
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

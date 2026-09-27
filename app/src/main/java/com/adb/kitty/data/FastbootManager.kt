package com.adb.kitty.data

import com.adb.kitty.R

import android.*
import android.util.*
import android.content.pm.*
import android.app.*
import android.graphics.*
import android.animation.*

import android.os.*
import android.view.*
import android.widget.*
import android.content.*
import android.hardware.usb.*

import android.net.*
import android.net.wifi.*
import android.net.nsd.*
import android.text.method.*

import androidx.core.view.*
import androidx.core.content.*
import androidx.core.app.*
/*******************************
*        kotlinx 协程         *
*    suspend 都给我挂起     *
********************************/
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

import kotlin.*
import kotlin.coroutines.*
import kotlin.math.*

import java.io.*
import java.nio.*
import java.security.*
import java.text.*
import java.net.*
import java.util.*
import java.util.zip.*
import java.time.*
import java.time.format.*
import javax.crypto.*
import javax.net.ssl.*
import org.json.*
import androidx.annotation.Keep

// 深度适配小米设备

@Keep
data class FastbootConfig(
    val abPartitions: Set<String> = setOf(
        "boot", "abl", "xbl", "xbl_config", "cpucp_dtb", "shrm", 
        "aop", "aop_config", "tz", "devcfg", "featenabler", "hyp", 
        "uefi", "uefisecapp", "spuservice", "modem", "modemfirmware", 
        "bluetooth", "dsp", "keymaster", "qupfw", "multiimgoem", 
        "multiimgqti", "cpucp", "xbl_ramdump", "imagefv", 
        "init_boot", "vendor_boot", "dtbo", "vbmeta", "vbmeta_system",
        "recovery"
    ),
    val bootPartitions: Set<String> = setOf(".img", ".elf", ".bin", ".mbn")
)

@Keep
data class FastbootResponse(val status: String, val payload: String, val allLines: List<String>)

@Keep
class FastbootManager(
    private val scope: CoroutineScope,
    private val usbConn: UsbDeviceConnection,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
    private val responseChannel: Channel<String>,
    private val flashFolder: File,
    private val config: FastbootConfig = FastbootConfig()
) {
    private var readerJob: Job? = null
    
    private val _logFlow = MutableSharedFlow<String>()
    val logFlow = _logFlow.asSharedFlow()

    private suspend fun log(msg: String) {
        _logFlow.emit(msg)
    }

    fun startFastbootReader() {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isActive) {
                val read = usbConn.bulkTransfer(epIn, buffer, buffer.size, 1000)
                if (read > 0) {
                    val response = String(buffer, 0, read).trim()
                    withContext(Dispatchers.Main) {
                        log("[INFO] FB >> $response")
                    }
                    responseChannel.trySend(response)
                }
            }
        }
    }

    private suspend fun waitForTerminalResponse(
        timeout: Long = 10000, 
        onInfoReceived: suspend (String) -> Unit
    ): FastbootResponse {
        val lines = mutableListOf<String>()
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val resp = withTimeoutOrNull(2000) { responseChannel.receive() } ?: continue
            lines.add(resp)
        
            if (resp.startsWith("OKAY") || resp.startsWith("FAIL")) {
                val status = resp.substring(0, 4)
                val payload = if (resp.length > 4) resp.substring(4) else ""
                return FastbootResponse(status, payload, lines)
            } else if (resp.startsWith("DATA")) {
                val payload = if (resp.length > 4) resp.substring(4) else ""
                return FastbootResponse("DATA", payload, lines)
            } else if (resp.startsWith("INFO")) {
                val infoPayload = if (resp.length > 4) resp.substring(4) else ""
                onInfoReceived(infoPayload)
            } else {
                onInfoReceived(resp)
            }
        }
        return FastbootResponse("TIMEOUT", "[Warn] 无响应", lines)
    }

    private fun sendFastbootCommandDirect(command: String) {
        val data = command.toByteArray()
        usbConn.bulkTransfer(epOut, data, data.size, 1000)
    }

    suspend fun executeCommandSync(command: String) = withContext(Dispatchers.IO) {
        val cleanCmd = command.removePrefix("fastboot ").trim()
        if (cleanCmd.isEmpty()) return@withContext
        val parts = cleanCmd.split(Regex("\\s+"))
        val action = parts[0].lowercase()

        when (action) {
            "flash" -> {
                if (parts.size >= 3) {
                    performFlash(parts[1], parts[2])
                } else {
                    withContext(Dispatchers.Main) {
                        log("[error] 格式: flash 分区 文件名")
                    }
                }
                return@withContext
            }
            "boot" -> {
                if (parts.size >= 2) {
                    performBoot(parts[1])
                } else {
                    withContext(Dispatchers.Main) {
                        log("[error] 格式: boot 文件名")
                    }
                }
                return@withContext
            }
        }

        val protocolCmd = when (action) {
            "getvar" -> {
                if (parts.size >= 2) "${parts[0]}:${parts.drop(1).joinToString(" ")}" else parts[0]
            }
            "oem" -> {
                cleanCmd 
            }
            "reboot" -> {
                cleanCmd 
            }
            "erase" -> {
                if (parts.size >= 2) "$action:${parts[1]}" else ""
            }
            "format" -> {
                if (parts.size >= 2) "$action:${parts[1]}" else ""
            }
            "set_active" -> {
                if (parts.size >= 2) "$action:${parts[1]}" else ""
            }
            else -> {
                cleanCmd
            }
        }

        withContext(Dispatchers.Main) {
            log("[INFO] 发送: $protocolCmd")
        }

        sendFastbootCommandDirect(protocolCmd)

        val result = waitForTerminalResponse(10000) { }

        withContext(Dispatchers.Main) {
            when (result.status) {
                "FAIL" -> log("[error] 指令被拒绝: ${result.payload}")
                "TIMEOUT" -> log("[Warn] 无响应: ${result.payload}")
            }
        }
    }

    suspend fun performFlash(partition: String, inputPath: String) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val cleanFileName = inputPath.removePrefix("/")
        val file = File(flashFolder, cleanFileName)
        if (!file.exists()) {
            withContext(Dispatchers.Main) { 
                log("[error] 文件不存在 -> $file.absolutePath") 
            }
            return@withContext
        }
        
        val activeSlot = getActiveSlot()
        val hasManualSuffix = partition.endsWith("_a", ignoreCase = true) || 
                              partition.endsWith("_b", ignoreCase = true) || 
                              partition.endsWith("_ab", ignoreCase = true)
        val targetPartition = if (hasManualSuffix) {
            partition 
        } else {
            getTargetPartition(partition, activeSlot)
        }
        
        withContext(Dispatchers.Main) { 
            log("[INFO] 准备刷入: ${file.name} -> 目标: $targetPartition")
            log("[INFO] 计算目标: $partition -> $targetPartition (Active Slot: ${activeSlot.ifEmpty { "N/A" }})")
        }
        
        val isSparse = isSparseImage(file)
        withContext(Dispatchers.Main) {
            log("[INFO] 镜像格式识别: ${if (isSparse) "Sparse Image" else "Raw Image"}")
        }

        val sizeHex = String.format("%08x", file.length())
        withContext(Dispatchers.Main) {
            log("[INFO] 发送下载请求: $partition (大小: ${file.length()} bytes)")
        }
    
        sendFastbootCommandDirect("download:$sizeHex")
    
        val handshake = waitForTerminalResponse(10000) { }
        if (handshake.status != "DATA") {
            withContext(Dispatchers.Main) {
                log("[error] 下载请求被拒绝: ${handshake.payload}, 状态: ${handshake.status}")
            }
            return@withContext
        }

        withContext(Dispatchers.Main) {
            log("[INFO] 正在传输数据，请勿断开物理连接!")
        }
        val buffer = ByteArray(65536)
        try {
            FileInputStream(file).use { fis ->
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    val written = usbConn.bulkTransfer(epOut, buffer, bytesRead, 5000)
                    if (written != bytesRead) {
                        throw Exception("[error] 数据传输被中断 (发送字节数不匹配)")
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                log("[error] 传输失败: ${e.message}")
            }
            return@withContext
        }

        val downloadConfirm = waitForTerminalResponse(30000) { }
        if (downloadConfirm.status != "OKAY") {
            withContext(Dispatchers.Main) {
                log("[error] 下载被拒绝: ${downloadConfirm.payload}, 状态: ${downloadConfirm.status}")
            }
            return@withContext
        }

        withContext(Dispatchers.Main) {
            log("[INFO] 触发刷写: flash:$targetPartition")
        }
        sendFastbootCommandDirect("flash:$targetPartition")
    
        val flashResult = waitForTerminalResponse(120000) { }
        
        val endTime = System.currentTimeMillis()
        val durationSeconds = (endTime - startTime) / 1000.0
        val thresholdBytes = 512 * 1024

        withContext(Dispatchers.Main) {
            if (flashResult.status == "OKAY") {
                val logMessage = StringBuilder()
                logMessage.append("[OKAY] 分区 $targetPartition 刷写完成\n")
                logMessage.append("[INFO] 耗时: ${"%.2f".format(durationSeconds)}秒")
            
                if (file.length() >= thresholdBytes && durationSeconds > 0) {
                    val fileSizeMB = file.length() / (1024.0 * 1024.0)
                    val speedMbps = fileSizeMB / durationSeconds
                    logMessage.append(" | 平均速度: ${"%.2f".format(speedMbps)} MB/s")
                } else {
                    logMessage.append("[INFO] 刷写的分区过小，因此不展示传输速度")
                }
                log(logMessage.toString())
            } else {
                log("[error] 分区 $partition 刷写失败: ${flashResult.payload} (已耗时: ${"%.2f".format(durationSeconds)}秒)")
            }
        }
    }

    suspend fun performBoot(fileName: String) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val file = File(flashFolder, fileName)
        val extension = "." + fileName.substringAfterLast(".", "").lowercase()

        if (fileName.endsWith(".xml", true) || fileName.endsWith(".txt", true) || fileName.endsWith(".py", true)) {
            withContext(Dispatchers.Main) { 
                log("[error] 文件类型无法被引导") 
            }
            return@withContext
        }
        
        if (!config.bootPartitions.contains(extension)) {
            withContext(Dispatchers.Main) { 
                log("[Warn] 文件后缀 $extension 可能无法被设备引导，将尝试发送") 
            }
        }
        
        if (!file.exists()) {
            withContext(Dispatchers.Main) { 
                log("[error] 找不到文件 -> ${file.absolutePath}") 
            }
            return@withContext
        }

        withContext(Dispatchers.Main) { 
            log("[INFO] 准备临时引导 (RAM Boot): ${file.name}") 
        }

        try {
            val sizeHex = String.format("%08x", file.length())
            withContext(Dispatchers.Main) {
                log("[INFO] 触发下载请求 (大小: ${file.length()} bytes)")
            }
            sendFastbootCommandDirect("download:$sizeHex")
        
            val handshake = waitForTerminalResponse(10000) { }
            if (handshake.status != "DATA") {
                withContext(Dispatchers.Main) {
                    log("[error] 下载被拒绝: ${handshake.payload}, 状态: ${handshake.status}")
                }
                return@withContext
            }

            val buffer = ByteArray(65536)
            FileInputStream(file).use { fis ->
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    val written = usbConn.bulkTransfer(epOut, buffer, bytesRead, 5000)
                    if (written != bytesRead) throw Exception("[error] 数据传输被中断")
                }
            }

            val downloadConfirm = waitForTerminalResponse(30000) { }
            if (downloadConfirm.status != "OKAY") {
                withContext(Dispatchers.Main) {
                    log("[error] 下载被拒绝: ${downloadConfirm.payload}, 状态: ${downloadConfirm.status}")
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                log("[INFO] 触发 boot 发送请求")
            }
            sendFastbootCommandDirect("boot")

            val bootResult = waitForTerminalResponse(30000) { }
            val duration = (System.currentTimeMillis() - startTime) / 1000.0

            withContext(Dispatchers.Main) {
                if (bootResult.status == "OKAY") {
                    log("[OKAY] 已成功发送 boot 指令 (耗时: ${"%.2f".format(duration)}秒)")
                } else {
                    log("[error] 指令被拒绝: ${bootResult.payload}")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                log("[error] 异常: ${e.message}")
            }
        }
    }

    private suspend fun getActiveSlot(): String {
        sendFastbootCommandDirect("getvar:current-slot")
        val response = waitForTerminalResponse(5000) { /* 可以在这里打印日志调试 */ }
    
        val fullResponse = response.payload.lowercase()
    
        return when {
            fullResponse.contains("okayb") -> "b"
            fullResponse.contains("okaya") -> "a"
        
            fullResponse.contains("current-slot: b") -> "b"
            fullResponse.contains("current-slot: a") -> "a"
        
            fullResponse.contains("slot: b") || fullResponse.endsWith(" b") -> "b"
            fullResponse.contains("slot: a") || fullResponse.endsWith(" a") -> "a"
        
            else -> ""
        }
    }

    private fun getTargetPartition(partition: String, activeSlot: String): String {
        return if (config.abPartitions.contains(partition) && activeSlot.isNotEmpty()) {
            "${partition}_$activeSlot"
        } else {
            partition
        }
    }

    private fun isSparseImage(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val SPARSE_HEADER_MAGIC = 0xED26FF3A.toInt() // 小端序 Magic

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(4)
                raf.readFully(buffer)
                val magic = ByteBuffer.wrap(buffer)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                magic == SPARSE_HEADER_MAGIC
            }
        } catch (e: Exception) {
            false
        }
    }
}

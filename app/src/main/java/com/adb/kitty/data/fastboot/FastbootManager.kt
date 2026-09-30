package com.adb.kitty.data.fastboot

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.lsposed.hiddenapibypass.HiddenApiBypass

@Keep
data class FastbootConfig(
    val abPartitions: Set<String> = setOf(
        "boot", "abl", "xbl", "xbl_config", "cpucp_dtb", "shrm", 
        "aop", "aop_config", "tz", "devcfg", "featenabler", "hyp", 
        "uefi", "uefisecapp", "spuservice", "modem", "modemfirmware", 
        "bluetooth", "dsp", "keymaster", "qupfw", "multiimgoem", 
        "multiimgqti", "cpucp", "xbl_ramdump", "imagefv", 
        "init_boot", "vendor_boot", "dtbo", "vbmeta", "vbmeta_system",
        "recovery", "system", "vendor", "product", "system_ext", "odm", "super"
    ),
    val bootPartitions: Set<String> = setOf(".img", ".elf", ".bin", ".mbn"),
    val defaultMaxDownloadSize: Long = 256 * 1024 * 1024L // 256MB 默认兜底
)

@Keep
data class FastbootResponse(val status: String, val payload: String, val allLines: List<String>)

// Sparse 格式头定义
@Keep
private data class SparseHeader(
    val magic: Int,
    val majorVersion: Short,
    val minorVersion: Short,
    val fileHdrSz: Short,
    val chunkHdrSz: Short,
    val blkSz: Int,
    val totalBlks: Int,
    val totalChunks: Int,
    val imageChecksum: Int
)

// Sparse Chunk 头定义
@Keep
private data class ChunkHeader(
    val chunkType: Short,  // 0xCAC1=RAW, 0xCAC2=FILL, 0xCAC3=DONT_CARE, 0xCAC4=CRC32
    val reserved: Short,
    val chunkSz: Int,      // 块数量 (in blocks)
    val totalSz: Int       // 字节大小 (chunk header + payload)
) {
    val payloadSize: Int get() = totalSz - 12
}

/**
 * USB 速率检测模式
 */
@Keep
enum class UsbSpeedMode(
    val chunkSize: Int, 
    val queueDepth: Int, 
    val description: String
) {
    SPEED_2_0(256 * 1024, 2, "USB 2.0 HighSpeed (480Mbps)"),
    SPEED_3_0(1024 * 1024, 2, "USB 3.0/3.1 Gen1 SuperSpeed (5Gbps)"),
    SPEED_3_1_PLUS(2 * 1024 * 1024, 4, "USB 3.1 Gen2/3.2/USB4 SuperSpeed+ (10Gbps+)");

    companion object {
        private const val API_USB_SPEED_HIGH = 3
        private const val API_USB_SPEED_SUPER = 4
        private const val API_USB_SPEED_SUPER_PLUS = 5

        fun detect(
            context: Context?,
            usbManager: UsbManager?,
            device: UsbDevice?,
            epOut: UsbEndpoint
        ): UsbSpeedMode {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && usbManager != null) {
                try {
                    // 使用 HiddenApiBypass 绕过系统限制调用 UsbManager.getPorts()
                    val ports = HiddenApiBypass.invoke(
                        UsbManager::class.java,
                        usbManager,
                        "getPorts"
                    ) as? List<*>

                    if (ports != null) {
                        for (port in ports) {
                            if (port == null) continue

                            // 调用 UsbPort.getStatus()
                            val status = HiddenApiBypass.invoke(
                                port.javaClass,
                                port,
                                "getStatus"
                            ) ?: continue

                            // 调用 UsbPortStatus.isConnected()
                            val isConnected = HiddenApiBypass.invoke(
                                status.javaClass,
                                status,
                                "isConnected"
                            ) as? Boolean ?: false

                            if (isConnected) {
                                // 调用 UsbPortStatus.getCurrentUsbSpeed()
                                val currentUsbSpeed = HiddenApiBypass.invoke(
                                    status.javaClass,
                                    status,
                                    "getCurrentUsbSpeed"
                                ) as? Int ?: continue

                                return when (currentUsbSpeed) {
                                    API_USB_SPEED_HIGH -> SPEED_2_0
                                    API_USB_SPEED_SUPER -> SPEED_3_0
                                    API_USB_SPEED_SUPER_PLUS -> SPEED_3_1_PLUS
                                    else -> getFallbackSpeed(epOut)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 无权限或非系统支持时，安全回退到硬件物理端点判断
                }
            }
            return getFallbackSpeed(epOut)
        }

        private fun getFallbackSpeed(epOut: UsbEndpoint): UsbSpeedMode {
            // 通过 USB Bulk OUT 端点的 maxPacketSize 兜底判断：
            // USB 2.0 HighSpeed 包大小最大为 512 字节
            // USB 3.0+ SuperSpeed 包大小一般为 1024 字节
            return if (epOut.maxPacketSize >= 1024) SPEED_3_0 else SPEED_2_0
        }
    }
}

@Keep
class FastbootManager(
    private val scope: CoroutineScope,
    private val usbConn: UsbDeviceConnection,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
    private val responseChannel: Channel<String>,
    private val flashFolder: File,
    private val context: Context? = null,
    private val usbManager: UsbManager? = null,
    private val usbDevice: UsbDevice? = null,
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
        onInfoReceived: (suspend (String) -> Unit)? = null
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
                onInfoReceived?.invoke(infoPayload)
            } else {
                onInfoReceived?.invoke(resp)
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

        val result = waitForTerminalResponse(10000)
        withContext(Dispatchers.Main) {
            when (result.status) {
                "FAIL" -> log("[error] 指令被拒绝: ${result.payload}")
                "TIMEOUT" -> log("[Warn] 无响应: ${result.payload}")
            }
        }
    }

    /**
     * 查询设备的 max-download-size
     */
    suspend fun getMaxDownloadSize(): Long = withContext(Dispatchers.IO) {
        sendFastbootCommandDirect("getvar:max-download-size")
        val response = waitForTerminalResponse(3000)
        val payload = response.payload.trim().lowercase()

        val cleanHex = payload.removePrefix("0x").takeWhile { it.isLetterOrDigit() }
        val size = try {
            if (payload.contains("0x")) cleanHex.toLong(16) else cleanHex.toLong()
        } catch (e: Exception) {
            config.defaultMaxDownloadSize
        }
        
        // 预留 1MB 防止缓冲区溢出
        return@withContext if (size > 1024 * 1024) size - (1024 * 1024) else size
    }

    /**
     * 主刷写入口
     */
    suspend fun performFlash(partition: String, inputPath: String) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val cleanFileName = inputPath.removePrefix("/")
        val file = File(flashFolder, cleanFileName)

        if (!file.exists()) {
            withContext(Dispatchers.Main) {
                log("[error] 文件不存在 -> ${file.absolutePath}")
            }
            return@withContext
        }

        val activeSlot = getActiveSlot()
        val targetPartition = getTargetPartition(partition, activeSlot)
        val maxDownloadSize = getMaxDownloadSize()
        val isSparse = isSparseImage(file)
        
        val speedMode = UsbSpeedMode.detect(context, usbManager, usbDevice, epOut)

        withContext(Dispatchers.Main) { 
            log("[INFO] 准备刷写: ${file.name} -> 目标: $targetPartition (Slot: ${activeSlot.ifEmpty { "N/A" }})")
            log("[INFO] 协议感知: ${speedMode.description}")
            log("[INFO] 设备限制: ${maxDownloadSize / 1024 / 1024} MB | 源类型: ${if (isSparse) "Sparse Image" else "Raw Image"}")
        }

        if (isSparse) {
            flashSparseImageInternal(file, targetPartition, maxDownloadSize, speedMode, startTime)
        } else {
            if (file.length() <= maxDownloadSize) {
                // 标准小型 RAW 镜像：直接整包发送
                flashRawImageInternal(file, targetPartition, speedMode, startTime)
            } else {
                // 超大 RAW 镜像：线上实时 Sparse 包装分片传输（无需用户手动转换）
                flashLargeRawAsSparseOnTheFly(file, targetPartition, maxDownloadSize, speedMode, startTime)
            }
        }
    }

    /**
     * 小型 RAW 镜像直接刷写逻辑
     */
    private suspend fun flashRawImageInternal(
        file: File, 
        targetPartition: String, 
        speedMode: UsbSpeedMode, 
        startTime: Long
    ) {
        val sizeHex = String.format("%08x", file.length())
        sendFastbootCommandDirect("download:$sizeHex")

        val handshake = waitForTerminalResponse(10000)
        if (handshake.status != "DATA") {
            withContext(Dispatchers.Main) {
                log("[error] 下载被拒绝: ${handshake.payload}")
            }
            return
        }

        val success = sendFileDataStreamUltra(file, 0, file.length(), speedMode)
        if (!success) return

        val downloadConfirm = waitForTerminalResponse(30000)
        if (downloadConfirm.status != "OKAY") {
            withContext(Dispatchers.Main) {
                log("[error] 下载确认失败: ${downloadConfirm.payload}")
            }
            return
        }

        sendFastbootCommandDirect("flash:$targetPartition")
        val dynamicTimeout = 30000L + (file.length() / (100 * 1024 * 1024) * 10000L)
        val flashResult = waitForTerminalResponse(dynamicTimeout)

        printFlashSummary(targetPartition, file.length(), startTime, flashResult)
    }

    /**
     * 超大 RAW 镜像动态线上传输引擎：
     * 无需在磁盘转换文件，直接将 RAW 数据以 4096 字节对齐切块，
     * 实时在网络层插入 Sparse 头与 RAW Chunk 头后以 Sparse 协议流式推送给设备 Bootloader。
     */
    private suspend fun flashLargeRawAsSparseOnTheFly(
        file: File,
        targetPartition: String,
        maxDownloadSize: Long,
        speedMode: UsbSpeedMode,
        startTime: Long
    ) {
        val blockSize = 4096
        val fileLength = file.length()
        
        // 计算每个 Sparse 批次可容纳的最大 RAW 字节数 (需 4KB 对齐并扣除 40 字节 Header)
        val maxRawPayloadPerBatch = ((maxDownloadSize - 40) / blockSize) * blockSize
        val totalBatches = ((fileLength + maxRawPayloadPerBatch - 1) / maxRawPayloadPerBatch).toInt()

        withContext(Dispatchers.Main) {
            log("[INFO] RAW 镜像 (${fileLength / 1024 / 1024} MB) 超出传输单包上限，启用动态线上分片传输 (共 $totalBatches 批)...")
        }

        var offset = 0L
        for (batchIndex in 0 until totalBatches) {
            val remaining = fileLength - offset
            val currentRawSize = minOf(maxRawPayloadPerBatch, remaining)
            val totalBlocks = (currentRawSize / blockSize).toInt()
            val batchPacketSize = 28 + 12 + currentRawSize // 28字节 Sparse 头 + 12字节 Chunk 头 + 数据 Payload

            withContext(Dispatchers.Main) {
                log("[INFO] 正在传输批次 (${batchIndex + 1}/$totalBatches) | 体积: ${batchPacketSize / 1024 / 1024} MB...")
            }

            // 1. 发送 Download 指令
            val sizeHex = String.format("%08x", batchPacketSize)
            sendFastbootCommandDirect("download:$sizeHex")

            val handshake = waitForTerminalResponse(10000)
            if (handshake.status != "DATA") {
                withContext(Dispatchers.Main) {
                    log("[error] 批次 $batchIndex 下载请求被拒绝")
                }
                return
            }

            // 2. 内存生成 28 字节动态 Sub Sparse Header
            val subHeaderBuffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0xED26FF3A.toInt()) // Magic
                putShort(1)                 // Major version
                putShort(0)                 // Minor version
                putShort(28)                // File header size
                putShort(12)                // Chunk header size
                putInt(blockSize)           // Block size (4096)
                putInt(totalBlocks)         // Total blocks in this slice
                putInt(1)                   // Total chunks (1 RAW chunk)
                putInt(0)                   // Checksum
            }.array()
            usbConn.bulkTransfer(epOut, subHeaderBuffer, 28, 2000)

            // 3. 内存生成 12 字节动态 RAW Chunk Header
            val chunkHdrBytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0xCAC1.toShort())  // Chunk Type: 0xCAC1 (RAW)
                putShort(0)                 // Reserved
                putInt(totalBlocks)         // Chunk size in blocks
                putInt(12 + currentRawSize.toInt()) // Total chunk size (Header + Payload)
            }.array()
            usbConn.bulkTransfer(epOut, chunkHdrBytes, 12, 2000)

            // 4. 传输 RAW 文件中的数据片段
            val success = sendFileDataStreamUltra(file, offset, currentRawSize, speedMode)
            if (!success) return

            val downloadConfirm = waitForTerminalResponse(30000)
            if (downloadConfirm.status != "OKAY") return

            // 5. 触发分片 Flash 指令
            sendFastbootCommandDirect("flash:$targetPartition")
            val flashResult = waitForTerminalResponse(120000)
            if (flashResult.status != "OKAY") {
                withContext(Dispatchers.Main) {
                    log("[error] 批次 ${batchIndex + 1} 写入失败: ${flashResult.payload}")
                }
                return
            }

            offset += currentRawSize
        }

        printFlashSummary(targetPartition, fileLength, startTime, FastbootResponse("OKAY", "", emptyList()))
    }

    /**
     * Sparse 稀疏镜像自动拆包与分块刷写
     */
    private suspend fun flashSparseImageInternal(
        file: File, 
        targetPartition: String, 
        maxDownloadSize: Long, 
        speedMode: UsbSpeedMode,
        startTime: Long
    ) {
        RandomAccessFile(file, "r").use { raf ->
            val headerBuffer = ByteArray(28)
            raf.readFully(headerBuffer)
            val headerBb = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN)
            val header = SparseHeader(
                magic = headerBb.int,
                majorVersion = headerBb.short,
                minorVersion = headerBb.short,
                fileHdrSz = headerBb.short,
                chunkHdrSz = headerBb.short,
                blkSz = headerBb.int,
                totalBlks = headerBb.int,
                totalChunks = headerBb.int,
                imageChecksum = headerBb.int
            )

            var currentOffset = header.fileHdrSz.toLong()
            val chunkList = mutableListOf<Triple<ChunkHeader, Long, Long>>()

            for (i in 0 until header.totalChunks) {
                raf.seek(currentOffset)
                val chunkBuffer = ByteArray(12)
                raf.readFully(chunkBuffer)
                val chunkBb = ByteBuffer.wrap(chunkBuffer).order(ByteOrder.LITTLE_ENDIAN)

                val chunk = ChunkHeader(
                    chunkType = chunkBb.short,
                    reserved = chunkBb.short,
                    chunkSz = chunkBb.int,
                    totalSz = chunkBb.int
                )
                chunkList.add(Triple(chunk, currentOffset + 12, chunk.payloadSize.toLong()))
                currentOffset += chunk.totalSz
            }

            val splits = mutableListOf<List<Triple<ChunkHeader, Long, Long>>>()
            var currentSplit = mutableListOf<Triple<ChunkHeader, Long, Long>>()
            var currentSplitBytes = 28L

            for (item in chunkList) {
                val chunkSizeInSplit = item.first.totalSz.toLong()
                if (currentSplitBytes + chunkSizeInSplit > maxDownloadSize && currentSplit.isNotEmpty()) {
                    splits.add(currentSplit)
                    currentSplit = mutableListOf()
                    currentSplitBytes = 28L
                }
                currentSplit.add(item)
                currentSplitBytes += chunkSizeInSplit
            }
            if (currentSplit.isNotEmpty()) splits.add(currentSplit)

            withContext(Dispatchers.Main) { 
                log("[INFO] Sparse 镜像将分 ${splits.size} 批传输") 
            }

            splits.forEachIndexed { index, splitChunks ->
                val splitTotalBlocks = splitChunks.sumOf { it.first.chunkSz }
                val splitTotalChunks = splitChunks.size
                val splitPayloadBytes = splitChunks.sumOf { it.first.totalSz.toLong() }
                val splitImageBytes = 28L + splitPayloadBytes

                withContext(Dispatchers.Main) {
                    log("[INFO] 正在传输批次 (${index + 1}/${splits.size}) | 大小: ${splitImageBytes / 1024 / 1024} MB...")
                }

                val sizeHex = String.format("%08x", splitImageBytes)
                sendFastbootCommandDirect("download:$sizeHex")

                val handshake = waitForTerminalResponse(10000)
                if (handshake.status != "DATA") return

                // 1. 发送 Sub Sparse Header
                val subHeaderBuffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(0xED26FF3A.toInt())
                    putShort(header.majorVersion)
                    putShort(header.minorVersion)
                    putShort(28)
                    putShort(12)
                    putInt(header.blkSz)
                    putInt(splitTotalBlocks)
                    putInt(splitTotalChunks)
                    putInt(0)
                }.array()
                
                usbConn.bulkTransfer(epOut, subHeaderBuffer, 28, 2000)

                // 2. 发送 Chunk Headers & Payloads
                for ((chunk, offset, payloadSize) in splitChunks) {
                    val chunkHdrBytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                        putShort(chunk.chunkType)
                        putShort(chunk.reserved)
                        putInt(chunk.chunkSz)
                        putInt(chunk.totalSz)
                    }.array()

                    usbConn.bulkTransfer(epOut, chunkHdrBytes, 12, 2000)

                    if (payloadSize > 0) {
                        sendFileDataStreamUltra(file, offset, payloadSize, speedMode)
                    }
                }

                val downloadConfirm = waitForTerminalResponse(30000)
                if (downloadConfirm.status != "OKAY") return

                sendFastbootCommandDirect("flash:$targetPartition")
                val flashResult = waitForTerminalResponse(120000)
                if (flashResult.status != "OKAY") return
            }

            printFlashSummary(targetPartition, file.length(), startTime, FastbootResponse("OKAY", "", emptyList()))
        }
    }

    /**
     * 适配 USB 3.0/3.2/4.0 的 4 环形队列异步传输引擎
     */
    private suspend fun sendFileDataStreamUltra(
        file: File, 
        offset: Long, 
        length: Long, 
        speedMode: UsbSpeedMode
    ): Boolean = withContext(Dispatchers.IO) {
        val chunkSize = speedMode.chunkSize
        val queueDepth = speedMode.queueDepth
        
        val buffers = Array(queueDepth) { ByteBuffer.allocateDirect(chunkSize) }
        val requests = Array(queueDepth) { UsbRequest().apply { initialize(usbConn, epOut) } }

        var bytesRemaining = length
        var headIndex = 0
        var tailIndex = 0
        var inFlightCount = 0

        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val channel = raf.channel

                // 初始化预填充队列
                while (bytesRemaining > 0 && inFlightCount < queueDepth) {
                    val buffer = buffers[headIndex]
                    val request = requests[headIndex]

                    buffer.clear()
                    val toRead = minOf(chunkSize.toLong(), bytesRemaining).toInt()
                    val readBytes = channel.read(buffer)
                    if (readBytes <= 0) break

                    buffer.flip()
                    if (!request.queueCompat(buffer)) throw Exception("USB 队列提交失败")

                    bytesRemaining -= readBytes
                    headIndex = (headIndex + 1) % queueDepth
                    inFlightCount++
                }

                // 环形推进
                while (inFlightCount > 0) {
                    val completedReq = usbConn.requestWait() ?: throw Exception("USB 队列断开")

                    inFlightCount--
                    tailIndex = (tailIndex + 1) % queueDepth

                    if (bytesRemaining > 0) {
                        val buffer = buffers[headIndex]
                        val request = requests[headIndex]

                        buffer.clear()
                        val toRead = minOf(chunkSize.toLong(), bytesRemaining).toInt()
                        val readBytes = channel.read(buffer)
                        if (readBytes > 0) {
                            buffer.flip()
                            if (!request.queueCompat(buffer)) throw Exception("USB 队列追加失败")

                            bytesRemaining -= readBytes
                            headIndex = (headIndex + 1) % queueDepth
                            inFlightCount++
                        }
                    }
                }
                return@withContext true
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                log("[error] 高速传输异常: ${e.message}")
            }
            return@withContext false
        } finally {
            requests.forEach { it.close() }
        }
    }

    suspend fun performBoot(fileName: String) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val cleanFileName = fileName.removePrefix("/")
        val file = File(flashFolder, cleanFileName)
        val extension = "." + cleanFileName.substringAfterLast(".", "").lowercase()

        if (!file.exists()) {
            withContext(Dispatchers.Main) {
                log("[error] 找不到引导文件 -> ${file.absolutePath}")
            }
            return@withContext
        }

        if (!config.bootPartitions.contains(extension)) {
            withContext(Dispatchers.Main) {
                log("[Warn] 文件后缀 $extension 非标准引导格式，将尝试强制发送...") 
            }
        }

        // 1. 检查 Boot 镜像大小是否超出 RAM 接收上限（Boot 镜像无法分块）
        val maxDownloadSize = getMaxDownloadSize()
        if (file.length() > maxDownloadSize) {
            withContext(Dispatchers.Main) {
                log("[error] 引导镜像体积 (${file.length() / 1024 / 1024} MB) 超出设备 RAM 允许的最大下载限制 (${maxDownloadSize / 1024 / 1024} MB)，无法引导！")
            }
            return@withContext
        }

        try {
            val speedMode = UsbSpeedMode.detect(context, usbManager, usbDevice, epOut)
            withContext(Dispatchers.Main) {
                log("[INFO] 正在将引导镜像推送到设备内存 (${file.length() / 1024 / 1024} MB)...")
            }

            // 2. 发送 Download 请求
            val sizeHex = String.format("%08x", file.length())
            sendFastbootCommandDirect("download:$sizeHex")
    
            val handshake = waitForTerminalResponse(10000)
            if (handshake.status != "DATA") {
                withContext(Dispatchers.Main) {
                    log("[error] 下载请求被拒绝: ${handshake.payload}")
                }
                return@withContext
            }

            // 3. 高速推送数据包
            val success = sendFileDataStreamUltra(file, 0, file.length(), speedMode)
            if (!success) return@withContext

            val downloadConfirm = waitForTerminalResponse(30000)
            if (downloadConfirm.status != "OKAY") {
                withContext(Dispatchers.Main) {
                    log("[error] 镜像传输校验失败: ${downloadConfirm.payload}")
                }
                return@withContext
            }

            // 4. 发送 Boot 引导指令
            withContext(Dispatchers.Main) {
                log("[INFO] 发送引导指令，设备即将启动...")
            }
            sendFastbootCommandDirect("boot")

            // 5. 兼容处理：部分设备收到 boot 会直接断开 USB 重启，不返回 OKAY
            val bootResult = try {
                waitForTerminalResponse(10000)
            } catch (e: Exception) {
                FastbootResponse("OKAY", "Device rebooted immediately", emptyList())
            }

            val duration = (System.currentTimeMillis() - startTime) / 1000.0

            withContext(Dispatchers.Main) {
                if (bootResult.status == "OKAY" || bootResult.status == "TIMEOUT") {
                    // 多数设备 boot 后由于断开连接会触发 TIMEOUT，视作引导成功
                    log("[OKAY] 引导命令已触发！(耗时: ${"%.2f".format(duration)}s)")
                    log("[INFO] 提示: 若设备卡在 logo，Android 12+ 设备请确认是否需要引导 init_boot 或 vendor_boot。")
                } else {
                    log("[error] 引导失败: ${bootResult.payload}")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { 
                // 如果是在发送 boot 后发生的 USB 断开异常，视为设备重启
                log("[OKAY] 设备已断开 USB 连接并开始引导。")
            }
        }
    }

    private suspend fun getActiveSlot(): String {
        sendFastbootCommandDirect("getvar:current-slot")
        val response = waitForTerminalResponse(3000)
        val fullResponse = response.payload.lowercase()
        return when {
            fullResponse.contains("current-slot: b") || fullResponse.contains("slot: b") || fullResponse.endsWith(" b") -> "b"
            fullResponse.contains("current-slot: a") || fullResponse.contains("slot: a") || fullResponse.endsWith(" a") -> "a"
            else -> ""
        }
    }

    private fun getTargetPartition(partition: String, activeSlot: String): String {
        val hasManualSuffix = partition.endsWith("_a", true) || partition.endsWith("_b", true)
        return if (!hasManualSuffix && config.abPartitions.contains(partition) && activeSlot.isNotEmpty()) {
            "${partition}_$activeSlot"
        } else {
            partition
        }
    }

    private fun isSparseImage(file: File): Boolean {
        if (!file.exists() || file.length() < 28) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = Integer.reverseBytes(raf.readInt())
                magic == 0xED26FF3A.toInt()
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun printFlashSummary(
        targetPartition: String, 
        totalBytes: Long, 
        startTime: Long, 
        result: FastbootResponse
    ) {
        val durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0
        withContext(Dispatchers.Main) {
            if (result.status == "OKAY") {
                val speedMbps = if (durationSeconds > 0) (totalBytes / (1024.0 * 1024.0)) / durationSeconds else 0.0
                log("[OKAY] 分区 $targetPartition 刷写成功！耗时: ${"%.2f".format(durationSeconds)}s | 平均速度: ${"%.2f".format(speedMbps)} MB/s")
            } else {
                log("[error] 分区 $targetPartition 刷写失败: ${result.payload}")
            }
        }
    }

    /**
     * UsbRequest.queue 向下兼容 API 24 (Android 7.0) 扩展函数
     */
    private fun UsbRequest.queueCompat(buffer: ByteBuffer): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            queue(buffer)
        } else {
            @Suppress("DEPRECATION")
            queue(buffer, buffer.remaining())
        }
    }
}

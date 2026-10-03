package com.adb.kitty

import android.*
import android.util.*
import android.content.pm.*
import android.graphics.*
import android.animation.*
import android.provider.*
import android.media.*
import android.app.PendingIntent
import android.annotation.SuppressLint

import android.os.*
import android.view.*
import android.widget.*
import android.webkit.*
import android.content.*
import android.content.res.Configuration
import android.hardware.usb.*
import android.bluetooth.BluetoothAdapter
import android.nfc.NfcAdapter
import android.location.LocationManager

import android.net.*
import android.net.wifi.*
import android.net.nsd.*
import android.text.method.*

import androidx.core.view.*
import androidx.core.content.*
import androidx.core.net.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.window.layout.WindowMetricsCalculator
/*******************************
*        kotlinx 协程         *
*    suspend 都给我挂起     *
********************************/
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

import kotlin.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.system.*

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

import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.*
import androidx.activity.result.contract.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.internal.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.text.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.viewinterop.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.ui.it.cpu.*
import com.adb.kitty.data.*
import com.adb.kitty.data.help.*
import com.adb.kitty.data.fastboot.*
import com.adb.kitty.service.*
import com.adb.kitty.R

@Keep
class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_WIFI_PERMISSION_CODE = 1001
        private const val PREFS_NAME = "adb_kitty_prefs"
        private const val KEY_DEVICE_LIST = "connected_devices"
    }
    private val viewModel: MainActivityViewModel by viewModels()
    private val pviewModel: PerformanceViewModel by viewModels()
    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.adb.kitty.USB_PERMISSION"

    private var usbConn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var readerJob: Job? = null
    private var logCollectJob: Job? = null

    private var isUsbAttached = false
    private var isAdbAuthorized = false
    private var isFastbootMode = false
    private var isWifiEnabled: Boolean = false

    private val responseChannel = Channel<String>(Channel.UNLIMITED)
    
    private var showAppSigBottomSheet by mutableStateOf(false)
    private var selectedSigReport by mutableStateOf<String?>(null)
    
    // adb 与 fastboot 使用的路径
    private val flashFolder by lazy { File(getExternalFilesDir(null), "flash") }
    private fun ensureFlashDirExists() {
        if (!flashFolder.exists()) {
            flashFolder.mkdirs()
        }
    }

    // 日志文件输出专用路径
    private val logsFolder by lazy { File(externalCacheDir, "logs") }
    private fun ensureLogsDirExists() {
        if (!logsFolder.exists()) {
            logsFolder.mkdirs()
        }
    }
    
    private var pendingCsvContent: String? = null

    // 1. SAF 导出 CSV
    private val createCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let { destUri ->
            pendingCsvContent?.let { csvData ->
                try {
                    contentResolver.openOutputStream(destUri)?.use { output ->
                        output.write(csvData.toByteArray())
                    }
                    Toast.makeText(this, "文件导出成功！", Toast.LENGTH_SHORT).show()
                    pviewModel.clearExportData()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "导出失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 2. 通知权限申请
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            appendLog("[INFO] Android 13+ 通知权限校验")
            startAndBindAdbService()
        } else {
            handlePermissionDeniedSituation()
            startAndBindAdbService()
        }
    }

    // 3. 网络扫描权限申请
    private val requestNetworkPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isWifiScanGranted = permissions[getWifiScanPermission()] ?: true
        val isLocalNetworkGranted = if (Build.VERSION.SDK_INT >= 37) {
            permissions["android.permission.ACCESS_LOCAL_NETWORK"] ?: false
        } else {
            true
        }
        if (isWifiScanGranted && isLocalNetworkGranted) {
            appendLog("[INFO] Wi-Fi 所需权限已授予，已具备激活无线链路条件")
        } else {
            appendLog("[Warn] 权限被拒绝，无法自动扫描 Wi-Fi SSID")
        }
    }

    // 4. Android 11+ 所有文件管理权限
    private val allFilesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            appendLog("[INFO] Android 11+ 所有文件访问权限已授权")
        } else {
            appendLog("[Warn] Android 11+ 所有文件访问权限未授权")
        }
    }

    // 5. Android 10- 传统读写权限
    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val isAllGranted = permissionsMap.values.all { it }
        if (isAllGranted) {
            appendLog("[INFO] Android 10 文件读写权限已授权")
        } else {
            appendLog("[Warn] Android 10 文件读写权限未授权")
        }
    }

    // 6. 视频选择与音频提取
    private var pendingAudioBaseName: String = ""
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            appendLog("[INFO] 已成功选取视频，正在分析轨道并提取音频…")

            lifecycleScope.launch {
                val resultUri = extractAudioToMusicDirectory(
                    context = this@MainActivity,
                    videoUri = uri,
                    baseFileName = pendingAudioBaseName,
                    onProgress = { progress ->
                        val percent = (progress * 100).toInt()
                    }
                )

                if (resultUri != null) {
                    appendLog("[OKAY] 音频提取完成！已安全保存至系统的【音乐(Music)/NekoExtractor】目录")
                } else {
                    appendLog("[error] 音频提取失败！可能视频中不包含有效的音频流，或多媒体架构初始化异常。")
                }
            }
        } else {
            appendLog("[Warn] 用户取消了视频选取。")
        }
    }

    // 7. 二维码图片选择
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            appendLog("[INFO] 已选择图片，开始解码...")

            val result = QrCodeUtils.decodeQrCodes(this, uri)

            if (result != null) {
                appendLog("[INFO] 二维码解码成功！")
                qrDecodeResult = result
            } else {
                appendLog("[error] 二维码解析失败，请确保图片清晰且确实包含二维码")
            }
        } else {
            appendLog("[Warn] 取消了系统图片选择。")
        }
    }

    val turbo by lazy { PerformanceTurbo(this) }

    var qrCodeDialogContent by mutableStateOf<String?>(null)
    var qrDecodeResult by mutableStateOf<String?>(null)

    var adbService: AdbSessionService? = null
    private var isServiceBound = false
    private var isBindingRequested = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AdbSessionService.AdbBinder
            adbService = binder.getService()
            val cmdsService = binder.getService()
            isServiceBound = true
            appendLog("[INFO] AdbSessionService Start OKAY!")
            cmdsService.onCommandReceivedListener = { cmd ->
                cmdsServiceExec(cmd)
            }

            logCollectJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    adbService?.logFlow?.collect { msg ->
                        appendLog(msg)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            adbService?.onCommandReceivedListener = null
            isServiceBound = false
            isBindingRequested = false
            adbService = null
            logCollectJob?.cancel()
        }
    }
    
    private var currentShellJob: Job? = null
    
    fun reloadServiceAvatar() {
        adbService?.reloadAvatar()
    }
    
    fun cmdsServiceExec(cmd: String) {
        dispatchCommandRoute(cmd)
    }

    // 桥接方法：将 Activity 内的所有日志无缝灌入 ViewModel
    fun appendLog(msg: String) {
        runOnUiThread {
            viewModel.appendLog(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            ),
            navigationBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            )
        )
        setContent {
            val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
            var showIntentBottomSheet by remember { mutableStateOf(false) }
            var selectedSigReport by remember { mutableStateOf<String?>(null) }
            var showHelpBottomSheet by remember { mutableStateOf(false) }
            var selectedSchemeText by remember { mutableStateOf("") }

            val puiState by pviewModel.uiState.collectAsStateWithLifecycle()
            var showCpuBottomSheet by remember { mutableStateOf(false) }

            var showCpuHistoryBottomSheet by remember { mutableStateOf(false) }

            NekoTheme(dynamicColor = useDynamicColor) {
                CenterAlignedTopAppBarExample(
                    viewModel = viewModel,
                    activity = this@MainActivity,
                    onExecuteCommand = { cmd -> 
                        dispatchCommandRoute(cmd)
                    },
                    onIntentBottomSheet = { 
                        showIntentBottomSheet = true
                    },
                    onHelpBottomSheet = { 
                        showHelpBottomSheet = true
                    },
                    onCpuBottomSheet = { 
                        showCpuBottomSheet = true
                    },
                    onCpuHistoryBottomSheet = { 
                        showCpuHistoryBottomSheet = true
                    }
                )

                qrCodeDialogContent?.let { textToEncode ->
                    QrCodePopupDialog(
                        contentString = textToEncode,
                        onDismiss = { qrCodeDialogContent = null }
                    )
                }

                qrDecodeResult?.let { decodedText ->
                    QrDecodeResultDialog(
                        rawResult = decodedText,
                        onDismiss = { qrDecodeResult = null },
                        onExportToFile = { content ->
                            val savedName = saveTextToFlashFolder(this@MainActivity, flashFolder, content)
                            if (savedName != null) {
                                appendLog("[INFO] 解码内容已成功全部输出至: flash/$savedName")
                            }
                            qrDecodeResult = null 
                        }
                    )
                }

                if (showIntentBottomSheet) {
                    NekoIntentBottomSheet(
                        onDismiss = { showIntentBottomSheet = false },
                        onCommandSubmit = { cmd ->
                            dispatchCommandRoute(cmd) 
                        }
                    )
                }

                if (showHelpBottomSheet) {
                    CommandHelpBottomSheet(
                        isVisible = showHelpBottomSheet,
                        onDismissRequest = { showHelpBottomSheet = false }
                    )
                }

                LaunchedEffect(showCpuBottomSheet) {
                    if (showCpuBottomSheet) {
                        pviewModel.initAndBind(this@MainActivity)
                    }
                }

                if (showCpuBottomSheet) {
                    val context = LocalContext.current
                    CompletePerformanceMonitorBottomSheet(
                        uiState = puiState,
                        onIntervalSelected = { pviewModel.setSampleInterval(it) },
                        onStartRecording = { pviewModel.startRecording(this@MainActivity) },
                        onStopRecording = { 
                            // 停止录制并自动在 getExternalFilesDir/cpu/ 目录下生成 CSV 文件
                            val savedFile = pviewModel.stopRecordingAndSave(context)
                            if (savedFile != null) {
                                appendLog("[INFO] 性能日志已自动保存至: cpu/${savedFile.name}")
                            }
                        },
                        onExportCsv = { csvContent ->
                            pendingCsvContent = csvContent
                            val fileName = "perf_log_${System.currentTimeMillis()}.csv"
                            createCsvLauncher.launch(fileName)
                        },
                        onDismissRequest = { showCpuBottomSheet = false }
                    )
                }

                LaunchedEffect(showCpuHistoryBottomSheet) {
                    if (showCpuHistoryBottomSheet) {
                        pviewModel.refreshSavedFiles(this@MainActivity)
                    }
                }

                if (showCpuHistoryBottomSheet) {
                    val context = LocalContext.current
                    HistoryPerformanceBottomSheet(
                        uiState = puiState,
                        onSelectFile = { file -> pviewModel.loadHistoryFromFile(file) },
                        onDeleteFile = { file -> pviewModel.deleteHistoryFile(context, file) },
                        onBackToList = { pviewModel.clearSelectedHistory() },
                        onDismissRequest = { 
                            pviewModel.clearSelectedHistory()
                            showCpuHistoryBottomSheet = false 
                        }
                    )
                }

                if (showAppSigBottomSheet) {
                    val context = LocalContext.current
                    val appList = remember { getInstalledApps(context) }

                    AppListBottomSheet(
                        appList = appList,
                        isVisible = showAppSigBottomSheet,
                        onDismissRequest = { showAppSigBottomSheet = false },
                        onAppSelected = { selectedApp ->
                            showAppSigBottomSheet = false
                            appendLog("[INFO] 正在解析 ${selectedApp.appName} [${selectedApp.packageName}] 的 APK 签名…")

                            lifecycleScope.launch(Dispatchers.IO) {
                                val report = NativeLibs.ApkSignature(selectedApp.apkPath)
                                val schemeText = NativeLibs.getSupportedSchemesText(selectedApp.apkPath)
                                withContext(Dispatchers.Main) {
                                    selectedSchemeText = schemeText
                                    selectedSigReport = report
                                    appendLog("[INFO] 签名解析完成")
                                }
                            }
                        },
                        onStorageApkSelected = { uri ->
                            showAppSigBottomSheet = false
                            appendLog("[INFO] 正在读取本地 APK 并解析签名…")

                            lifecycleScope.launch(Dispatchers.IO) {
                                val cacheFile = File(context.cacheDir, "target_file.apk")
                                val isCopySuccess = runCatching {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        cacheFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }.isSuccess

                                if (isCopySuccess) {
                                    val apkPath = cacheFile.absolutePath
                                    val report = NativeLibs.ApkSignature(apkPath)
                                    val schemeText = NativeLibs.getSupportedSchemesText(apkPath)

                                    withContext(Dispatchers.Main) {
                                        selectedSchemeText = schemeText
                                        selectedSigReport = report
                                        appendLog("[INFO] 本地 APK 签名解析完成")
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        appendLog("[error] 无法读取选中的本地文件")
                                    }
                                }
                            }
                        }
                    )
                }

                selectedSigReport?.let { report ->
                    SignatureResultBottomSheet(
                        reportText = report,
                        schemeText = selectedSchemeText,
                        onDismiss = { selectedSigReport = null }
                    )
                }
            }
        }
        
        usbManager = getSystemService<UsbManager>()
        ensureFlashDirExists()
        tryToStartService()

        // USB 权限回调广播（单独注册为 NOT_EXPORTED）
        ContextCompat.registerReceiver(
            this,
            usbPermissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 除非 Activity 销毁，否则不允许注销，这是预期行为，如果被注销则破坏整体逻辑，破坏等于重写整个应用的所有逻辑
        val systemIntentFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addDataScheme("file")
        }
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        ContextCompat.registerReceiver(
            this,
            systemReceiver,
            systemIntentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            systemReceiver,
            mediaFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            systemReceiver,
            packageFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        appendLog("[INFO] USB 调试设备权限获取成功")
                        adbService?.connectToInterface(device, flashFolder)
                    }
                } else {
                    appendLog("[Warn] 用户拒绝了 USB 权限申请")
                }
            }
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // 单一处理，逻辑清晰不冲突，互不影响
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("[INFO] USB 主机设备已连接")
                    isUsbAttached = true
                    findHostDevice()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    isUsbAttached = false
                    isAdbAuthorized = false
                    isFastbootMode = false
                    adbService?.closeUsbConnection()
                    appendLog("[Warn] USB 主机设备已断开")
                }

                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    appendLog("[INFO] USB 配件设备已连接")
                }

                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    appendLog("[Warn] USB 配件设备已断开")
                }

                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    when (wifiState) {
                        WifiManager.WIFI_STATE_ENABLED -> {
                            isWifiEnabled = true
                            appendLog("[INFO] ⏳ WLAN 已开启")
                        }
                        WifiManager.WIFI_STATE_DISABLED -> {
                            isWifiEnabled = false
                            appendLog("[Warn] ⏳ WLAN 已关闭")
                        }
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            appendLog("[INFO] 📶 蓝牙已开启")
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            appendLog("[Warn] 📶 蓝牙已关闭")
                        }
                        BluetoothAdapter.STATE_TURNING_ON -> {
                            appendLog("[INFO] ⏳ 蓝牙开启中")
                        }
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            appendLog("[INFO] ⏳ 蓝牙关闭中")
                        }
                    }
                }

                NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> {
                    val state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)
                    when (state) {
                        NfcAdapter.STATE_ON -> {
                            appendLog("[INFO] 💳 NFC 已开启")
                        }
                        NfcAdapter.STATE_OFF -> {
                            appendLog("[Warn] 💳 NFC 已关闭")
                        }
                        NfcAdapter.STATE_TURNING_ON -> {
                            appendLog("[INFO] ⏳ NFC 开启中")
                        }
                        NfcAdapter.STATE_TURNING_OFF -> {
                            appendLog("[INFO] ⏳ NFC 关闭中")
                        }
                    }
                }

                Intent.ACTION_POWER_CONNECTED -> {
                    appendLog("[INFO] 🔌 充电器已插入")
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    appendLog("[Warn] 🔋 充电器已拔出")
                }

                Intent.ACTION_TIME_CHANGED -> {
                    appendLog("[INFO] ⏰ 系统时间被修改")
                }
  
                Intent.ACTION_LOCALE_CHANGED -> {
                    appendLog("[INFO] 🌐 系统语言/区域变更")
                }

                Intent.ACTION_TIMEZONE_CHANGED -> {
                    val tz = intent.getStringExtra("time-zone")
                    appendLog("[INFO] 🌐 时区变更: $tz")
                }

                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    if (state == 1) {
                        appendLog("[INFO] 🎧 有线耳机已插入")
                    } else if (state == 0) {
                        appendLog("[Warn] 🎧 有线耳机已拔出")
                    }
                }

                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    // 耳机拔出/蓝牙断开导致音频即将外放，音乐播放器应在此暂停播放
                    appendLog("[Warn] 🔊 音频输出设备断开，暂停播放")
                }

                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    val lm = context?.getSystemService<LocationManager>()
                    val isGpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    appendLog("[INFO] 📍 定位服务状态变化，GPS启用: $isGpsOn")
                }
 
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val isAirplaneOn = intent.getBooleanExtra("state", false)
                    appendLog("[INFO] ✈️ 飞行模式: $isAirplaneOn")
                }

                Intent.ACTION_SCREEN_ON -> {
                    appendLog("[INFO] 💡 屏幕点亮")
                }

                Intent.ACTION_SCREEN_OFF -> {
                    appendLog("[Warn] 💡 屏幕熄灭")
                }

                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val pm = context?.getSystemService<PowerManager>()
                    appendLog("[INFO] 💤 Doze低电耗模式切换: ${pm.isDeviceIdleMode}")
                }

                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val pm = context?.getSystemService<PowerManager>()
                    appendLog("[INFO] 🔋 省电模式切换: ${pm.isPowerSaveMode}")
                }

                Intent.ACTION_MEDIA_MOUNTED -> {
                    val path = intent.data?.path
                    appendLog("[INFO] 💾 存储设备已挂载: $path")
                }

                Intent.ACTION_PACKAGE_ADDED -> {
                    val packageName = intent.data?.schemeSpecificPart
                    appendLog("[INFO] 📦 应用安装: $packageName")
                }

                Intent.ACTION_PACKAGE_REMOVED -> {
                    val packageName = intent.data?.schemeSpecificPart
                    appendLog("[Warn] 📦 应用卸载: $packageName")
                }

                Intent.ACTION_PACKAGE_REPLACED -> {
                    val packageName = intent.data?.schemeSpecificPart
                    appendLog("[INFO] 📦 应用更新: $packageName")
                }
            }
        }
    }

    fun startWakeLock() {
        // 持有 WakeLock
        val intent = Intent(this, AdbSessionService::class.java).apply {
            action = AdbSessionService.ACTION_START_RECORDING
        }
        startService(intent)
    }

    fun stopWakeLock() {
        // 释放 WakeLock
        val intent = Intent(this, AdbSessionService::class.java).apply {
            action = AdbSessionService.ACTION_STOP_RECORDING
        }
        startService(intent)
    }

    fun addFlagSecure() {
        // 添加 FLAG_SECURE 窗口安全保护
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun clearFlagSecure() {
        // 清除 FLAG_SECURE 窗口安全保护
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun stopAdbService() {
        val intent = Intent(this, AdbSessionService::class.java)
        stopService(intent)
    }

    private fun dispatchCommandRoute(cmdInput: String) {
        val cmd = cmdInput.trim()
        if (cmd.isEmpty()) return
    
        when {
            cmd.startsWith("msg-name") -> {
                appendLog("[INFO] Msg >> $cmd")
                val msgCmd = cmd.removePrefix("msg-name ").trim()
                if (msgCmd.isNotEmpty()) {
                    updateConsoleTitle(msgCmd)
                }
            }

            cmd.startsWith("adb ") -> {
                appendLog("[INFO] ADB >> $cmd")
                val adbCmd = cmd.removePrefix("adb ").trim()
                if (adbCmd.isNotEmpty()) {
                    handleAdbCommand(adbCmd)
                }
            }

            cmd.startsWith("neko ") -> {
                // 临时保留 neko 指令, 一般情况用不到
                val localCmd = cmd.removePrefix("neko ").trim()
                if (localCmd.isNotEmpty()) {
                    handleLocalShellPipeline(cmd)
                }
            }
            
            cmd == "neko-sig" || cmd == "apk-sig" -> {
                appendLog("[INFO] 扩展指令 >> $cmd")
                appendLog("[INFO] 正在读取安装应用列表...")
                showAppSigBottomSheet = true
            }
            
            cmd.startsWith("neko-intent ") -> {
                appendLog("[INFO] 扩展指令 >> $cmd")
                val argsText = cmd.removePrefix("neko-intent ").trim()
                val parts = argsText.split("|")
                val content = parts[0].trim()
                val packageName = if (parts.size > 1) parts[1].trim() else ""

                if (content.isEmpty()) {
                    appendLog("[error] 内容或链接不能为空！")
                    return
                }

                appendLog("[INFO] 正在构建智能 Intent 执行管道...")
                executeSmartIntent(this, content, packageName)
            }
            
            cmd.startsWith("neko-audio") -> {
                appendLog("[INFO] 扩展指令 >> $cmd")
                
                val nameArg = cmd.removePrefix("neko-audio").trim()
                pendingAudioBaseName = nameArg.ifEmpty {
                    "NekoAudio_${System.currentTimeMillis()}"
                }

                appendLog("[INFO] 正在唤醒系统的媒体选择器…")
                
                pickVideoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }

            cmd.startsWith("download ") -> {
                appendLog("[INFO] 扩展指令 >> $cmd")
                val urlArg = cmd.removePrefix("download ").trim()
                if (urlArg.isEmpty() || urlArg == "download") {
                    appendLog("[error] download 指令缺少参数！用法: download <文件的URL地址>")
                } else {
                    executeDownload(urlArg)
                }
            }

            cmd.startsWith("encrypt ") -> handleCryptoCommand(cmd, isEncrypt = true)
            cmd.startsWith("decrypt ") -> handleCryptoCommand(cmd, isEncrypt = false)
            cmd.startsWith("qr-gen ")   -> handleQrGenCommand(cmd)
            cmd.startsWith("qr-decode ") -> handleQrDecodeCommand(cmd)

            cmd == "userkitty-log-export" -> exportLogToFlashFolder()
        
            cmd == "ip-test" -> {
                appendLog("[INFO] 扩展指令 >> $cmd")
                startIpNetworkTest()
            }
            cmd == "usb-host" -> {
                appendLog("[INFO] 扩展指令 >> $cmd")
                findHostDevice()
            }
            cmd == "query-apm" -> {
                appendLog("[INFO] 扩展指令 >> $cmd")
                handleApmQuery()
            }

            else -> handlePhysicalFallback(cmd)
        }
    }

    private fun updateConsoleTitle(newTitle: String) {
        // 1. 持久化保存到 SharedPreferences
        val prefs = applicationContext.getSharedPreferences("msg_name_title", Context.MODE_PRIVATE)
        prefs.edit { 
            putString("key_name_title", newTitle) 
        }

        reloadServiceAvatar()
 
        appendLog("[INFO] Updated console title to: $newTitle")
    }

    private fun handleAdbCommand(adbCmd: String) {
        val client = adbService?.safeAdbClient
        if (client == null) {
            appendLog("[error] ADB可能未初始化")
            return
        }

        val tokens = adbCmd.split("\\s+".toRegex())
        val subCmd = tokens.getOrNull(0)?.lowercase() ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                when (subCmd) {
                    "connect" -> {
                        // 支持: connect 10.45.16.152:5555 或 connect 10.45.16.152 5555
                        val target = tokens.getOrNull(1) ?: run {
                            appendLog("[error] 请指定 IP 和端口，例: connect 192.168.1.100:5555")
                            return@launch
                        }

                        val host: String
                        val port: Int
                        if (target.contains(":")) {
                            val parts = target.split(":")
                            host = parts[0]
                            port = parts.getOrNull(1)?.toIntOrNull() ?: 5555
                        } else {
                            host = target
                            port = tokens.getOrNull(2)?.toIntOrNull() ?: 5555
                        }

                        appendLog("[info] 正在连接 $host:$port ...")
                        client.connect(host, port)
                        appendLog("[success] 连接成功")
                    }

                    "pair" -> {
                        // 支持 2 种格式:
                        // 1. pair 10.45.16.152:42919 088758 (IP:PORT CODE)
                        // 2. pair 10.45.16.152 42919 088758 (IP PORT CODE)
                        val host: String
                        val port: Int
                        val code: String

                        if (tokens.size >= 4) {
                            host = tokens[1]
                            port = tokens[2].toIntOrNull() ?: 0
                            code = tokens[3]
                        } else if (tokens.size >= 3) {
                            val parts = tokens[1].split(":")
                            host = parts[0]
                            port = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            code = tokens[2]
                        } else {
                            appendLog("[error] 配对参数格式错误，例: pair 10.45.16.152:42919 088758")
                            return@launch
                        }

                        if (port == 0 || code.isBlank()) {
                            appendLog("[error] 无效的端口号或验证码")
                            return@launch
                        }

                        appendLog("[info] 正在配对 $host:$port ($code)...")

                        // 必须处理 pair 返回的 Result<String>
                        val pairResult = client.pair(host, port, code)
                        pairResult.onSuccess { msg ->
                            appendLog("[success] 配对成功: $msg")
                        }.onFailure { e ->
                            appendLog("[error] 配对失败: ${e.message}")
                        }
                    }

                    "shell" -> {
                        val cmd = subCmd.removePrefix("shell").trim()
                        val res = client.shell.execV2(cmd)
                        appendLog(res.stdout.ifEmpty { res.stderr })
                    }

                    "install" -> {
                        val path = subCmd.removePrefix("install").trim()
                        val apkFile = File(path)
                        if (!apkFile.exists()) {
                            appendLog("[error] APK 文件不存在: $path")
                            return@launch
                        }
                        appendLog("[info] 正在安装 $path ...")
                        val res = client.installApk(apkFile)
                        res.onSuccess { appendLog("[success] 安装成功") }
                           .onFailure { appendLog("[error] 安装失败: ${it.message}") }
                    }

                    "disconnect" -> {
                        client.disconnect()
                        appendLog("[info] 已断开连接")
                    }

                    else -> {
                        // 透传 Shell 命令
                        val res = client.shell.execV2(subCmd)
                        val output = res.stdout.ifEmpty { res.stderr }
                        appendLog(output.ifEmpty { "[exec finish, exit code ${res.exitCode}]" })
                    }
                }
            }.onFailure { e ->
                appendLog("[error] 执行异常: ${e.localizedMessage}")
            }
        }
    }

    private fun executeSmartIntent(activityContext: ComponentActivity, content: String, packageName: String) {
        try {
            val isUrl = content.startsWith("http://") || content.startsWith("https://")

            val intent = if (isUrl) {
                Intent(Intent.ACTION_VIEW, content.toUri()).apply {
                    if (packageName.isNotEmpty()) {
                        setPackage(packageName)
                    }
                }
            } else {
                Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content)
                    if (packageName.isNotEmpty()) {
                        setPackage(packageName)
                    }
                }
            }

            if (packageName.isNotEmpty()) {
                activityContext.startActivity(intent)
                appendLog("[INFO] Intent 指令已精准发送至: $packageName")
            } else {
                val chooserTitle = if (isUrl) "选择要打开的应用" else "Neko Intent 分享"
                val chooser = Intent.createChooser(intent, chooserTitle)
                activityContext.startActivity(chooser)
                appendLog("[INFO] 已经成功唤起应用选择面板。")
            }

        } catch (e: Exception) {
            appendLog("[error] 执行失败！请检查链接格式或确认目标应用已安装。")
        }
    }

    private fun handleCryptoCommand(cmd: String, isEncrypt: Boolean) {
        appendLog("[INFO] 扩展指令 >> $cmd")
        val prefix = if (isEncrypt) "encrypt " else "decrypt "
        val args = cmd.removePrefix(prefix).trim().split(" ")
        if (args.size < 2) {
            appendLog("[error] 用法: ${prefix.trim()} 文件名 密码")
            return
        }
        val fileName = args[0]
        val password = args[1]
        val targetFile = File(flashFolder, fileName)

        if (!targetFile.exists() || !targetFile.isFile) {
            appendLog("[error] 未找到文件: flash/$fileName")
            return
        }

        if (isEncrypt) {
            val outputFile = File(flashFolder, "$fileName.enc")
            appendLog("[INFO] 正在对 ${fileName} 执行 AES-256 加密...")
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) { CryptoUtils.encryptFile(targetFile, outputFile, password) }
                if (success) appendLog("[INFO] 加密成功！输出文件: flash/${outputFile.name}")
                else appendLog("[error] 加密失败，请检查异常日志")
            }
        } else {
            val outName = if (fileName.endsWith(".enc")) fileName.removeSuffix(".enc") else "$fileName.dec"
            val outputFile = File(flashFolder, outName)
            appendLog("[INFO] 正在解密文件: $fileName ...")
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) { CryptoUtils.decryptFile(targetFile, outputFile, password) }
                if (success) appendLog("[INFO] 解密成功！已还原为: flash/$outName")
                else appendLog("[error] 解密失败！可能是密码错误或文件已被篡改！")
            }
        }
    }
    
    private fun handleQrGenCommand(cmd: String) {
        appendLog("[INFO] 扩展指令 >> $cmd")
        val arg = cmd.removePrefix("qr-gen ").trim()
        if (arg.isEmpty()) {
            appendLog("[error] qr-gen 指令缺少参数！用法: qr-gen 文本或文件名")
            return
        }
        val fileInFlash = File(flashFolder, arg)
        val targetFile = if (fileInFlash.exists() && fileInFlash.isFile) fileInFlash else if (File(arg).exists() && File(arg).isFile) File(arg) else null

        if (targetFile != null) {
            appendLog("[INFO] 匹配到本地文件: ${targetFile.absolutePath}")
            runCatching {
                if (targetFile.length() > 2000) {
                    appendLog("[Warn] 文件过大，已自动降级为【生成文件名二维码】")
                    qrCodeDialogContent = if (targetFile.parentFile?.name == "flash") arg else targetFile.name
                } else {
                    val fileText = targetFile.readText(Charsets.UTF_8).trim()
                    if (fileText.isEmpty()) appendLog("[error] 文件内容为空") 
                    else qrCodeDialogContent = fileText
                }
            }.onFailure {
                appendLog("[error] 读取文件失败，转为对参数文本生成二维码")
                qrCodeDialogContent = arg
            }
        } else {
            if (arg.length > 2000) appendLog("[error] 输入文本过长！") 
            else qrCodeDialogContent = arg
        }
    }

    private fun handleQrDecodeCommand(cmd: String) {
        appendLog("[INFO] 扩展指令 >> $cmd")
        val arg = cmd.removePrefix("qr-decode ").trim()
        if (arg.isEmpty()) return
        if (arg == "--system") {
            openSystemImagePicker()
            return
        }
        val fileInFlash = File(flashFolder, arg)
        val targetFile = if (fileInFlash.exists() && fileInFlash.isFile) fileInFlash else if (File(arg).exists() && File(arg).isFile) File(arg) else null
    
        if (targetFile != null) {
            val result = QrCodeUtils.decodeQrCode(targetFile)
            if (result != null) { qrDecodeResult = result } 
            else appendLog("[error] 二维码解析失败")
        } else {
            appendLog("[error] 未找到指定图片文件: $arg")
        }
    }

    private fun handleApmQuery() {
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val apm = getSystemService(android.security.advancedprotection.AdvancedProtectionManager::class.java)
                val isEnabled = apm?.isAdvancedProtectionEnabled ?: false
                appendLog("[INFO] Android 16+ AAPM: ${if (isEnabled) "【Start 🛡️】" else "【Stop 🔓】"}")
            }.onFailure { appendLog("[error] PM: ${it.message}") }
        } else {
            appendLog("[Warn] No is not Supposed")
        }
    }

    private fun handleLocalShellPipeline(cmd: String) {
        currentShellJob?.cancel()

        val rawCmd = cmd.trim()
        if (rawCmd.isEmpty()) return

        appendLog("[INFO] Shell >> $rawCmd")

        var realLocalCmd = rawCmd
        var requestRoot = false

        if (realLocalCmd == "su -c sh" || realLocalCmd == "su") {
            realLocalCmd = "id"
            requestRoot = true
        } else if (realLocalCmd.startsWith("su -c ")) {
            realLocalCmd = realLocalCmd.removePrefix("su -c ").trim().removeSurrounding("\"", "\"")
            requestRoot = true
        } else if (realLocalCmd.startsWith("su ")) {
            realLocalCmd = realLocalCmd.removePrefix("su ").trim()
            requestRoot = true
        }

        appendLog(if (requestRoot) "[Root] Start" else "[Shell] Start")

        currentShellJob = lifecycleScope.launch {
            val service = adbService
            if (service != null && isServiceBound) {
                var pfd: ParcelFileDescriptor? = null

                try {
                    pfd = withContext(Dispatchers.IO) {
                        service.executeShellStream(this@MainActivity, realLocalCmd, requestRoot)
                    }

                    val logChannel = Channel<List<String>>(Channel.UNLIMITED)

                    coroutineScope {
                        launch(Dispatchers.IO) {
                            try {
                                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { inputStream ->
                                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8), 32768).use { reader ->
                                        val chunkBuffer = ArrayList<String>(128)
                                        var lastChunkTime = System.currentTimeMillis()

                                        while (isActive) {
                                            val line = try {
                                                reader.readLine()
                                            } catch (_: java.io.IOException) {
                                                null
                                            } ?: break

                                            chunkBuffer.add(line)
                                            val now = System.currentTimeMillis()

                                            if (chunkBuffer.size >= 100 || (now - lastChunkTime >= 8)) {
                                                logChannel.send(ArrayList(chunkBuffer))
                                                chunkBuffer.clear()
                                                lastChunkTime = now
                                            }
                                        }

                                        if (chunkBuffer.isNotEmpty()) {
                                            logChannel.send(ArrayList(chunkBuffer))
                                            chunkBuffer.clear()
                                        }
                                    }
                                }
                            } finally {
                                logChannel.close()
                            }
                        }

                        launch(Dispatchers.Main) {
                            val mainBuffer = ArrayList<String>(256)
                            var lastFlushTime = System.currentTimeMillis()

                            for (batch in logChannel) {
                                mainBuffer.addAll(batch)

                                val now = System.currentTimeMillis()
                                if (mainBuffer.size >= 200 || (now - lastFlushTime >= 8)) {
                                    mainBuffer.forEach { appendLog(it) }
                                    mainBuffer.clear()
                                    lastFlushTime = now
                                }
                            }

                            if (mainBuffer.isNotEmpty()) {
                                mainBuffer.forEach { appendLog(it) }
                                mainBuffer.clear()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        appendLog("[error] E: ${e.message}")
                    }
                } finally {
                    runCatching { pfd?.close() }
                }
            } else {
                appendLog("[error] Service is not running")
            }
        }
    }

    fun onUserClickStopCommand() {
        val job = currentShellJob

        if (job == null || !job.isActive) {
            appendLog("[INFO] Nullify")
            return
        }

        job.cancel()
        currentShellJob = null

        lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            val service = adbService
            if (service != null && isServiceBound) {
                runCatching {
                    service.terminateCurrentCommand()
                    withContext(Dispatchers.Main) {
                        appendLog("[INFO] Stop OKAY")
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        appendLog("[error] cmd E: ${e.message}")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    appendLog("[Warn] Service is not running")
                }
            }
        }
    }

    private fun handlePhysicalFallback(cmd: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            when {
                cmd.startsWith("usb-selinux") -> {
                    appendLog("[INFO] FB >> $cmd")
                    appendLog("[INFO] 正在尝试设置 SeLinux 为宽容模式")
                    FbSeLinuxCmd()

                    return@launch
                }

                cmd.startsWith("fastboot") -> {
                    appendLog("[INFO] FB >> $cmd")
                    sendFastbootCommand(cmd)
                    return@launch
                }

                else -> {
                    if (isFastbootMode) {
                        appendLog("[INFO] FB >> $cmd")
                        sendFastbootCommand(cmd)
                    } else {
                        handleLocalShellPipeline(cmd)
                    }
                }
            }
        }
    }

    fun triggerStoragePermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                appendLog("[INFO] Android 11+ File MANAGE OKAY")
            } else {
                runCatching {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:$packageName".toUri()
                    }
                    allFilesPermissionLauncher.launch(intent)
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val hasPermission = permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }

            if (hasPermission) {
                appendLog("[INFO] Android 10 File STORAGE OKAY")
            } else {
                legacyStorageLauncher.launch(permissions)
            }
        }
    }

    private fun openSystemImagePicker() {
        try {
            pickImageLauncher.launch("image/*")
        } catch (e: Exception) {
            appendLog("[error] 无法打开系统图片选择器: ${e.localizedMessage}")
        }
    }
    
    fun tryToStartService() {
        if (isBindingRequested) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                startAndBindAdbService()
            } else {
                appendLog("[INFO] Android 13+ Notification OKAY")
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                appendLog("[INFO] Notification OKAY")
                startAndBindAdbService()
            } else {
                handlePermissionDeniedSituation()
                startAndBindAdbService()
            }
        }
    }
    
    private fun handlePermissionDeniedSituation() {
        appendLog("[error] ❌ Notification pm")
        appendLog("[Warn] ⚠️ Notification")
    }
    
    private fun startAndBindAdbService() {
        if (isBindingRequested) return
        isBindingRequested = true
        val intent = Intent(this, AdbSessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun executeDownload(urlStr: String) {
        val uri = urlStr.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            appendLog("[error] 下载失败！该指令仅支持 http:// 或 https:// 的网络地址")
            return
        }

        val serviceInstance = adbService
        if (serviceInstance != null && isServiceBound) {
            serviceInstance.executeDownloadFromService(urlStr, flashFolder) { logText ->
                appendLog(logText)
            }
        } else {
            appendLog("[error] 核心前台进程未并网或已断开，拒绝执行网络下载")
        }
    }

    fun FbSeLinuxCmd() {
        if (!isFastbootMode) {
             appendLog("[Warn] 该命令只能在 Fastboot 模式使用")
           return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val cmds = listOf(
                  "oem set-gpu-preemption 0 androidboot.selinux=permissive",
                  "continue"
            )
            for (cmd in cmds) {
               // 1. 先把要发的命令打印出来
               withContext(Dispatchers.Main) { 
                  appendLog("[INFO] FB >> $cmd") 
               }
               // 2. 发送原始指令 (调用临时执行方法)
               sendFastbootCommand(cmd)
               // 3. 等待设备响应（如果有）
                delay(500) 
            }
        }
    }

    fun findHostDevice() {
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            appendLog("[INFO] 未发现 USB 设备")
            return
        }

        for (device in devices.values) {
            // 使用 StringBuilder 减少频繁字符串拼接和日志输出次数
            val logBuilder = StringBuilder().apply {
                // 设备基本信息
                appendLine("物理设备: ${device.productName ?: "unknown"}")
                appendLine("制造商: ${device.manufacturerName ?: "unknown"}")
                appendLine("VID: ${device.vendorId} | PID: ${device.productId} | 版本: ${device.version}")
                appendLine("全局类: Class=${device.deviceClass}, Subclass=${device.deviceSubclass}, Protocol=${device.deviceProtocol}")

                // 配置层 (Configuration)
                val configCount = device.configurationCount
                appendLine("拥有配置(Configuration)数量: $configCount")

                for (c in 0 until configCount) {
                    val config = device.getConfiguration(c)
                    // config.name
                    appendLine("[配置 $c] ID: ${config.id}, 名称: ${config.name ?: "unknown"}")
                    appendLine("属性: MaxPower=${config.maxPower}mA, RemoteWakeup=${config.isRemoteWakeup}, SelfPowered=${config.isSelfPowered}")

                    // 接口层 (Interface)
                    val interfaceCount = config.interfaceCount
                    for (i in 0 until interfaceCount) {
                        val intf = config.getInterface(i)
                        appendLine("[接口 $i] ID: ${intf.id}, 名称: ${intf.name ?: "unknown"}")
                        // intf.alternateSetting (替代设置)
                        appendLine("属性: AlternateSetting=${intf.alternateSetting}, Class=${intf.interfaceClass}, Subclass=${intf.interfaceSubclass}, Protocol=${intf.interfaceProtocol}")

                        // 端点层 (Endpoint)
                        for (j in 0 until intf.endpointCount) {
                            val ep = intf.getEndpoint(j) ?: continue
                            val isInput = (ep.address and 0x80) != 0
                            val direction = if (isInput) "IN (设备->手机)" else "OUT (手机->设备)"
                            val epNumber = ep.address and 0x7F

                            // 端点传输类型识别
                            val transferType = when (ep.type) {
                                UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK (批量传输)"
                                UsbConstants.USB_ENDPOINT_XFER_INT -> "INT (中断传输)"
                                UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC (同步传输)"
                                UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL (控制)"
                                else -> "UNKNOWN (${ep.type})"
                            }

                            // ep.interval (传输间隔)
                            appendLine("[- 端点 $j] 物理地址=${ep.address} (方向: $direction, 编号: $epNumber)")
                            appendLine("传输属性: 类型: $transferType, MaxPacketSize=${ep.maxPacketSize}, 轮询间隔(Interval)=${ep.interval}")
                        }
                    }
                }
            }
            appendLog(logBuilder.toString())

            val (mode, targetIntf) = UsbDeviceMode.matchDevice(device)
            appendLog("--- 通过USB连接输出 ---")

            if (mode != UsbDeviceMode.UNKNOWN && targetIntf != null) {
                isUsbAttached = true
                isFastbootMode = (mode == UsbDeviceMode.FASTBOOT || mode == UsbDeviceMode.FASTBOOTD_CUSTOM)

                appendLog("--- 检测到兼容设备: ${mode.displayName} ---")

                if (!usbManager.hasPermission(device)) {
                    val flags = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> { // Android 14+
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> { // Android 12 & 13
                            PendingIntent.FLAG_MUTABLE
                        }
                        else -> 0 // Android 7.0 to 11
                    }

                    val intent = Intent(ACTION_USB_PERMISSION).apply {
                        setPackage(packageName) // 限制接收者包名以提高安全性
                    }

                    val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
                    usbManager.requestPermission(device, pi)
                } else {
                    // 获取物理设备节点路径，例如 /dev/bus/usb/001/002
                    val nodePath = device.deviceName
                    appendLog("[INFO] USB物理节点: $nodePath")

                    // 注意：在没有权限时直接获取 serialNumber 可能会返回 null 或抛出 SecurityException
                    val serial = try { device.serialNumber } catch (e: SecurityException) { "Permission Denied" }
                    appendLog("[INFO] 硬件序列号: ${serial ?: "unknown"}")

                    // 派发给 Service 处理
                    adbService?.connectToInterface(device, flashFolder)
                }
                return
            }
        }
        appendLog("发现设备但无 ADB/Fastboot/9008/MTK 接口")
    }

    fun sendFastbootCommand(commandText: String) {
        adbService?.runFastbootCommand(commandText)
            ?: appendLog("[error] 服务未绑定，无法发送指令")
    }

    private fun exportLogToFlashFolder() {
        if (viewModel.isLogEmpty) {
            appendLog("[Warn] 当前控制台日志空空如也")
            return
        }

        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val timeStamp = LocalDateTime.now().format(formatter)
        val fileName = "Log_$timeStamp.txt"
        val targetFile = File(logsFolder, fileName)

        if (targetFile.parentFile?.exists() == false) {
            targetFile.parentFile?.mkdirs()
        }

        lifecycleScope.launch {
            val isSuccess = viewModel.exportFullLogToFile(targetFile)

            if (isSuccess) {
                appendLog("[INFO] 🎉 日志已成功安全写入文件：${targetFile.absolutePath}")
            } else {
                appendLog("[error] ❌ 写入文件时发生异常，请检查磁盘权限或空间是否充足。")
            }
        }
    }

    private fun getWifiScanPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    fun startIpNetworkTest() {
        // 合并为一个统一的 IO 协程流，确保控制台输出的时序绝对工整不乱序
        lifecycleScope.launch(Dispatchers.Main) {
            appendLog("[网络探针] 正在唤醒底层网络数据透视…")

            val ipManager = IpManager()
            
            // 1. 抓取本地物理与虚拟网卡快照
            val localIp = withContext(Dispatchers.IO) { ipManager.getAllLocalIpAddresses() }
            appendLog("[本地 Wi-Fi 网卡] IPv4: ${localIp.wifiIpv4 ?: "未连接"}")
            appendLog("[本地 Wi-Fi 网卡] IPv6: ${localIp.wifiIpv6 ?: "无IPv6"}")
            appendLog("[本地移动网卡] IPv4: ${localIp.mobileIpv4 ?: "未开启"}")
            appendLog("[本地移动网卡] IPv6: ${localIp.mobileIpv6 ?: "无IPv6"}")
            appendLog("[本地 VPN 网卡] IPv4: ${localIp.vpnIpv4 ?: "未创建"}")
            
            // 2. 串行测试全球 IPv4 出口（多节点测绘防御单点崩溃）
            appendLog("[探针] 正在向全球 IPv4 节点发射探测信标...")
            val vpnOuterIpv4 = fetchIpFromWeb("https://api.ipify.org")
            appendLog("[外网出口] 测试 IPv4 (api.ipify.org) -> ${vpnOuterIpv4 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv41 = fetchIpFromWeb("https://v4.ident.me")
            appendLog("[外网出口] 测试 IPv4 (v4.ident.me) -> ${vpnOuterIpv41 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv42 = fetchIpFromWeb("https://ipv4.icanhazip.com")
            appendLog("[外网出口] 测试 IPv4 (ipv4.icanhazip.com) -> ${vpnOuterIpv42 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv43 = fetchIpFromWeb("https://myip.dnsomatic.com")
            appendLog("[外网出口] 测试 IPv4 (myip.dnsomatic.com) -> ${vpnOuterIpv43 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv44 = fetchIpFromWeb("https://api-ipv4.ip.sb/ip")
            appendLog("[外网出口] 测试 IPv4 (api-ipv4.ip.sb/ip) -> ${vpnOuterIpv44 ?: "连接失败(可能无v4网络或代理断开)"}")

            // 3. 串行测试全球 IPv6 出口
            appendLog("[探针] 正在向全球 IPv6 节点发射探测信标...")
            val vpnOuterIpv6 = fetchIpFromWeb("https://api6.ipify.org")
            appendLog("[外网出口] 测试 IPv6 (api6.ipify.org) -> ${vpnOuterIpv6 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv61 = fetchIpFromWeb("https://v6.ident.me")
            appendLog("[外网出口] 测试 IPv6 (v6.ident.me) -> ${vpnOuterIpv61 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv62 = fetchIpFromWeb("https://ipv6.icanhazip.com")
            appendLog("[外网出口] 测试 IPv6 (ipv6.icanhazip.com) -> ${vpnOuterIpv62 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv63 = fetchIpFromWeb("https://api-ipv6.ip.sb/ip")
            appendLog("[外网出口] 测试 IPv6 (api-ipv6.ip.sb/ip) -> ${vpnOuterIpv63 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            // 4. 双通道直连骨干网测试
            appendLog("[探针] 正在评测全球骨干网连通度...")
            val isV4Ok = verifyGoogleOutbound("https://ipv4.google.com/generate_204")
            val isV6Ok = verifyGoogleOutbound("https://ipv6.google.com/generate_204")
            appendLog("[Google通道] 物理/VPN IPv4 直连状态: ${if(isV4Ok) "🟢 畅通" else "🔴 阻塞"}")
            appendLog("[Google通道] 物理/VPN IPv6 直连状态: ${if(isV6Ok) "🟢 畅通" else "🔴 阻塞"}")
            
            // 5. 抓取本地虚拟 VPN 网卡底层的 IPv6 状态
            val vpnIpManager = VpnIpManager()
            val localVpnIpv6 = withContext(Dispatchers.IO) { vpnIpManager.getLocalVpnIpv6(applicationContext) }
            if (localVpnIpv6 != null) {
                appendLog("[本地 VPN 网卡] 成功抓取本地 VPN IPv6 地址: $localVpnIpv6")
            } else {
                appendLog("[本地 VPN 网卡] 提示: 未检测到本地 VPN 的 IPv6 地址 (VPN未开启，或该VPN软件底层未分配IPv6虚拟网卡)")
            }
            
            appendLog("[系统] === 全网环境深度检测结束 ===")
        }
    }
    
    private suspend fun verifyGoogleOutbound(urlString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            return@withContext conn.responseCode == 204
        } catch (e: Exception) {
            return@withContext false
        }
    }
    
    private suspend fun fetchIpFromWeb(urlString: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    reader.readLine()?.trim()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        stopAdbService()
        currentShellJob?.cancel()
        super.onDestroy()
        readerJob?.cancel()
        usbConn?.close()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(systemReceiver)
    }
}

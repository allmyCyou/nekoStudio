package com.adb.kitty.service

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.data.*
import com.adb.kitty.data.fastboot.*
import com.adb.kitty.R
import com.adb.kitty.*

import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import android.graphics.*

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityOptions
import android.app.AppOpsManager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo

import android.net.wifi.WifiManager
import android.net.wifi.p2p.*

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Process
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock

import android.system.Os
import android.system.OsConstants

import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.createBitmap
import androidx.window.layout.WindowMetricsCalculator
import android.webkit.MimeTypeMap
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import androidx.annotation.CallSuper

import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass

import java.io.*
import java.net.*
import java.lang.reflect.*

@Keep
class AdbSessionService : Service() {

    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "com.adb.kitty.core_service_channel_v1"
    private val GROUP_ID = "com.adb.kitty.core_service_group"
    private val MAX_LOG_COUNT = 1
    private val notificationLogs = mutableListOf<String>()

    companion object {
        private const val ACTION_REPLY_COMMAND = "com.adb.kitty.ACTION_REPLY_COMMAND"
        const val ACTION_START_RECORDING = "com.adb.kitty.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.adb.kitty.ACTION_STOP_RECORDING"
        private const val KEY_REPLY_INPUT = "com.adb.kitty.service_key_reply_input"
    }

    private var usbConn: UsbDeviceConnection? = null
    var fastbootManager: FastbootManager? = null
        private set

    private val _logFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logFlow = _logFlow.asSharedFlow()

    private val responseChannel = Channel<String>(Channel.UNLIMITED)

    fun appendLog(msg: String) {
        serviceScope.launch {
            _logFlow.emit(msg)
        }
    }

    private var lastCommand: String? = null
    private var cachedCircularIcon: IconCompat? = null
    var onCommandReceivedListener: ((String) -> Unit)? = null

    @Volatile
    private var currentWorkingDirectory: File = Environment.getExternalStorageDirectory()

    @Volatile
    private var currentShellProcess: java.lang.Process? = null

    @Volatile
    private var currentTaskKey: String? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    private val binder = AdbBinder()

    inner class AdbBinder : Binder() {
        fun getService(): AdbSessionService = this@AdbSessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun logToNotification(log: String) {
        synchronized(notificationLogs) {
            if (notificationLogs.size >= MAX_LOG_COUNT) {
                notificationLogs.removeAt(0)
            }
            notificationLogs.add(log)
        }
        triggerTickerRefreshImmediate()
    }

    private var currentText: String = "00:00:00"
    private var activeForegroundTypes: Int = 0
    private var serviceStartTime = 0L

    override fun onCreate() {
        super.onCreate()
        serviceStartTime = SystemClock.elapsedRealtime()

        createNotificationChannel()

        // 1. 初始注册前台服务（传入默认 0 增量）
        updateForegroundType()

        // 运行前台服务被要求在 1秒或者2秒 内发送通知，必需发送通知，哪怕用户没有授予通知权限，也是可以正常运行的，通知并不会影响到前台服务，唯一受影响的只有视觉上
        updateShortcutIfNeeded()
        startNotificationTicker()
    }

/*
*  增量追加某种类型，前提是已经在清单中声明了该类型
*  updateForegroundType(
*    addTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
*  )
*
*  增量移除某种类型，请务必等任务圆满完成之后再移除，否则将抛出权限崩溃异常，直接关闭整个 Service 或 Activity
*  updateForegroundType(
*    removeTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
*  )
*
*  同时追加与移除前台服务类型
*  updateForegroundType(
*      addTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
*      removeTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
*  )
*/

    /**
     * 动态更新前台服务类型（支持增量追加和增量移除）
     * @param addTypes 需要新增的 ServiceInfo.FOREGROUND_SERVICE_TYPE_* 掩码
     * @param removeTypes 需要移除的 ServiceInfo.FOREGROUND_SERVICE_TYPE_* 掩码
     */
    fun updateForegroundType(addTypes: Int = 0, removeTypes: Int = 0) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(currentText))
            return
        }

        // 1. 如果尚未初始化基础类型，先计算基础类型
        if (activeForegroundTypes == 0) {
            activeForegroundTypes = getBaseForegroundTypes()
        }

        // 2. 增量追加 + 增量移除（按位与取反）
        activeForegroundTypes = (activeForegroundTypes or addTypes) and removeTypes.inv()

        // 3. 提交更新到系统
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(currentText),
                activeForegroundTypes
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 封装获取应用基础必备类型
     */
    private fun getBaseForegroundTypes(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING

            val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOpsManager.checkOpNoThrow(
                "android:foreground_service_special_use",
                android.os.Process.myUid(),
                packageName
            )

            if (mode == AppOpsManager.MODE_ALLOWED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            return types
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return 0
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPLY_COMMAND -> handleNotificationInput(intent)
            ACTION_START_RECORDING -> acquireWakeLock()
            ACTION_STOP_RECORDING -> releaseWakeLock()
        }
        return START_STICKY
    }

    private fun handleNotificationInput(intent: Intent) {
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        val inputText = remoteInputResults?.getCharSequence(KEY_REPLY_INPUT)?.toString()

        if (!inputText.isNullOrBlank()) {
            lastCommand = inputText

            serviceScope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    onCommandReceivedListener?.invoke(inputText)
                }
            }

            logToNotification("📡 OKAY: $inputText")
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null || wakeLock?.isHeld == false) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NekoStudio:RecordingWakeLock"
            ).apply {
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun startNotificationTicker() {
        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            while (isActive) {
                updateTickerNotification()
                // 此时 delay 被睡眠暂停也没关系，醒来后计算差值依旧准确
                delay(52000) 
            }
        }
    }

    private fun updateTickerNotification() {
        // 始终使用当前硬件时间减去服务最初创建的时间
        val elapsedMillis = SystemClock.elapsedRealtime() - serviceStartTime
        val totalSeconds = (elapsedMillis / 1000).toInt()

        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val timeString = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

        updateNotification(timeString)
    }

    private fun triggerTickerRefreshImmediate() {
        serviceScope.launch {
            updateTickerNotification()
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private val SHORTCUT_ID = "com.adb.kitty.service_shortcut_id"
    private val PERSON_KEY = "com.adb.kitty.service_person_key_001"

    private var cachedReplyAction: NotificationCompat.Action? = null
    private var cachedOpenAppAction: NotificationCompat.Action? = null

    private var cachedConsoleUser: Person? = null
    private val anonymousSender: Person by lazy {
        Person.Builder().setName("").build()
    }

    private fun getConsoleUser(): Person {
        var user = cachedConsoleUser
        if (user == null) {
            user = Person.Builder()
                .setName(getString(R.string.action_service_aaa))
                .setIcon(getCircularIcon())
                .setKey(PERSON_KEY)
                .build()
            cachedConsoleUser = user
        }
        return user
    }

    private fun getReplyAction(): NotificationCompat.Action {
        var replyAction = cachedReplyAction
        if (replyAction == null) {
            val remoteInput = RemoteInput.Builder(KEY_REPLY_INPUT)
                .setLabel(getString(R.string.action_service_aad))
                .build()

            val replyIntent = Intent(this, AdbSessionService::class.java).apply {
                this.action = ACTION_REPLY_COMMAND
            }

            val replyPendingIntent = PendingIntent.getService(
                this,
                0,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                getString(R.string.action_service_aab),
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            cachedReplyAction = replyAction
        }
        return replyAction
    }

    private fun getOpenAppAction(): NotificationCompat.Action {
        var openAction = cachedOpenAppAction
        if (openAction == null) {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

                // 仅在 Android 10+ 上附带厂商私有小窗 Extra
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    putExtra("miui.intent.extra.open_in_freeform", true)
                    putExtra("intent_flag_miui_freeform", true)
                    putExtra("com.mbridge.msdk.intent.extra.open_in_freeform", true)
                }
            }

            var optionsBundle: Bundle? = null

            // 只有 Android 10 (API 29) 及以上才去计算小窗尺寸并反射参数
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val screenWidth: Int
                val screenHeight: Int

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm.currentWindowMetrics.bounds
                    screenWidth = bounds.width()
                    screenHeight = bounds.height()
                } else {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(metrics)
                    screenWidth = metrics.widthPixels
                    screenHeight = metrics.heightPixels
                }

                val windowWidth = (screenWidth * 0.85).toInt()
                val windowHeight = (screenHeight * 0.60).toInt()
                val left = (screenWidth - windowWidth) / 2
                val top = (screenHeight - windowHeight) / 2

                val options = ActivityOptions.makeBasic().apply {
                    launchBounds = Rect(left, top, left + windowWidth, top + windowHeight)
                    try {
                        val method = ActivityOptions::class.java.getMethod(
                            "setLaunchWindowingMode",
                            Int::class.javaPrimitiveType
                        )
                        method.invoke(this, 5) // WINDOWING_MODE_FREEFORM
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                optionsBundle = options.toBundle()
            }

            val openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                optionsBundle
            )

            openAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_view,
                getString(R.string.action_service_aac),
                openPendingIntent
            ).build()

            cachedOpenAppAction = openAction
        }
        return openAction
    }

    /**
     * 动态 Shortcut 独立更新（仅在 onCreate 或更换头像时调用）
     */
    private fun updateShortcutIfNeeded() {
        val newIntent = Intent().apply {
            action = Intent.ACTION_VIEW
            setClassName("com.android.settings", "com.android.settings.SubSettings")
            putExtra(":settings:show_fragment", "com.android.settings.development.WirelessDebuggingFragment")
        
            // 跨应用/从 Service 启动必须带上 NEW_TASK 标志
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val shortcut = ShortcutInfoCompat.Builder(this, SHORTCUT_ID)
            .setShortLabel(getString(R.string.action_service_aaa))
            .setIcon(getCircularIcon())
            .setIntent(newIntent)
            .setPerson(getConsoleUser())
            .setLongLived(true)
            .setIsConversation()
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)

        ShortcutManagerCompat.disableShortcuts(
            this,
            listOf("adb_conversation_shortcut_id"),
            "delete"
        )
    }

    /**
     * 🏗️ 生产通知对象的工厂方法
     */
    private fun buildNotification(contentText: String): Notification {
        val consoleUser = getConsoleUser()

        val messagingStyle = NotificationCompat.MessagingStyle(consoleUser)
            .setConversationTitle(getString(R.string.action_service_aae))

        val lastLog = synchronized(notificationLogs) {
            notificationLogs.lastOrNull()
        }
        val line1Text = lastLog ?: "📡 Nullify"

        val timeText = getString(R.string.action_service_aaj)
        val line2Text = "⏱️ $timeText: $contentText"

        val now = System.currentTimeMillis()
        messagingStyle.addMessage(line1Text, now - 1000, anonymousSender)
        messagingStyle.addMessage(line2Text, now, anonymousSender)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_small_kiss)
            .setStyle(messagingStyle)
            .setShortcutId(SHORTCUT_ID)
            .setBubbleMetadata(null)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true) 
            .setOnlyAlertOnce(true) 
            .addAction(getReplyAction())
            .addAction(getOpenAppAction())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        try {
            val oldChannelId = "com.adb.kitty.core_service_channel_v2"
            val existingChannel = manager.getNotificationChannel(oldChannelId)
            if (existingChannel != null) {
                manager.deleteNotificationChannel(oldChannelId)
            }

            val groupName = getString(R.string.action_service_aaf)
            val channelGroup = NotificationChannelGroup(GROUP_ID, groupName)
            manager.createNotificationChannelGroup(channelGroup)

            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.action_service_aag),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.action_service_aah)
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250)
                setSound(null, null)
            }

            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun reloadAvatar() {
        cachedCircularIcon = null
        cachedConsoleUser = null
        updateShortcutIfNeeded()
        triggerTickerRefreshImmediate()
    }

    private fun getCircularIcon(): IconCompat {
        cachedCircularIcon?.let { return it }

        val avatarFile = File(filesDir, "custom_avatar.png")
        var srcBitmap: Bitmap? = null

        if (avatarFile.exists()) {
            runCatching {
                srcBitmap = BitmapFactory.decodeFile(avatarFile.absolutePath)
            }
        }

        if (srcBitmap == null) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                val tempBitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_service_icon, options)

                tempBitmap?.let {
                    val size = Math.min(it.width, it.height)
                    val dstBitmap = createBitmap(size, size)
                    val canvas = Canvas(dstBitmap)
                    val paint = Paint().apply { isAntiAlias = true }
                    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(it, Rect((it.width - size) / 2, (it.height - size) / 2, (it.width + size) / 2, (it.height + size) / 2), Rect(0, 0, size, size), paint)
                    it.recycle()
                    srcBitmap = dstBitmap
                }
            }
        }

        val finalBitmap = srcBitmap ?: return IconCompat.createWithResource(this, android.R.drawable.ic_dialog_info)

        val finalIcon = IconCompat.createWithBitmap(finalBitmap)
        cachedCircularIcon = finalIcon
        return finalIcon
    }

    /**
     * 根据设备识别模式构建对应的物理通信管道
     */
    fun connectToInterface(device: UsbDevice, flashFolder: File) {
        serviceScope.launch(Dispatchers.IO) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val (mode, intf) = UsbDeviceMode.matchDevice(device)

            if (mode == UsbDeviceMode.UNKNOWN || intf == null) {
                appendLog("[Warn] 无法识别该设备的硬件通信协议或无可用接口")
                return@launch
            }

            appendLog("[INFO] 正在建立物理管道，模式: ${mode.displayName}")

            val conn = usbManager.openDevice(device)
            if (conn == null) {
                appendLog("[error] 打开 USB 设备失败，请检查 USB 权限")
                return@launch
            }

            if (!conn.claimInterface(intf, true)) {
                conn.close()
                appendLog("[error] 占用 USB 接口 (claimInterface) 失败")
                return@launch
            }

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    epIn = ep
                } else {
                    epOut = ep
                }
            }

            if (epIn == null || epOut == null) {
                conn.releaseInterface(intf)
                conn.close()
                appendLog("[error] 端点配置异常: 未能同时获得 IN 和 OUT 端点")
                return@launch
            }

            this@AdbSessionService.usbConn = conn
            val serialNumber = runCatching { device.serialNumber }.getOrNull() ?: "unknown"

            when (mode) {
                UsbDeviceMode.FASTBOOT, UsbDeviceMode.FASTBOOTD_CUSTOM -> {
                    fastbootManager = FastbootManager(
                        scope = serviceScope,
                        usbConn = conn,
                        epOut = epOut,
                        epIn = epIn,
                        responseChannel = responseChannel,
                        flashFolder = flashFolder,
                        context = this@AdbSessionService,
                        usbManager = usbManager,
                        usbDevice = device
                    ).apply {
                        startFastbootReader()
                    }

                    serviceScope.launch {
                        fastbootManager?.logFlow?.collect { msg -> appendLog(msg) }
                    }

                    appendLog("[INFO] ${mode.displayName} 物理信道已就绪 | 序列号: $serialNumber")
                }

                UsbDeviceMode.ADB -> {
                    appendLog("[INFO] ADB 设备已建立物理管道，暂不支持")
                }

                UsbDeviceMode.QUALCOMM_9008 -> {
                    appendLog("[INFO] 高通 EDL 9008 模式已激活，准备握手 Sahara / Firehose 协议，暂不支持")
                    // TODO: 初始化 9008 协议管理器 (例如 setupQcom9008Manager(conn, epOut, epIn))
                }

                UsbDeviceMode.QUALCOMM_900E -> {
                    appendLog("[Warn] 高通 900E 诊断模式已连接 (通常需要加载 Diagnostics 驱动或切换模式)")

                    if (device.configurationCount > 0) {
                        val config = device.getConfiguration(0)
                        val maxPower = config.maxPower
                        val isSelfPowered = config.isSelfPowered

                        appendLog("📊 [硬件诊断] 电源配置属性: 最大供电需求 = ${maxPower}mA | 电池自供电状态 = $isSelfPowered")
  
                        // Output localized analysis based on electrical traits
                        when {
                            maxPower == 0 && isSelfPowered -> {
                                appendLog("💡 [分析] 设备目前处于自供电状态，依赖内部电池运行。")
                            }
                            maxPower in 1..499 -> {
                                appendLog("💡 [分析] 供电限制偏低 (${maxPower}mA)，芯片引导可能停滞在极早期硬件自检阶段。")
                            }
                            maxPower >= 500 -> {
                                appendLog("💡 [分析] 设备已申请标准 USB 大电流通道。")
                            }
                        }
                    } else {
                        appendLog("🚨 [错误] 无法读取该设备的 USB 配置树描述符。")
                    }

                    appendLog("👉 [恢复建议] 900E 无法直接进行刷机写入。请指导用户长按 [电源键 + 音量减] 强制重启，或配合 EDL 线 / 短接点强切至 9008 救砖。")
                }

                UsbDeviceMode.MTK_PRELOADER, UsbDeviceMode.MTK_BROM -> {
                    appendLog("[INFO] MTK 联发科硬件已就绪，准备握手 BROM / Preloader 串行命令，暂不支持")
                    // TODO: 初始化 MTK Handshake 模块
                }

                UsbDeviceMode.UNKNOWN -> {
                    appendLog("[INFO] 设备连接成功: ${mode.displayName}")
                }
            }
        }
    }

    fun runFastbootCommand(cmd: String) {
        serviceScope.launch(Dispatchers.IO) {
            val manager = fastbootManager
            if (manager == null) {
                appendLog("[error] Fastboot 驱动未就绪，请检查硬件通信。")
                return@launch
            }
            try {
                manager.executeCommandSync(cmd)
            } catch (e: Exception) {
                appendLog("[error] 物理管道执行崩溃: ${e.message}")
            }
        }
    }

    fun closeUsbConnection() {
        serviceScope.launch(Dispatchers.IO) {
            fastbootManager = null
            usbConn?.close()
            usbConn = null
            appendLog("[Warn] USB 硬件管道已安全关闭")
        }
    }

    fun executeDownloadFromService(urlStr: String, flashFolder: File, onLog: (String) -> Unit) {
        val uri = urlStr.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            onLog("[error] No is http:// & https:// Link")
            return
        }

        onLog("[INFO] Start downloading")

        refreshJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val contentLength = connection.contentLengthLong

                    var fileName = urlStr.substringAfterLast("/").substringBefore("?")
                    if (fileName.isEmpty() || !fileName.contains(".")) {
                        val contentType = connection.contentType
                        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType) ?: "bin"
                        fileName = "download_${System.currentTimeMillis()}.$extension"
                    }
   
                    val targetFile = File(flashFolder, fileName)

                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    val startTime = System.currentTimeMillis()
                    var lastLogTime = startTime

                    connection.inputStream.use { inputStream ->
                        targetFile.outputStream().use { outputStream ->
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastLogTime >= 500) {
                                    val elapsedSec = (now - startTime) / 1000.0
                                    val speedMbPerSec = if (elapsedSec > 0) (totalBytesRead / (1024.0 * 1024.0)) / elapsedSec else 0.0

                                    withContext(Dispatchers.Main) {
                                        if (contentLength > 0) {
                                            val progress = (totalBytesRead.toDouble() / contentLength * 100).toInt()
                                            onLog(String.format(Locale.US, "[INFO] ⚡ Ongoing: %d%% | speed: %.2f MB/s | time: %.1f 秒", progress, speedMbPerSec, elapsedSec))
                                        } else {
                                            val downloadedMb = totalBytesRead / (1024.0 * 1024.0)
                                            onLog(String.format(Locale.US, "[INFO] ⚡ Ongoing: %.2f MB | speed: %.2f MB/s | time: %.1f 秒", downloadedMb, speedMbPerSec, elapsedSec))
                                        }
                                    }
                                    lastLogTime = now
                                }
                            }
                            outputStream.flush()
                        }
                    }

                    val totalTimeSec = (System.currentTimeMillis() - startTime) / 1000.0
                    val avgSpeed = if (totalTimeSec > 0) (totalBytesRead / (1024.0 * 1024.0)) / totalTimeSec else 0.0

                    withContext(Dispatchers.Main) {
                        onLog("[OKAY] 🎉 download OKAY！")
                        onLog("[OKAY] Save to flach directory: ${targetFile.name}")
                        onLog(String.format(Locale.US, "[INFO] Total duration: %.2f 秒 | mean velocity: %.2f MB/s", totalTimeSec, avgSpeed))
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onLog("[error] Status code: ${connection.responseCode}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onLog("[error] network: ${e.localizedMessage}")
                }
            }
        }
    }

    fun executeShellStream(context: Context, cmd: String, useRoot: Boolean): ParcelFileDescriptor {
        terminateCurrentCommand()

        val taskKey = "TASK_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        val cwdSnapshot = synchronized(this) {
            currentTaskKey = taskKey
            val trimmedCmd = cmd.trim()
            if (trimmedCmd == "cd" || trimmedCmd.startsWith("cd ")) {
                val cdResult = handleCdCommand(trimmedCmd)
                writeDirectMessageToPipe(writeSide, cdResult)
                return readSide
            }
            currentWorkingDirectory
        }

        thread(start = true, name = "AdbServiceShellThread") {
            var process: java.lang.Process? = null
            var os: DataOutputStream? = null

            try {
                val safeCmd = sanitizeCommand(cmd)

                val builder = if (useRoot || safeCmd.startsWith("su")) {
                    val baseSuCmd = if (safeCmd.contains(" -c ")) safeCmd.substringBefore(" -c ") else safeCmd
                    val args = parseCommandLine(baseSuCmd.ifBlank { "su" })

                    if (useRoot && !args.contains("su")) {
                        ProcessBuilder("su")
                    } else {
                        ProcessBuilder(args)
                    }
                } else {
                    ProcessBuilder("sh")
                }

                builder.redirectErrorStream(true)

                val envMap = builder.environment()
                envMap.putAll(System.getenv())
                envMap["SHELL_TASK_KEY"] = taskKey

                // 让应用 lib 目录下的可执行文件作为 Shell 的环境
                envMap["PATH"] = "$nativeLibDir:${envMap["PATH"]}"

                builder.directory(cwdSnapshot)

                process = builder.start()
                synchronized(this) {
                    currentShellProcess = process
                }

                os = DataOutputStream(process.outputStream)

                val realExecutionCmd = when {
                    safeCmd.contains(" -c ") -> {
                        safeCmd.substringAfter(" -c ").trim {
                            it == '\'' || it == '"' || it.isWhitespace() || it.code == 160
                        }
                    }
                    safeCmd == "su" || safeCmd.startsWith("su ") -> "id"
                    else -> safeCmd
                }

                val taggedCmd = "export SHELL_TASK_KEY='$taskKey'; $realExecutionCmd"

                os.writeBytes("$taggedCmd\n")
                os.writeBytes("exit\n")
                os.flush()

                val activeProcess = process
                startAntiStallPump(
                    processInputStream = activeProcess.inputStream,
                    ipcOutputStream = ParcelFileDescriptor.AutoCloseOutputStream(writeSide),
                    onPumpComplete = { stream ->
                        val exitCode = runCatching { activeProcess.waitFor() }.getOrDefault(-1)
                        val exitMsg = "\n[Shell] 进程结束，状态码: $exitCode\n".toByteArray(Charsets.UTF_8)
                        runCatching {
                            stream.write(exitMsg)
                            stream.flush()
                        }
                    }
                )
            } catch (e: Exception) {
                runCatching {
                    OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(writeSide), "UTF-8").use { writer ->
                        val errorMsg = if (useRoot && e is java.io.IOException) {
                            "su 命令调用被拒绝：请在 Root 管理器中允许超级用户请求，并始终开启 su 命令支持\n"
                        } else {
                            "执行中断或异常: ${e.message}\n"
                        }
                        writer.write(errorMsg)
                        writer.flush()
                    }
                }
            } finally {
                synchronized(this) {
                    if (currentShellProcess == process) {
                        currentShellProcess = null
                    }
                    if (currentTaskKey == taskKey) {
                        currentTaskKey = null
                    }
                }
                runCatching { os?.close() }
                process?.let { killProcessTree(it, taskKey) }
            }
        }

        return readSide
    }

    /**
     * 强行终止当前 Shell 任务
     */
    fun terminateCurrentCommand() {
        synchronized(this) {
            val key = currentTaskKey
            currentTaskKey = null

            if (!key.isNullOrEmpty()) {
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f '$key'"))
                }
            }

            currentShellProcess?.let { proc ->
                killProcessTree(proc, key)
                currentShellProcess = null
            }
        }
    }

    fun getCurrentWorkingDirectory(): File = currentWorkingDirectory

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startAntiStallPump(
        processInputStream: InputStream,
        ipcOutputStream: OutputStream,
        onPumpComplete: (OutputStream) -> Unit
    ) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val channel = Channel<ByteArray>(Channel.UNLIMITED)

        val drainThread = Thread({
            val rawBuffer = ByteArray(32768)
            try {
                var bytesRead: Int
                while (processInputStream.read(rawBuffer).also { bytesRead = it } != -1) {
                    val chunk = rawBuffer.copyOf(bytesRead)
                    channel.trySend(chunk)
                }
            } catch (_: Exception) {
            } finally {
                channel.close()
            }
        }, "NativePipeDrainer")

        drainThread.start()

        val ipcWriterThread = Thread({
            try {
                runBlocking {
                    val batchBuffer = ByteArrayOutputStream()
                    var lastFlushTime = System.currentTimeMillis()

                    for (chunk in channel) {
                        batchBuffer.write(chunk)

                        while (true) {
                            val nextChunk = channel.tryReceive().getOrNull() ?: break
                            batchBuffer.write(nextChunk)
                            if (batchBuffer.size() >= 65536) break
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastFlushTime >= 8 || batchBuffer.size() >= 65536 || channel.isEmpty) {
                            batchBuffer.writeTo(ipcOutputStream)
                            ipcOutputStream.flush()
                            batchBuffer.reset()
                            lastFlushTime = now
                        }
                    }

                    if (batchBuffer.size() > 0) {
                        batchBuffer.writeTo(ipcOutputStream)
                        ipcOutputStream.flush()
                        batchBuffer.reset()
                    }

                    onPumpComplete(ipcOutputStream)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { ipcOutputStream.close() }
            }
        }, "IpcStreamWriter")

        ipcWriterThread.start()

        runCatching { drainThread.join() }
        runCatching { ipcWriterThread.join() }
    }

    private fun sanitizeCommand(cmd: String): String {
        var processedCmd = cmd.trim()
        if (processedCmd == "dumpsys" || processedCmd.startsWith("dumpsys ")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (processedCmd == "dumpsys") return "dumpsys -t 3"
                if (!processedCmd.contains(" -t ") && !processedCmd.contains(" --timeout ")) {
                    processedCmd = processedCmd.replaceFirst("dumpsys", "dumpsys -t 3")
                }
            }
        }
        return processedCmd
    }

    private fun killProcessTree(proc: java.lang.Process?, taskKey: String? = null) {
        proc ?: return
        runCatching {
            if (proc.isAliveCompat()) {
                if (!taskKey.isNullOrEmpty()) {
                    runCatching {
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f '$taskKey'"))
                    }
                }

                val pid = getProcessPid(proc)
                if (pid > 1000) {
                    runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -P $pid")) }
                    runCatching { Os.kill(pid, OsConstants.SIGKILL) }
                } else {
                    proc.destroyForciblyCompat()
                }
            }
        }
    }

    private fun getProcessPid(proc: java.lang.Process): Int {
        return try {
            val field: Field = proc.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(proc)
        } catch (_: Exception) {
            val procStr = proc.toString()
            val pidMatch = Regex("pid=(\\d+)").find(procStr)
            pidMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
    }

    private fun java.lang.Process.isAliveCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.isAlive
        } else {
            try {
                this.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }
    }

    private fun java.lang.Process.destroyForciblyCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.destroyForcibly()
        } else {
            this.destroy()
        }
    }

    private fun writeDirectMessageToPipe(writeSide: ParcelFileDescriptor, message: String) {
        thread {
            runCatching {
                OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(writeSide), "UTF-8").use { writer ->
                    writer.write(message + "\n")
                    writer.flush()
                }
            }
        }
    }

    private fun handleCdCommand(cmd: String): String {
        val targetPath = if (cmd == "cd") {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            cmd.removePrefix("cd ").trim().removeSurrounding("\"", "\"")
        }

        val newDir = if (targetPath.startsWith("/")) {
            File(targetPath)
        } else {
            File(currentWorkingDirectory, targetPath)
        }

        val canonicalDir = runCatching { newDir.canonicalFile }.getOrElse { newDir }

        if (!canonicalDir.exists()) {
            return "sh: cd: $targetPath: No such file or directory"
        }
        if (!canonicalDir.isDirectory) {
            return "sh: cd: $targetPath: Not a directory"
        }

        currentWorkingDirectory = canonicalDir
        return "[INFO] Working directory switching: ${currentWorkingDirectory.absolutePath}"
    }

    private fun parseCommandLine(cmd: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        for (ch in cmd.toCharArray()) {
            if (ch == '"' || ch == '\'') {
                inQuotes = !inQuotes
            } else if (ch == ' ' && !inQuotes) {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.setLength(0)
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
        }
        return tokens
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        terminateCurrentCommand()
        closeUsbConnection()
        super.onDestroy()
    }
}

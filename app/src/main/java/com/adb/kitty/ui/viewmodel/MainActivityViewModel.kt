package com.adb.kitty.ui.viewmodel

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

import androidx.collection.*
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

import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.res.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*

import com.adb.kitty.ui.theme.*
import com.adb.kitty.data.*
import com.adb.kitty.data.fastboot.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.ui.it.*

@Keep
class MainActivityViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPrefs = application.getSharedPreferences(
        "user_color_settings", 
        Context.MODE_PRIVATE
    )
    private val KEY_USE_DYNAMIC_COLOR = "key_user_dynamic_color"
    private val _useDynamicColor = MutableStateFlow(
        sharedPrefs.getBoolean(KEY_USE_DYNAMIC_COLOR, false)
    )
    val useDynamicColor = _useDynamicColor.asStateFlow()

    fun setDynamicColorEnabled(enabled: Boolean) {
        _useDynamicColor.value = enabled
    
        sharedPrefs.edit {
            putBoolean(KEY_USE_DYNAMIC_COLOR, enabled)
        }
    }

    /**
     * 判断当前 Native 堆外日志缓冲区是否为空
     */
    val isLogEmpty: Boolean
        get() = NativeLibs.getWriteOffset() <= 0L

    private val logInputChannel = Channel<String>(Channel.UNLIMITED)

    private val _uiUpdateVersion = MutableStateFlow(0L)
    val uiUpdateVersion: StateFlow<Long> = _uiUpdateVersion.asStateFlow()

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
    private var lastSecondTimestamp: Long = 0L
    private var cachedTimeString: String = ""

    init {
        // 1. 自动监听 Application 广播的内存紧张信号
        val app = application as? BypassApi
        app?.let {
            @Suppress("DEPRECATION")
            viewModelScope.launch(Dispatchers.Default) {
                it.trimMemoryEvents.collect { level ->
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                        level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                    ) {
                        onTrimMemoryRequested()
                    }
                }
            }
        }

        // 2. 异步消费数据通道：格式化时间戳并推入 C++ 堆外内存
        viewModelScope.launch(Dispatchers.Default) {
            for (msg in logInputChannel) {
                processAndPushLog(msg)
                _uiUpdateVersion.value++
            }
        }
    }

    /**
     * 外部写入接口（非阻塞）
     */
    fun appendLog(msg: String) {
        logInputChannel.trySend(msg)
    }

    /**
     * 解析多行数据并追加时间戳，直接推入 Native C++ 堆外缓冲区
     */
    private fun processAndPushLog(msg: String) {
        val timeStr = getCachedTimeStamp()

        var start = 0
        val len = msg.length
        while (start < len) {
            var end = msg.indexOf('\n', start)
            if (end == -1) end = len

            if (end > start) {
                val line = msg.substring(start, end)
                if (line.isNotBlank()) {
                    // 直接写入 C++ 堆外内存，C++ append_fast 会自动在末尾补 '\n'
                    NativeLibs.appendNativeLog("$timeStr $line")
                }
            }
            start = end + 1
        }
    }

    /**
     * 内存紧张/主动清理：清空 Native 物理页 (madvise) 并通知 UI
     */
    fun onTrimMemoryRequested() {
        while (logInputChannel.tryReceive().isSuccess) { /* 清空 Channel 队列 */ }
        NativeLibs.clearNativeBuffer()
        _uiUpdateVersion.value++
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        while (logInputChannel.tryReceive().isSuccess) { /* 清空 Channel 队列 */ }
        NativeLibs.clearNativeBuffer()
        _uiUpdateVersion.value++
    }

    /**
     * 文件导出：利用 NIO FileChannel 将 Native 堆外内存一键落盘（物理层零拷贝）
     */
    suspend fun exportFullLogToFile(targetFile: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val buffer = NativeLibs.getDirectBuffer() ?: return@withContext false
            val writeOffset = NativeLibs.getWriteOffset().toInt()
            if (writeOffset <= 0) return@withContext false

            FileOutputStream(targetFile).channel.use { channel ->
                val readSlice = buffer.duplicate().apply {
                    position(0)
                    limit(writeOffset.coerceAtMost(capacity()))
                }
                channel.write(readSlice)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 高效秒级时间戳缓存（避免每条日志重复格式化字符串）
     */
    private fun getCachedTimeStamp(): String {
        val currentSecond = System.currentTimeMillis() / 1000
        if (currentSecond != lastSecondTimestamp) {
            lastSecondTimestamp = currentSecond
            cachedTimeString = LocalDateTime.now().format(timeFormatter)
        }
        return cachedTimeString
    }

    override fun onCleared() {
        
    }

    val items: List<CommandUiItem> by lazy { buildCombinedItems() }

    private fun buildCombinedItems(): List<CommandUiItem> = buildList(
        _adbCommands.size + _fbCommands.size + _appCommands.size
    ) {
        _adbCommands.forEach { 
            add(CommandUiItem(command = it.command, description = it.description, isAdb = true, isApp = false)) 
        }
        _fbCommands.forEach { 
            add(CommandUiItem(command = it.command, description = it.description, isAdb = false, isApp = false)) 
        }
        _appCommands.forEach { 
            add(CommandUiItem(command = it.command, description = it.description, isAdb = false, isApp = true)) 
        }
    }

    private val _appCommands = listOf(
        AppCommand("尝试设置selinux为宽容模式, 该扩展指令由app提供", "usb-selinux"),
        AppCommand("临时调用 Shell 的指令, 一般情况下用不上", "neko "),
        AppCommand("查询su域 id", "su"),
        AppCommand("以root身份调用本地 Shell", "su -c "),
        AppCommand("以system身份调用本地 Shell", "su system -c "),
        AppCommand("以adb身份调用本地 Shell", "su adb -c "),
        AppCommand("以shell身份调用本地 Shell", "su shell -c "),
        AppCommand("以root身份+全局挂载空间调用本地 Shell", "su -M -c "),
        AppCommand("以system身份+全局挂载空间调用本地 Shell", "su system -M -c "),
        AppCommand("以adb身份+全局挂载空间调用本地 Shell", "su adb -M -c "),
        AppCommand("以shell身份+全局挂载空间调用本地 Shell", "su shell -M -c "),
        AppCommand("以system身份+system域名调用本地 Shell", "su system -Z u:r:system_app:s0 -c "),
        AppCommand("以shell身份+shell域名调用本地 Shell", "su shell -Z u:r:shell:s0 -c "),
        AppCommand("以system身份+system域名+全局挂载空间调用本地 Shell", "su system -M -Z u:r:system_app:s0 -c "),
        AppCommand("以shell身份+shell域名+全局挂载空间调用本地 Shell", "su shell -M -Z u:r:shell:s0 -c "), 
        AppCommand("查看su版本信息", "su -v"),
        AppCommand("查看su帮助", "su -h"),
        AppCommand("扫描/识别 USB 设备, 该扩展指令由app提供", "usb-host"),
        AppCommand("测试 ipv4 和 ipv6 地址, 该扩展指令由app提供", "ip-test"),
        AppCommand("打印控制台已输出的日志到本地, 该扩展指令由app提供", "userkitty-log-export"),
        AppCommand("查询高级保护模式状态, 该扩展指令由app提供", "query-apm"),
        AppCommand("文本内容生成二维码, 该扩展指令由app提供", "qr-gen "),
        AppCommand("二维码解码, 该扩展指令由app提供", "qr-decode "),
        AppCommand("调用系统图片选择器来进行二维码解码, 该扩展指令由app提供", "qr-decode --system"),
        AppCommand("文件加密, 该扩展指令由app提供", "encrypt "),
        AppCommand("文件解密, 该扩展指令由app提供", "decrypt "),
        AppCommand("尝试从互联网下载文件, 该扩展指令由app提供", "download "),
        AppCommand("从视频中提取音频, 该指令由app提供", "neko-audio "),
        AppCommand("intent 跳转, 该指令由app提供", "neko-intent "),
        AppCommand("查看应用签名信息, 该指令由app提供", "neko-sig"),
        AppCommand("查看应用签名信息, 该指令由app提供", "apk-sig"),
        AppCommand("用于设置消息通知标题, 该指令由app提供", "msg-name ")
    )

    private val _adbCommands = listOf(
        AdbCommand("无线调试配对", "adb pair "),
        AdbCommand("连接无线调试", "adb connect "),
        AdbCommand("断开无线调试", "adb disconnect"),
        AdbCommand("adbd Shell", "adb shell "),
        AdbCommand("查询设备支持特征", "adb features"),
        AdbCommand("查询adb连接状态", "adb status"),
        AdbCommand("检索远程路径元数据", "adb stat "),
        AdbCommand("推送文件", "adb push "),
        AdbCommand("拉取文件", "adb pull "),
        AdbCommand("安装软件", "adb install "),
        AdbCommand("卸载软件", "adb uninstall "),
        AdbCommand("登录超级用户", "adb root"),
        AdbCommand("退出超级用户", "adb unroot"),
        AdbCommand("设备重启", "adb reboot "),
        AdbCommand("使用无线配对码一键配对", "adb mdns --pair "),
        AdbCommand("一键连接无线调试", "adb mdns --connect"),
        AdbCommand("列出附近可连接的无线设备", "adb mdns --list"),
        AdbCommand("查看应用自身域", "id"),
        AdbCommand("查看SeLinux状态", "getenforce"),
        AdbCommand("系统重启，相当于长按电源键", "svc power reboot"),
        AdbCommand("系统关机，相当于长按电源键", "svc power shutdown"),
        AdbCommand("开启 SysRq 机制", "echo 1 > /proc/sys/kernel/sysrq"),
        AdbCommand("SysRq 强制将所有缓存的数据写入闪存", "echo s > /proc/sysrq-trigger"),
        AdbCommand("SysRq 强制将所有挂载的分区重新挂载为只读", "echo u > /proc/sysrq-trigger"),
        AdbCommand("SysRq 强制重启", "echo b > /proc/sysrq-trigger"),
        AdbCommand("重启", "reboot"),
        AdbCommand("重启进入红屏死机模式，部分高通设备支持", "reboot rsod"),
        AdbCommand("重启进入 Recovery 的 ADB Sideload 模式", "reboot sideload"),
        AdbCommand("触发冷启动，部分设备支持", "reboot cold"),
        AdbCommand("触发热启动，部分设备支持", "reboot warm"),
        AdbCommand("强制重启", "reboot -f"),
        AdbCommand("强制关机", "reboot -p"),
        AdbCommand("重启时不进行磁盘同步", "reboot -n"),
        AdbCommand("强制重启，且绝对不同步缓存", "reboot -fn"),
        AdbCommand("重启到系统", "reboot system"),
        AdbCommand("重启到恢复模式", "reboot recovery"),
        AdbCommand("重启到引导模式", "reboot bootloader"),
        AdbCommand("重启到闪刷模式", "reboot fastboot"),
        AdbCommand("重启到紧急下载模式", "reboot edl"),
        AdbCommand("利用Android属性关机", "setprop sys.powerctl shutdown"),
        AdbCommand("利用Android属性重启", "setprop sys.powerctl reboot"),
        AdbCommand("利用Android属性软重启", "setprop sys.powerctl reboot,userspace"),
        AdbCommand("利用Android属性重启到恢复模式", "setprop sys.powerctl reboot,recovery"),
        AdbCommand("利用Android属性重启到引导模式", "setprop sys.powerctl reboot,bootloader"),
        AdbCommand("利用Android属性重启到闪刷模式", "setprop sys.powerctl reboot,fastboot"),
        AdbCommand("利用Android属性重启到紧急下载模式", "setprop sys.powerctl reboot,edl"),
        AdbCommand("logcat 选项 过滤器规范", "logcat "),
        AdbCommand("抓取崩溃缓冲区的日志", "logcat -b crash -d"),
        AdbCommand("清空历史输出的日志", "logcat -c"),
        AdbCommand("快照式转储日志", "logcat -d"),
        AdbCommand("settings --user 用户id  get|put|delete|list  global|secure|system  参数  参数", "settings "),
        AdbCommand("settings get global 参数", "settings get global "),
        AdbCommand("settings get secure 参数", "settings get secure "),
        AdbCommand("settings get system 参数", "settings get system "),
        AdbCommand("settings put global 参数 参数", "settings put global "),
        AdbCommand("settings put secure 参数 参数", "settings put secure "),
        AdbCommand("settings put system 参数 参数", "settings put system "),
        AdbCommand("settings delete global 参数", "settings delete global "),
        AdbCommand("settings delete secure 参数", "settings delete secure "),
        AdbCommand("settings delete system 参数", "settings delete system "),
        AdbCommand("settings reset global 参数", "settings reset global "),
        AdbCommand("settings reset secure 参数", "settings reset secure "),
        AdbCommand("settings reset system 参数", "settings reset system "),
        AdbCommand("列出当前全局命名空间下所有参数", "settings list global"),
        AdbCommand("列出当前安全命名空间下所有参数", "settings list secure"),
        AdbCommand("列出当前系统命名空间下所有参数", "settings list system"),
        AdbCommand("将当前全局命名空间下所有修改重置为官方默认值", "settings reset global untrusted_defaults"),
        AdbCommand("将当前安全命名空间下所有修改重置为官方默认值", "settings reset secure untrusted_defaults"),
        AdbCommand("将当前系统命名空间下所有修改重置为官方默认值", "settings reset system untrusted_defaults"),
        AdbCommand("将当前全局命名空间下所有修改彻底抹去", "settings reset global untrusted_clear"),
        AdbCommand("将当前安全命名空间下所有修改彻底抹去", "settings reset secure untrusted_clear"),
        AdbCommand("将当前系统命名空间下所有修改彻底抹去", "settings reset system untrusted_clear"),
        AdbCommand("chmod 选项 权限模式 文件或目录路径", "chmod "),
        AdbCommand("ls 选项 路径", "ls "),
        AdbCommand("mkdir 选项 目录名/路径", "mkdir "),
        AdbCommand("cp 选项 源路径 目标路径", "cp "),
        AdbCommand("mv 源路径 目标路径", "mv "),
        AdbCommand("rm 选项 文件或目录路径", "rm "),
        AdbCommand("查看手机各分区的磁盘空间剩余", "df -h"),
        AdbCommand("查看电池电量、温度、健康度", "dumpsys battery"),
        AdbCommand("查看当前实时温度与温控断频状态", "dumpsys thermal"),
        AdbCommand("查看闪存(ROM) 总大小，剩余空间", "dumpsys diskstats"),
        AdbCommand("查看当前多用户/分身状态", "dumpsys user"),
        AdbCommand("查看当前状态栏、通知栏、锁屏的状态", "dumpsys statusbar"),
        AdbCommand("查看CPU/GPU电池的硬件温度限制", "dumpsys hardware_properties"),
        AdbCommand("清除 SurfaceFlinger 历史缓存", "dumpsys SurfaceFlinger --latency-clear"),
        AdbCommand("查看 WakeLock 泄露情况", "dumpsys power | grep -i NekoStudio:RecordingWakeLock"),
        AdbCommand("查看前台服务类型注册情况", "dumpsys activity services com.adb.kitty"),
        AdbCommand("查看当前屏幕分辨率", "wm size"),
        AdbCommand("查看当前屏幕DPI", "wm density"),
        AdbCommand("杀死所有后台进程", "am kill-all"),
        AdbCommand("am kill 包名", "am kill "),
        AdbCommand("am force-stop 包名", "am force-stop "),
        AdbCommand("am 子命令 参数 意图", "am "),
        AdbCommand("打开正在运行的服务", "su -c am start -n com.android.settings/.SubSettings --es :settings:show_fragment com.android.settings.applications.RunningServices"),
        AdbCommand("打开极暗", "su -c am start -n com.android.settings/.Settings\\\$ReduceBrightColorsSettingsActivity"),
        AdbCommand("打开原生通知", "su -c am start -n com.android.settings/.Settings\\\$ConfigureNotificationSettingsActivity"),
        AdbCommand("打开内存分析界面", "su -c am start -n com.android.settings/.Settings\\\$MemorySettingsActivity"),
        AdbCommand("打开放大镜功能", "su -c am start -n com.android.settings/.Settings\\\$MagnificationActivity"),
        AdbCommand("打开无线调试", "su -c am start -n com.android.settings/.SubSettings --es :settings:show_fragment com.android.settings.development.WirelessDebuggingFragment"),
        AdbCommand("打开开发者选项", "su -c am start -n com.android.settings/.Settings\\\$DevelopmentSettingsActivity"),
        AdbCommand("启动放大功能", "su -c settings put secure accessibility_display_magnification_enabled 1"),
        AdbCommand("禁用放大功能", "su -c settings put secure accessibility_display_magnification_enabled 0"),
        AdbCommand("启用极暗模式", "su -c settings put secure reduce_bright_colors_activated 1"),
        AdbCommand("禁用极暗模式", "su -c settings put secure reduce_bright_colors_activated 0"),
        AdbCommand("pm path 包名", "pm path "),
        AdbCommand("pm clear 包名", "pm clear "),
        AdbCommand("pm enable 包名或应用组件", "pm enable "),
        AdbCommand("pm disable 包名或应用组件", "pm disable "),
        AdbCommand("列出所有功能特性标签", "pm list features"),
        AdbCommand("列出所有应用包名", "pm list packages"),
        AdbCommand("列出所有应用包名的数量", "pm list packages | wc -l"),
        AdbCommand("pm list packages 选项 过滤字符", "pm list packages "),
        AdbCommand("仅列出第三方应用包名", "pm list packages -3"),
        AdbCommand("仅列出系统预装应用包名", "pm list packages -s"),
        AdbCommand("仅列出被禁用的应用包名", "pm list packages -d"),
        AdbCommand("仅列出处于启动状态的应用包名", "pm list packages -e"),
        AdbCommand("仅列出第三方应用包名的数量", "pm list packages -3 | wc -l"),
        AdbCommand("仅列出系统预装应用包名的数量", "pm list packages -s | wc -l"),
        AdbCommand("仅列出被禁用的应用包名的数量", "pm list packages -d | wc -l"),
        AdbCommand("仅列出处于启动状态的应用包名的数量", "pm list packages -e | wc -l"),
        AdbCommand("仅列出第三方应用包名(包含已卸载残留)", "pm list packages -3 -u"),
        AdbCommand("pm list packages -i 字符串", "pm list packages -i"),
        AdbCommand("仅列出过滤字符的应用安装来源", "pm list packages -i"),
        AdbCommand("pm list packages -f 字符串", "pm list packages -f"),
        AdbCommand("仅列出过滤字符的APK绝对路径", "pm list packages -f"),
        AdbCommand("纯净系统无任何用户激活 Dhizuku (需要安装Dhizuku)", "adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver"),
        AdbCommand("激活 Sence (需要安装Sence)", "adb shell sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh"),
        AdbCommand("激活 AppManager (需要安装AppManager)", "adb shell sh /storage/emulated/0/Android/data/io.github.muntashirakon.AppManager/cache/run_server.sh 60001 wasp-lurk-ripen"),
        AdbCommand("查询系统信息", "uname -a"),
        AdbCommand("查看内核版本", "cat /proc/version"),
        AdbCommand("读取进程启动信息", "cat /proc/self/status"),
        AdbCommand("getprop 参数", "getprop "),
        AdbCommand("查看基带版本", "getprop gsm.version.baseband"),
        AdbCommand("查看安卓系统版本", "getprop ro.build.version.release"),
        AdbCommand("查看安卓系统最低支持的TargetSdk", "getprop ro.build.version.min_supported_target_sdk"),
        AdbCommand("查看设备编译版本", "getprop ro.build.display.id"),
        AdbCommand("查看安全补丁级别", "getprop ro.build.version.security_patch"),
        AdbCommand("查看主板型号", "getprop ro.product.board"),
        AdbCommand("查看屏幕分辨率 (随设置而变化)", "getprop persist.sys.miui_resolution"),
        AdbCommand("卸载系统更新", "pm uninstall --user 0 com.android.updater"),
        AdbCommand("恢复系统更新", "pm install-existing --user 0 com.android.updater"),
        AdbCommand("强行启用小米堆叠桌面 (已支持勿用)", "settings put global task_stack_view_layout_style 2 "),
        AdbCommand("强行停止小米系统桌面", "am force-stop com.miui.home"),
        AdbCommand("列出当前系统所有运行中进程的SeLinux域", "su -c ps -AZ"),
        AdbCommand("列出当前运行中进程shell的SeLinux域", "su -c ps -AZ | grep shell"),
        AdbCommand("列出当前运行中进程system的SeLinux域", "su -c ps -AZ | grep system")
    )

    private val _fbCommands = listOf(
        FbCommand("fastboot set_active a或b", "set_active "),
        FbCommand("fastboot format 分区", "format "),
        FbCommand("fastboot erase 分区", "erase "),
        FbCommand("fastboot getvar 参数", "getvar "),
        FbCommand("fastboot oem 参数", "oem "),
        FbCommand("fastboot reboot 可选参数", "reboot "),
        FbCommand("fastboot boot 文件名", "boot "),
        FbCommand("fastboot flash 分区 路径", "flash "),
        FbCommand("查看当前安全补丁级别", "getvar security-patch-level"),
        FbCommand("查看当前最大下载限制", "getvar max-download-size"),
        FbCommand("查看当前活跃的分区槽位（a 或 b)", "getvar current-slot"),
        FbCommand("查看 Bootloader 解锁状态", "getvar unlocked"),
        FbCommand("oem info", "oem device-info"),
        FbCommand("获取设备的所有系统变量（如版本号、电池电压、Bootloader 锁状态等)", "getvar all"),
        FbCommand("退出 Fastboot 模式并正常重启手机", "reboot"),
        FbCommand("从 Fastboot 模式再次重启回 Fastboot 模式（用于重置连接状态)", "reboot bootloader"),
        FbCommand("重启到 FastbootD 模式", "reboot fastboot"),
        FbCommand("尝试重启到 EDL 模式(紧急下载/9008)", "reboot edl"),
        FbCommand("尝试进入 EDL 模式(紧急下载/9008)", "oem edl"),
        FbCommand("擦除缓存分区", "erase cache"),
        FbCommand("擦除用户数据分区（相当于恢复出厂设置)", "erase userdata"),
        FbCommand("擦除出厂重置保护(谷歌锁)", "erase frp"),
        FbCommand("尝试解锁 Bootloader", "oem unlock"),
        FbCommand("尝试解锁 Bootloader", "flashing unlock")
    )
}

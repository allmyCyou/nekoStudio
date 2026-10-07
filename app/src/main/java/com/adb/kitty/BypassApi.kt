package com.adb.kitty

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.data.*
import com.adb.kitty.R

import android.app.Application
import android.content.Context
import android.content.ComponentCallbacks2
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.Keep
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Keep
class BypassApi : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }
    }

    override fun onCreate() {
        super.onCreate()
        NativeLibs.initNativeEngine(16 * 1024 * 1024)
    }
    
    private val _trimMemoryEvents = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val trimMemoryEvents: Flow<Int> = _trimMemoryEvents.asSharedFlow()
    
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        _trimMemoryEvents.tryEmit(level)

        // 当系统内存紧张时，直接触发 madvise(MADV_DONTNEED) 归还堆外物理内存页
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            NativeLibs.clearNativeBuffer()
        }
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        _trimMemoryEvents.tryEmit(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        // 极低内存时清空 Native 缓冲区
        NativeLibs.clearNativeBuffer()
    }

    override fun onTerminate() {
        super.onTerminate()
        // 销毁 Native 引擎并释放堆外内存
        destroyLogEngine()
    }

    fun destroyLogEngine() {
        NativeLibs.releaseNativeEngine()
    }
}

package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnNextLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.milliseconds

/**
 * 继承原生 android.widget.TextView 并拦截无障碍事件
 * 阻止高频 setText 时向系统发送大容量 Binder 消息
 */
class LogTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    init {
        // 关闭不必要的绘制计算
        setIncludeFontPadding(false)
    }

    override fun sendAccessibilityEventUnchecked(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        super.sendAccessibilityEventUnchecked(event)
    }

    override fun sendAccessibilityEvent(eventType: Int) {
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        super.sendAccessibilityEvent(eventType)
    }
}

// 改为安全读取 UTF-8 字节并转为 String
fun getNativeLogSnapshot(): String {
    val buffer = NativeLibs.getDirectBuffer() ?: return ""
    val writeOffset = NativeLibs.getWriteOffset().toInt().coerceAtMost(buffer.capacity())
    if (writeOffset <= 0) return ""

    val bytes = ByteArray(writeOffset)
    val duplicate = buffer.duplicate()
    duplicate.position(0)
    duplicate.get(bytes, 0, writeOffset)
    
    // 一次性转为标准 UTF-8 字符串，确保 UTF-16 字符排版引擎高效工作
    return String(bytes, Charsets.UTF_8)
}

@Keep
private class LogContainerView(context: Context) : NestedScrollView(context) {
    val horizontalScrollView = HorizontalScrollView(context)
    val textView = LogTextView(context)

    var isAutoScrollEnabled = true
    private var isUserTouching = false
    private var isUpdatingText = false
    private var lastLoadedLength: Int = -1

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFillViewport = true

        horizontalScrollView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        horizontalScrollView.isFillViewport = true

        textView.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(false)
            setHorizontallyScrolling(true)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

            setOnLongClickListener { v ->
                val tv = v as TextView

                // 开启选中支持并请求焦点
                tv.setTextIsSelectable(true)
                tv.requestFocus()

                // 监听系统复制/全选菜单销毁事件，离开选中状态时还原为不可选中
                tv.customSelectionActionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = true
                    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false

                    override fun onDestroyActionMode(mode: ActionMode?) {
                        // 结束复制操作或取消选择后重置
                        tv.setTextIsSelectable(false)
                    }
                }

                // 返回 false 允许 TextView 内部继续响应 performLongClick 弹出选择游标
                false
            }
        }

        horizontalScrollView.addView(textView)
        addView(horizontalScrollView)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isUserTouching = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isUserTouching = false
                if (isAtBottom()) {
                    isAutoScrollEnabled = true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)

        if (isUpdatingText) return

        val dy = t - oldt

        if (dy < 0) {
            isAutoScrollEnabled = false
        } else if (isAtBottom()) {
            isAutoScrollEnabled = true
        }
    }

    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean
    ): Boolean {
        return false
    }

    private fun isAtBottom(): Boolean {
        val child = getChildAt(0) ?: return true
        val maxScrollY = (child.height - height).coerceAtLeast(0)
        if (maxScrollY <= 0) return true
        return (maxScrollY - scrollY) <= 150
    }

    fun updateLogs(logCharSequence: CharSequence, textColor: Int) {
        if (textView.currentTextColor != textColor) {
            textView.setTextColor(textColor)
        }

        val currentLength = logCharSequence.length
        if (lastLoadedLength != currentLength) {
            lastLoadedLength = currentLength
            isUpdatingText = true

            horizontalScrollView.doOnNextLayout {
                if (isAutoScrollEnabled && !isUserTouching) {
                    fullScroll(View.FOCUS_DOWN)
                }
                post {
                    isUpdatingText = false
                }
            }

            textView.setText(logCharSequence, TextView.BufferType.NORMAL)
        }
    }

    fun setLogTextColor(textColor: Int) {
        if (textView.currentTextColor != textColor) {
            textView.setTextColor(textColor)
        }
    }

    fun releaseMemory() {
        lastLoadedLength = -1
        textView.text = ""
        isAutoScrollEnabled = true
        isUserTouching = false
        isUpdatingText = false
    }
}

@OptIn(FlowPreview::class)
@Keep
@Composable
fun LogSection(
    uiUpdateVersionFlow: StateFlow<Long>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var containerView by remember { mutableStateOf<LogContainerView?>(null) }

    val isDark = isSystemInDarkTheme()
    val logTextColor = remember(isDark) {
        if (isDark) 0xFFEEEEEE.toInt() else 0xFF111111.toInt()
    }

    LaunchedEffect(context) {
        val app = context.applicationContext as? BypassApi ?: return@LaunchedEffect

        @Suppress("DEPRECATION")
        app.trimMemoryEvents.collect { level ->
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            ) {
                containerView?.releaseMemory()
            }
        }
    }

    LaunchedEffect(uiUpdateVersionFlow, containerView, lifecycleOwner) {
        val view = containerView ?: return@LaunchedEffect

        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            uiUpdateVersionFlow
                .sample(100.milliseconds)
                .collect {
                    // 在后台线程安全提取快照并解码 UTF-8
                    val logText = withContext(Dispatchers.Default) {
                        getNativeLogSnapshot()
                    }

                    // 主线程仅负责设置合法文本
                    withContext(Dispatchers.Main) {
                        view.updateLogs(logText, logTextColor)
                    }
                }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            containerView?.releaseMemory()
            containerView = null
        }
    }

    AndroidView(
        factory = { ctx ->
            LogContainerView(ctx).also {
                containerView = it
            }
        },
        update = { view ->
            view.setLogTextColor(logTextColor)
        },
        modifier = modifier.clipToBounds()
    )
}

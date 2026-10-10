package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

import android.os.Bundle
import android.os.Build
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.text.Spannable
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
import java.nio.ByteOrder
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
        // 避免获取焦点时强制拉起软键盘
        setShowSoftInputOnFocus(false)

        // 禁用状态自动保存与恢复，避免长文本序列化开销与内存抖动
        isSaveEnabled = false
    }

    // 彻底阻断输入法连接，防止软键盘线程与主线程争夺资源导致卡死
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        return null
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

/**
 * 安全获取 Native 日志快照（所有二进制解析、滑动窗口、文本拼接全部在 C++ 堆外完成）
 */
fun getNativeLogSnapshot(): String {
    // 限制最大返回 880 KB 的最新文本，保护 UI 渲染性能
    return NativeLibs.getLogSnapshot(880 * 1024)
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

            bindLongClickListener(this)
        }

        horizontalScrollView.addView(textView)
        addView(horizontalScrollView)
    }

    // 将绑定长按监听抽离为独立函数
    private fun bindLongClickListener(tv: TextView) {
        tv.setOnLongClickListener { v ->
            val textView = v as TextView

            // 1. 防御性检查：确保 Buffer 类型为 Spannable
            if (textView.text !is Spannable) {
                textView.setText(textView.text, TextView.BufferType.SPANNABLE)
            }

            // 2. 开启选择状态（这会覆盖当前的 OnLongClickListener，但后续会恢复）
            textView.setTextIsSelectable(true)
            textView.requestFocus()

            // 3. 设置 ActionMode 回调
            textView.customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = true
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false

                override fun onDestroyActionMode(mode: ActionMode?) {
                    // 4. 延迟到下一帧，避开 Editor 销毁时的 removeSelection 强转崩溃
                    textView.post {
                        // 关闭选择状态（这会导致系统把 OnLongClickListener 置为 null）
                        textView.setTextIsSelectable(false)

                        // 重新把长按监听器装回去！
                        bindLongClickListener(textView)
                    }
                }
            }
    
            false
        }
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

            // 使用 BufferType.SPANNABLE，确保 Editor.onDestroyActionMode 内部可以正确转换
            textView.setText(logCharSequence, TextView.BufferType.SPANNABLE)
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
                .sample(170.milliseconds)
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

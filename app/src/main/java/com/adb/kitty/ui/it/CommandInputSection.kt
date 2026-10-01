package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

import android.annotation.SuppressLint
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.widget.EditText
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> CommandInputSection(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    filteredItems: List<T>,
    getItemCommand: (T) -> String,
    getItemDescription: (T) -> String,
    isAppItem: (T) -> Boolean,
    isAdbItem: (T) -> Boolean,
    modifier: Modifier = Modifier
) {
    val currentOnQueryChange by rememberUpdatedState(onQueryChange)

    class EditStateHolder {
        var isUpdatingProgrammatically = false
    }
    val stateHolder = remember { EditStateHolder() }

    val interactionSource = remember { MutableInteractionSource() }
    val coroutineScope = rememberCoroutineScope()
    var focusInteraction by remember { mutableStateOf<FocusInteraction.Focus?>(null) }

    // 当输入框内容发生变化时，根据内容是否为空自动同步展开状态，避免 Channel 防抖引发的延迟竞态
    LaunchedEffect(query.text) {
        if (query.text.isNotEmpty() && !expanded) {
            onExpandedChange(true)
        } else if (query.text.isEmpty() && expanded) {
            onExpandedChange(false)
        }
    }

    val displayItems = remember(filteredItems) { filteredItems.take(20) }

    // 记录输入框在窗口中的坐标与尺寸
    var textFieldSize by remember { mutableStateOf(IntSize.Zero) }
    var anchorBoundsInWindow by remember { mutableStateOf(IntRect.Zero) }

    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current

    val maxMenuHeightDp = remember(anchorBoundsInWindow, windowInfo.containerSize, density) {
        if (anchorBoundsInWindow == IntRect.Zero) 200.dp
        else {
            val containerHeightPx = windowInfo.containerSize.height
            val availablePx = (containerHeightPx - anchorBoundsInWindow.bottom).coerceAtLeast(0)
            with(density) {
                (availablePx.toDp() - 16.dp).coerceAtLeast(80.dp)
            }
        }
    }

    // 无副作用的位置定位器
    val customPositionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                return IntOffset(
                    x = anchorBounds.left,
                    y = anchorBounds.bottom
                )
            }
        }
    }

    Box(
        modifier = modifier
            .wrapContentHeight()
            .onGloballyPositioned { coordinates ->
                textFieldSize = coordinates.size
                anchorBoundsInWindow = coordinates.boundsInWindow().roundToIntRect()
            }
    ) {
        OutlinedTextFieldDefaults.DecorationBox(
            value = query.text,
            innerTextField = {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        EditText(context).apply {
                            background = null
                            setPadding(0, 0, 0, 0)

                            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                            maxLines = 3
                            isSingleLine = false
                            textSize = 16f

                            overScrollMode = android.view.View.OVER_SCROLL_NEVER
                            filters = arrayOf(InputFilter.LengthFilter(16384))

                            post {
                                if (lineHeight > 0) {
                                    maxHeight = lineHeight * 3 + compoundPaddingTop + compoundPaddingBottom
                                }
                            }

                            isVerticalScrollBarEnabled = false
                            setHorizontallyScrolling(false)
                            isLongClickable = true

                            setOnTouchListener { view, event ->
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        view.parent?.requestDisallowInterceptTouchEvent(true)
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        view.parent?.requestDisallowInterceptTouchEvent(false)
                                    }
                                }
                                false
                            }

                            setOnFocusChangeListener { _, hasFocus ->
                                coroutineScope.launch {
                                    if (hasFocus) {
                                        if (focusInteraction == null) {
                                            val focus = FocusInteraction.Focus()
                                            focusInteraction = focus
                                            interactionSource.emit(focus)
                                        }
                                    } else {
                                        focusInteraction?.let { focus ->
                                            interactionSource.emit(FocusInteraction.Unfocus(focus))
                                            focusInteraction = null
                                        }
                                    }
                                }
                            }

                            var lastLineCount = -1

                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    if (stateHolder.isUpdatingProgrammatically) return

                                    val newText = s?.toString() ?: ""
                                    val safeStart = selectionStart.coerceIn(0, newText.length)
                                    val safeEnd = selectionEnd.coerceIn(0, newText.length)

                                    currentOnQueryChange(
                                        TextFieldValue(
                                            text = newText,
                                            selection = TextRange(safeStart, safeEnd)
                                        )
                                    )
                                }

                                override fun afterTextChanged(s: Editable?) {
                                    val currentLineCount = lineCount
                                    if (currentLineCount != lastLineCount) {
                                        lastLineCount = currentLineCount
                                        layout?.let { l ->
                                            val sel = selectionStart
                                            if (sel >= 0) {
                                                val line = l.getLineForOffset(sel)
                                                val lineBottom = l.getLineBottom(line)
                                                val visibleBottom = scrollY + (height - paddingTop - paddingBottom)

                                                if (lineBottom > visibleBottom) {
                                                    scrollTo(0, lineBottom - (height - paddingTop - paddingBottom))
                                                }
                                            }
                                        }
                                    }
                                }
                            })
                        }
                    },
                    update = { editText ->
                        if (editText.text.toString() != query.text) {
                            stateHolder.isUpdatingProgrammatically = true
                            editText.setText(query.text)
                            val safeSelection = query.selection.end.coerceIn(0, query.text.length)
                            editText.setSelection(safeSelection)
                            stateHolder.isUpdatingProgrammatically = false
                        }
                    }
                )
            },
            enabled = true,
            singleLine = false,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactionSource,
            isError = false,
            label = {
                Text(stringResource(R.string.action_menu_sospl))
            },
            colors = OutlinedTextFieldDefaults.colors(),
            contentPadding = OutlinedTextFieldDefaults.contentPaddingWithLabel()
        )

        // 下拉菜单：锁定在输入框底部
        if (expanded && query.text.isNotEmpty() && displayItems.isNotEmpty()) {
            Popup(
                popupPositionProvider = customPositionProvider,
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnClickOutside = false // 禁用外部点击自动关闭，避免打字或点击 EditText 时菜单闪烁关闭
                )
            ) {
                Surface(
                    modifier = Modifier
                        .width(with(density) { textFieldSize.width.toDp() })
                        .heightIn(max = maxMenuHeightDp),
                    shape = MenuDefaults.shape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(
                            items = displayItems,
                            key = { item -> getItemCommand(item) } // 绑定 key 优化节点复用，避免每次重新构建
                        ) { item ->
                            val command = getItemCommand(item)
                            val description = getItemDescription(item)
                            val isApp = isAppItem(item)
                            val isAdb = isAdbItem(item)

                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(vertical = 4.dp)
                                                .padding(end = 8.dp)
                                        ) {
                                            Text(
                                                text = command,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )

                                            Spacer(modifier = Modifier.height(2.dp))

                                            Text(
                                                text = description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = when {
                                                isApp -> "[APP]"
                                                isAdb -> "[ADB]"
                                                else -> "[Fastboot]"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when {
                                                isApp -> MaterialTheme.colorScheme.secondary
                                                isAdb -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.error
                                            },
                                            modifier = Modifier.wrapContentWidth()
                                        )
                                    }
                                },
                                onClick = {
                                    onQueryChange(
                                        TextFieldValue(
                                            text = command,
                                            selection = TextRange(command.length)
                                        )
                                    )
                                    onExpandedChange(false)
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }
        }
    }
}

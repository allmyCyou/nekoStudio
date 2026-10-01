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
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
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

    val searchChannel = remember { Channel<String>(capacity = Channel.CONFLATED) }

    LaunchedEffect(searchChannel) {
        searchChannel.receiveAsFlow()
            .debounce(150)
            .collect { latestText ->
                onExpandedChange(latestText.isNotEmpty())
            }
    }

    val displayItems = remember(filteredItems) { filteredItems.take(20) }

    // 记录输入框外层容器的宽度，以便 Dropdown 宽度精确对齐
    var textFieldSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .wrapContentHeight()
            .onSizeChanged { textFieldSize = it }
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
                                    searchChannel.trySend(newText)
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

        // 自定义下拉菜单：严格锁定在输入框底部，绝不上移/覆盖
        if (expanded && query.text.isNotEmpty() && displayItems.isNotEmpty()) {
            var maxMenuHeightDp by remember { mutableStateOf(200.dp) }

            val customPositionProvider = remember(density) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        // 算出输入框底部到小窗底部剩余的像素高度
                        val availableHeightPx = (windowSize.height - anchorBounds.bottom - 16).coerceAtLeast(80)
                        val calculatedDp = with(density) { availableHeightPx.toDp() }
                        if (maxMenuHeightDp != calculatedDp) {
                            maxMenuHeightDp = calculatedDp
                        }

                        // 强行指定 Top 坐标等于 anchorBounds.bottom（绝对不向上覆盖）
                        return IntOffset(
                            x = anchorBounds.left,
                            y = anchorBounds.bottom
                        )
                    }
                }
            }

            Popup(
                popupPositionProvider = customPositionProvider,
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = false) // focusable = false 避免焦点抢占导致的软键盘关闭/打字卡顿
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
                        items(displayItems) { item ->
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

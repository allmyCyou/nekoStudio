package com.adb.kitty.ui.it

import android.*
import android.util.*
import android.content.pm.*
import android.animation.*
import android.provider.*
import android.app.PendingIntent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.speech.tts.TextToSpeech

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
import androidx.core.graphics.createBitmap
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
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

import android.os.*
import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.contract.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CenterAlignedTopAppBarExample(
    viewModel: MainActivityViewModel,
    activity: MainActivity,
    onExecuteCommand: (String) -> Unit,
    onIntentBottomSheet: () -> Unit,
    onHelpBottomSheet: () -> Unit,
    onCpuBottomSheet: () -> Unit,
    onCpuHistoryBottomSheet: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var showMenu by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                viewModel.appendLog("[提示] 正在处理大图，请稍候...")
                val success = processAndSaveAvatar(context, uri)
                if (success) {
                    activity.reloadServiceAvatar()
                    viewModel.appendLog("[成功] 服务头像已更新，取图片正中央区域！")
                } else {
                    viewModel.appendLog("[错误] 头像裁剪失败，请检查图片是否损坏")
                }
            }
        }
    }
    
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { 
        mutableStateOf(TextFieldValue("")) 
    }
    val filteredItems = remember(query.text) {
        if (query.text.isEmpty()) {
            emptyList()
        } else {
            viewModel.items.filter { 
                it.command.contains(query.text, ignoreCase = true) || 
                it.description.contains(query.text, ignoreCase = true)
            }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    
    val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
    
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (query.text.isNotBlank()) {
                        onExecuteCommand(query.text)
                        query = TextFieldValue("")
                        expanded = false
                    }
                },
                icon = { 
                    Icon(
                        imageVector = wand_stars,
                        contentDescription = "Wand Stars"
                    )
                },
                text = { 
                    Text(
                        text = stringResource(R.string.push_cmd_name)
                    ) 
                },
            )
        },
        floatingActionButtonPosition = FabPosition.End,
        
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(
                        text = stringResource(R.string.app_bar_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                         /* do something */ 
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Localized description"
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_notification_img)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.AccountCircle, null) },
                                onClick = {
                                    showMenu = false
                                    pickMediaLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_color)
                                    )
                                },
                                leadingIcon = { 
                                    Icon(Icons.Outlined.Palette, null) 
                                },
                                onClick = {
                                    viewModel.setDynamicColorEnabled(!useDynamicColor)
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_dellog)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { 
                                    viewModel.clearLogs()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_help)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = {
                                    showMenu = false
                                    onHelpBottomSheet()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_log_export)
                                    )
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Send, null) },
                                onClick = {
                                    onExecuteCommand("userkitty-log-export")
                                    showMenu = false 
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_usb)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = {
                                    activity.findHostDevice()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_storage)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = {
                                    showMenu = false
                                    activity.triggerStoragePermissionCheck()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_iptest)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Language, null) },
                                onClick = {
                                    activity.startIpNetworkTest()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_share)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                onClick = { 
                                    showMenu = false
                                    onIntentBottomSheet()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_selinux)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.LockOpen, null) },
                                onClick = {
                                    showMenu = false
                                    activity.FbSeLinuxCmd()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_cpu_view)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                                onClick = {
                                    showMenu = false
                                    onCpuBottomSheet()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_cpu_view_cat)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                                onClick = {
                                    showMenu = false
                                    onCpuHistoryBottomSheet()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_neko_shell_stop)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Cancel, null) },
                                onClick = {
                                    showMenu = false
                                    activity.onUserClickStopCommand()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_wakelock)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Speed, null) },
                                onClick = {
                                    showMenu = false
                                    activity.startWakeLock()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_wakelock_stop)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.SettingsBackupRestore, null) },
                                onClick = {
                                    showMenu = false
                                    activity.stopWakeLock()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_add_flagsecure)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Speed, null) },
                                onClick = {
                                    showMenu = false
                                    activity.addFlagSecure()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_clear_flagsecure)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.SettingsBackupRestore, null) },
                                onClick = {
                                    showMenu = false
                                    activity.clearFlagSecure()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_turbo)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Speed, null) },
                                onClick = {
                                    showMenu = false
                                    activity.lifecycleScope.launch(Dispatchers.IO) {
                                        activity.turbo.enterTurboMode()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_turbo_stop)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.SettingsBackupRestore, null) },
                                onClick = {
                                    showMenu = false
                                    activity.lifecycleScope.launch(Dispatchers.IO) {
                                        activity.turbo.exitTurboMode()
                                    }
                                }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(12.dp).fillMaxSize()) {

            CommandInputSection(
                query = query,
                onQueryChange = { query = it },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                filteredItems = filteredItems,
                getItemCommand = { it.command },
                getItemDescription = { it.description },
                isAppItem = { it.isApp },
                isAdbItem = { it.isAdb }
            )
            
            LogSection(
                uiUpdateVersionFlow = viewModel.uiUpdateVersion,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            )
            
            IdentityFooterSection()
        }
    }
}

@Keep
private suspend fun processAndSaveAvatar(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

        val targetSize = 256 
        var sampleSize = 1
        val minRawSize = Math.min(options.outWidth, options.outHeight)
        while (minRawSize / sampleSize / 2 >= targetSize) {
            sampleSize *= 2
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { 
            BitmapFactory.decodeStream(it, null, options) 
        } ?: return@withContext false

        val rawWidth = rawBitmap.width
        val rawHeight = rawBitmap.height
        val cropSize = Math.min(rawWidth, rawHeight)
        
        val left = (rawWidth - cropSize) / 2
        val top = (rawHeight - cropSize) / 2
        
        val srcRect = Rect(left, top, left + cropSize, top + cropSize)
        val dstRect = Rect(0, 0, targetSize, targetSize)

        val circularBitmap = createBitmap(targetSize, targetSize)
        val canvas = Canvas(circularBitmap)
        val paint = Paint().apply { 
            isAntiAlias = true 
            isFilterBitmap = true
        }
        
        val radius = targetSize / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(rawBitmap, srcRect, dstRect, paint)
        
        rawBitmap.recycle()

        val outFile = File(context.filesDir, "custom_avatar.png")
        FileOutputStream(outFile).use { out ->
            circularBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        circularBitmap.recycle()
        true
    }.getOrDefault(false)
}

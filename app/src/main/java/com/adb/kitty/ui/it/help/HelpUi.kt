package com.adb.kitty.ui.it.help

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.data.help.*
import com.adb.kitty.*
import com.adb.kitty.R

import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandHelpBottomSheet(
    isVisible: Boolean,
    onDismissRequest: () -> Unit
) {
    if (!isVisible) return
    val commandList = remember {
        listOf(
            CommandHelp(
                title = "ADB 无线/有线调试",
                command = "adb",
                description = "使用有线/无线连接到adbd",
                options = listOf(
                    CommandOption(
                        flag = "adb pair IP地址 配对端口 配对码",
                        description = "adb pair 198.0.0.1 13056 123456"
                    ),
                    CommandOption(
                        flag = "adb connect IP地址 无线调试端口",
                        description = "adb connect 198.0.0.1 13056"
                    ),
                    CommandOption(
                        flag = "adb disconnect",
                        description = "断开无线调试连接"
                    ),
                    CommandOption(
                        flag = "adb push",
                        description = "推送文件"
                    ),
                    CommandOption(
                        flag = "adb pull",
                        description = "拉取文件"
                    ),
                    CommandOption(
                        flag = "adb install",
                        description = "安装软件/应用/APP"
                    ),
                    CommandOption(
                        flag = "adb uninstall",
                        description = "卸载软件/应用/APP"
                    ),
                    CommandOption(
                        flag = "adb shell",
                        description = "adb shell 会话"
                    ),
                    CommandOption(
                        flag = "adb root",
                        description = "adb root 会话"
                    ),
                    CommandOption(
                        flag = "adb unroot",
                        description = "降级为 adb shell 会话"
                    ),
                    CommandOption(
                        flag = "adb reboot",
                        description = "重启设备"
                    )
                )
            ),
            CommandHelp(
                title = "fastboot 刷机",
                command = "fastboot",
                description = "使用 fastboot 原生底层链路进行有线刷机, 高级玩法请迁移至 Termux 使用 fastboot 可执行文件来完成",
                options = listOf(
                    CommandOption(
                        flag = "reboot 可选参数",
                        description = "重启"
                    ),
                    CommandOption(
                        flag = "getvar 参数",
                        description = "查询参数"
                    ),
                    CommandOption(
                        flag = "oem 参数",
                        description = "解锁、回锁或查询操作"
                    ),
                    CommandOption(
                        flag = "erase 分区",
                        description = "擦除分区数据"
                    ),
                    CommandOption(
                        flag = "format 分区",
                        description = "格式化分区"
                    ),
                    CommandOption(
                        flag = "set_active a或b",
                        description = "切换活跃插槽"
                    ),
                    CommandOption(
                        flag = "flash 分区 路径",
                        description = "刷写分区"
                    ),
                    CommandOption(
                        flag = "boot 文件名",
                        description = "临时引导数据"
                    ),
                    CommandOption(
                        flag = "--fb fastboot.bash",
                        description = "批量执行 fastboot 指令"
                    )
                )
            ),
            CommandHelp(
                title = "Shell",
                command = "id",
                description = "Shell",
                options = listOf(
                    CommandOption(
                        flag = "adb shell id",
                        description = "adb Shell"
                    ),
                    CommandOption(
                        flag = "su -c id",
                        description = "Root Shell"
                    )
                )
            ),
            CommandHelp(
                title = "解析 APK 签名",
                command = "apk-sig 或 neko-sig",
                description = "调出底部对话框来从应用列表选择或从本地选择 APK 进行签名解析",
                options = emptyList()
            ),
            CommandHelp(
                title = "下载",
                command = "download 链接",
                description = "下载文件, 仅限 http/https 链接",
                options = emptyList()
            ),
            CommandHelp(
                title = "查询高级保护模式",
                command = "query-apm",
                description = "查询设备上的高级保护模式是否启用, Android 16+ 专属",
                options = emptyList()
            ),
            CommandHelp(
                title = "生成 QR 二维码",
                command = "qr-gen 文本",
                description = "生成二维码, 最多 2000 字节",
                options = emptyList()
            ),
            CommandHelp(
                title = "二维码解码",
                command = "qr-decode 文件",
                description = "二维码解码",
                options = listOf(
                    CommandOption(
                        flag = "qr-decode --system",
                        description = "从系统图片选择器选择二维码图片来进行解码操作"
                    )
                )
            ),
            CommandHelp(
                title = "提取视频中的音频",
                command = "neko-audio",
                description = "唤起系统的视频选择器来选择视频进行提取音频的操作",
                options = emptyList()
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "指令集使用说明",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(commandList) { help ->
                    CommandHelpItem(help = help)
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                item {
                    SupportAndDependencySection()
                }
            }
        }
    }
}

@Keep
@Composable
private fun CommandHelpItem(help: CommandHelp) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = help.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = help.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = help.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (help.options.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "可选参数选项：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(4.dp))

                help.options.forEachIndexed { index, option ->
                    val isLast = index == help.options.lastIndex
                    val prefixBranch = if (isLast) "└─ " else "├─ "

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = prefixBranch,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.flag,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Keep
@Composable
private fun SupportAndDependencySection() {
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Application",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            InfoRow(label = "System", value = "Android 7.0 - 17")
            InfoRow(label = "api", value = "24 - 37")
            InfoRow(label = "abi", value = "arm64-v8a, armeabi-v7a, x86, x86_64, riscv64")
            InfoRow(label = "github", value = "https://github.com/allmyCyou/nekoStudio")
            InfoRow(label = "fastboot数据目录", value = "/storage/emulated/0/Android/data/com.adb.kitty/files/flash")
            InfoRow(label = "adb数据目录", value = "/storage/emulated/0/Android/data/com.adb.kitty/files/flash")
            InfoRow(label = "cpu数据目录", value = "/storage/emulated/0/Android/data/com.adb.kitty/files/cpu")
            InfoRow(label = "log数据目录", value = "/storage/emulated/0/Android/data/com.adb.kitty/cache/logs")
        }
    }
}

@Keep
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

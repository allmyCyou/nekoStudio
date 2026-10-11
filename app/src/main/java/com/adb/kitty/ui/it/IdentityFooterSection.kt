package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.lang.management.ManagementFactory

// 1. 数据类仅保留初始堆和最大堆配置
data class JvmHeapConfig(
    val initialHeapMb: Long, // 对应 -Xms
    val maxHeapMb: Long       // 对应 -Xmx
) {
    companion object {
        fun current(): JvmHeapConfig {
            // 使用 JMX 的 MemoryMXBean 获取精确配置
            val memoryMXBean = ManagementFactory.getMemoryMXBean()
            val heapMemoryUsage = memoryMXBean.heapMemoryUsage

            // init 是 JVM 启动时向系统申请的初始内存 (-Xms)
            // max 是 JVM 能够使用的最大内存 (-Xmx)
            val initialBytes = heapMemoryUsage.init
            val maxBytes = heapMemoryUsage.max

            return JvmHeapConfig(
                initialHeapMb = if (initialBytes > 0) initialBytes / 1024 / 1024 else -1,
                maxHeapMb = if (maxBytes > 0) maxBytes / 1024 / 1024 else -1
            )
        }
    }
}

@Composable
fun IdentityFooterSection(modifier: Modifier = Modifier) {
    // 2. 静态配置值，使用 remember 只在初始化时读取一次即可
    val heapConfig = remember { JvmHeapConfig.current() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(71.dp)
            .padding(start = 4.dp, end = 108.dp, top = 2.dp, bottom = 2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center // 只有两行，居中布局更美观
        ) {
            // 3. 仅展示 -Xms
            Text(
                text = "Initial Heap (-Xms): ${if (heapConfig.initialHeapMb > 0) "${heapConfig.initialHeapMb} MB" else "Undefined"}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 4. 仅展示 -Xmx
            Text(
                text = "Max Heap (-Xmx): ${if (heapConfig.maxHeapMb > 0) "${heapConfig.maxHeapMb} MB" else "Undefined"}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

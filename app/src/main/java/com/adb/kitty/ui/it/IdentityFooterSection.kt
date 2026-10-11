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

// 1. 定义存储 JVM 堆内存信息的数据类
data class JvmHeapIdentity(
    val totalMemoryMb: Long,
    val freeMemoryMb: Long,
    val usedMemoryMb: Long,
    val maxMemoryMb: Long
) {
    companion object {
        fun current(): JvmHeapIdentity {
            val runtime = Runtime.getRuntime()
            
            // 获取字节大小并转换为 MB
            val total = runtime.totalMemory() / 1024 / 1024
            val free = runtime.freeMemory() / 1024 / 1024
            val max = runtime.maxMemory() / 1024 / 1024
            val used = total - free

            return JvmHeapIdentity(
                totalMemoryMb = total,
                freeMemoryMb = free,
                usedMemoryMb = used,
                maxMemoryMb = max
            )
        }
    }
}

@Composable
fun IdentityFooterSection(modifier: Modifier = Modifier) {
    // 2. 记住当前的 JVM 堆快照信息
    val heapInfo = remember { JvmHeapIdentity.current() }

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
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 3. 替换为堆内存信息的文本展示
            Text(
                text = "Total Heap: ${heapInfo.totalMemoryMb} MB",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Used Heap: ${heapInfo.usedMemoryMb} MB",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Free Heap: ${heapInfo.freeMemoryMb} MB",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Max Heap (-Xmx): ${heapInfo.maxMemoryMb} MB",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

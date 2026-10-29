package io.github.mangi.minesbot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 就绪面板。
 *
 * 这个 App 不需要天天看。它常驻后台，被 VPS 操控。
 * 用户打开它只需要确认三件事：
 *   1. 无障碍服务开没开
 *   2. 连上 VPS 没有
 *   3. 最近执行了什么
 *
 * 刻意做得很朴素 —— 交互在 VPS 那侧，不在手机上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadyScreen(vm: PhoneViewModel = viewModel()) {
    val ui by vm.state.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("MinesBot 手机端") }) },
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusCard(
                title = "无障碍服务",
                ok = ui.serviceAlive,
                okText = "已启用",
                badText = "未启用 —— 点下面按钮去开",
                action = {
                    OutlinedButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    })
                    { Text("去开启") }
                },
            )

            StatusCard(
                title = "VPS 连接",
                ok = ui.connected,
                okText = "已连接",
                badText = ui.lastError ?: "未连接",
                action = {
                    OutlinedButton(onClick = vm::reconnect) { Text("重连") }
                },
            )

            if (ui.serviceAlive && ui.connected) {
                Button(
                    onClick = vm::requestObserve,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PhotoCamera, null)
                    Text("  立即截图回传（测试链路）")
                }
            }

            Text(
                "最近执行",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(ui.recent) { r -> LogRow(r) }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
    action: @Composable () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (ok) Color(0xFF3DDC84) else MaterialTheme.colorScheme.error,
                )
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (ok) okText else badText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (!ok) action()
        }
    }
}

@Composable
private fun LogRow(r: LogEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            r.text,
            style = MaterialTheme.typography.bodySmall,
            color = when (r.kind) {
                LogKind.BAD -> MaterialTheme.colorScheme.error
                LogKind.GOOD -> Color(0xFF3DDC84)
                LogKind.PLAIN -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            r.time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

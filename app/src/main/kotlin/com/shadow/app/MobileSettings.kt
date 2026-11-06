package com.shadow.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Composable internal fun MobileSettings(state: ScreenState, vm: BotViewModel) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(RealtimePush.enabled(context)) }
    var pushState by remember { mutableStateOf(RealtimePush.status(context)) }
    val enable = { context.activity()?.let(vm::enableRealtimePush); Unit }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) enable() else vm.shareError("通知权限未开启，可在系统设置中开启。") }
    LaunchedEffect(state.baseUrl, state.deviceManaged) {
        while (true) { enabled = RealtimePush.enabled(context); pushState = RealtimePush.status(context); delay(1000) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("设备与实时通知", style = MaterialTheme.typography.titleMedium)
        if (!state.deviceManaged) Text("重新登录或用口令连接后，可启用本机设备会话。", style = MaterialTheme.typography.bodySmall)
        if ("realtimePush" in state.bot.capabilities && state.deviceManaged) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("实时通知")
                    Text(when (pushState) {
                        "ready" -> "已连接推送服务"
                        "registering" -> "正在登记推送服务…"
                        "unavailable", "error" -> "连接不可用，可关闭后重新连接"
                        else -> "通过手机上的 UnifiedPush 服务接收提醒"
                    }, style = MaterialTheme.typography.bodySmall)
                }
                Switch(enabled, { checked ->
                    if (!checked) { RealtimePush.disable(context); enabled = false }
                    else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else enable()
                }, enabled = !state.connecting && !state.bot.needsLogin)
            }
            Text("推送服务只收到加密唤醒；消息内容由手机登录后读取。可同时保留定期检查作为补充。", style = MaterialTheme.typography.bodySmall)
        }
        Row { Text("已连接设备", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); TextButton(onClick = vm::loadDevices, enabled = !state.deviceBusy) { Text("刷新") } }
        Text("撤销会使该设备的 Bot 凭证、通知和伙伴语音失效；统一账号及浏览器登录分别管理。", style = MaterialTheme.typography.bodySmall)
        state.devices.forEach { device ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(device.name + if (device.current) " · 本机" else "")
                    Text(if (device.active) "最近使用 ${date(device.lastSeenAt)} · 到期 ${date(device.expiresAt)}" else "已失效", style = MaterialTheme.typography.bodySmall)
                }
                if (device.active) TextButton(onClick = { vm.revokeDevice(device) }, enabled = !state.deviceBusy && !state.connecting) { Text("撤销") }
            }
        }
    }
}

private fun date(at: Long) = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

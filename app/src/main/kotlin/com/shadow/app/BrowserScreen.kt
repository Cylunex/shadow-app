package com.shadow.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shadow.bot.*

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BrowserScreen(state: ScreenState, vm: BotViewModel) {
    val data = state.bot.browser
    val view = data.view?.takeIf { it.entryId == state.bot.selected }
    val enabled = !state.bot.busy && !state.bot.needsLogin && !state.connecting && !data.loading
    val list = rememberLazyListState()
    LaunchedEffect(view?.sessionId, view?.version) { list.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("操作伙伴正在使用的网页。交回浏览器只结束人工接手；需要继续执行时，在原事项接着做。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if ("browserHandoff" !in state.bot.capabilities) item { Text("当前服务尚未开放手机浏览器接手。") }
        else {
            if (data.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            data.notice?.let { item { Text(it) } }
            if (view == null) data.followupWorkId?.let { id -> item { TextButton(onClick = { vm.openWork(id) }, enabled = enabled) { Text("打开原事项") } } }
            data.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { TextButton(onClick = vm::reloadBrowser, enabled = enabled) { Text("重新读取接手列表") } }
            if (data.loaded && data.sessions.isEmpty()) item { Text("当前伙伴没有等待接手的页面。") }
            if (view == null) items(data.sessions, key = { it.id }) { session ->
                Surface(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(session.goal.ifBlank { "等待你接手的网页" }, style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.inspectBrowser(session) }, enabled = enabled) { Text("查看并接手") }
                            TextButton(onClick = { vm.openWork(session.workId) }, enabled = enabled) { Text("打开原事项") }
                        }
                    }
                }
            }
            else {
                item { Text(view.title.ifBlank { "浏览器页面" }, style = MaterialTheme.typography.titleLarge) }
                item { Text("以下为读取时的页面；控件变化后需重新查看。填写内容仅用于本次操作，离开后清除。", style = MaterialTheme.typography.bodySmall) }
                if (view.screenshot.isNotBlank()) item {
                    val bitmap = remember(view.sessionId, view.version) {
                        runCatching {
                            require(view.screenshot.length <= 6_000_000)
                            val bytes = Base64.decode(view.screenshot, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bitmap != null) Image(bitmap, "读取时的浏览器页面", Modifier.fillMaxWidth())
                    else Text("截图未能显示，可重新查看页面。")
                }
                item {
                    var showText by remember(view.sessionId, view.version) { mutableStateOf(false) }
                    TextButton(onClick = { showText = !showText }) { Text(if (showText) "收起页面文字" else "查看页面文字") }
                    if (showText) Text(view.text, style = MaterialTheme.typography.bodyMedium)
                }
                item { BrowserControls(view, enabled, vm) }
                item { TextButton(onClick = { data.sessions.find { it.id == view.sessionId }?.let(vm::inspectBrowser) }, enabled = enabled) { Text("重新查看页面") } }
                item { OutlinedButton(onClick = { vm.actBrowser(view, "resume") }, enabled = enabled) { Text("交回伙伴") } }
                item { TextButton(onClick = { vm.openWork(view.workId) }, enabled = enabled) { Text("打开原事项") } }

            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun BrowserControls(view: BrowserView, enabled: Boolean, vm: BotViewModel) {
    // Intentionally not rememberSaveable: passwords and verification codes must
    // not enter saved instance state, ViewModel drafts or disk persistence.
    var selected by remember(view.sessionId, view.version) { mutableStateOf<String?>(null) }
    var value by remember(view.sessionId, view.version, selected) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var choosing by remember(view.sessionId, view.version) { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(view.controls.find { it.ref == selected }?.label?.ifBlank { "选中的控件" } ?: "选择页面控件")
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                view.controls.forEach { control ->
                    DropdownMenuItem(text = { Text(control.label.ifBlank { control.tag } + if (control.disabled) "（不可用）" else "") },
                        enabled = !control.disabled, onClick = { selected = control.ref; choosing = false })
                }
            }
        }
        view.controls.find { it.ref == selected }?.let { control ->
            if (control.fillable) OutlinedTextField(value, { if (it.length <= 10000) value = it }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                label = { Text("填写内容（不保存）") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (control.fillable) Button(onClick = { val text = value; value = ""; vm.actBrowser(view, "fill", control.ref, text) }, enabled = enabled) { Text("填写选中控件") }
                OutlinedButton(onClick = { value = ""; vm.actBrowser(view, "click", control.ref) }, enabled = enabled) { Text("点击选中控件") }
            }
        }
        if (view.controls.isEmpty()) Text("当前可见区域没有可操作控件。")
    }
}

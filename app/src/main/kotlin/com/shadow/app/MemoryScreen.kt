package com.shadow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable internal fun MemoryScreen(state: ScreenState, vm: BotViewModel) {
    val browser = state.bot.memoryBrowser
    val detail = browser.detail
    val browsing = "memoryBrowse" in state.bot.capabilities
    val kinds = linkedMapOf("" to "全部类型", "episode" to "共同经历", "preference" to "偏好", "profile" to "个人资料", "thread" to "持续话题", "relationship" to "相处关系", "play" to "虚构情境")
    var query by remember(browser.query) { mutableStateOf(browser.query) }
    var kindMenu by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = remember(browser.query, browser.kind, browser.revision) { LazyListState() }
    val detailState = rememberLazyListState()
    var action by remember(detail?.item?.id) { mutableStateOf("") }
    var correction by remember(detail?.item?.id) { mutableStateOf(detail?.item?.text.orEmpty()) }
    Column(Modifier.fillMaxSize()) {
        if (detail == null && browsing) Column(Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(query, { query = it.take(200) }, singleLine = true, label = { Text("搜索全部有效记忆") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.searchMemories(query, browser.kind) }), modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { keyboard?.hide(); vm.searchMemories(query, browser.kind) }, enabled = !browser.loading) { Text("搜索") }
                Box {
                    TextButton(onClick = { kindMenu = true }) { Text(kinds[browser.kind] ?: "全部类型") }
                    DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                        kinds.forEach { (kind, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { kindMenu = false; keyboard?.hide(); vm.searchMemories(query, kind) }) }
                    }
                }
                if (browser.query.isNotEmpty() || browser.kind.isNotEmpty()) TextButton(onClick = { query = ""; vm.searchMemories("", "") }) { Text("清除") }
            }
        }
        LazyColumn(Modifier.weight(1f), state = if (detail == null) listState else detailState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("你与伙伴共享的有效记忆。更正事实、减少主动提及，或停止使用相关来源。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (browser.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        browser.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::reloadMemories, enabled = !browser.loading) { Text("重新读取") } } }
        if (detail != null) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectionContainer { Text(detail.item.text) }
                        Row { TextButton(onClick = { action = "correct" }) { Text("更正") }; TextButton(onClick = { action = "quiet" }) { Text("少提") }; TextButton(onClick = { action = "forget" }) { Text("遗忘") } }
                        if (action == "correct") OutlinedTextField(correction, { correction = it.take(2000) }, label = { Text("改成什么") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        if (action == "quiet") Text("保留这段记忆，减少主动提及；你明确问起时仍可回忆。", style = MaterialTheme.typography.bodySmall)
                        if (action == "forget") Text("停止使用这条记忆及相关来源和衍生内容，同一来源的其他记忆也可能受影响；已有业务记录不删除。", style = MaterialTheme.typography.bodySmall)
                        if (action.isNotEmpty()) Button(onClick = { vm.editMemory(detail.item, "memory.$action", correction) }, enabled = !state.bot.busy && (action != "correct" || correction.isNotBlank())) {
                            Text(when (action) { "correct" -> "保存更正"; "quiet" -> "以后少提"; else -> "遗忘相关内容" })
                        }
                        TextButton(onClick = vm::closeMemory) { Text("收起详情") }
                    }
                }
            }
            item { Text("来源", style = MaterialTheme.typography.titleMedium) }
            items(detail.sources, key = { it.id }) { source ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(source.at, style = MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(source.text) }
                    if (source.truncated) Text("来源较长，仅展示前 4000 字。", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            if (browser.loaded && browser.items.isEmpty()) item { Text(if (browser.query.isNotEmpty() || browser.kind.isNotEmpty()) "没有找到相关记忆，试试其他关键词或类型。" else "暂时没有可显示的记忆。") }
            items(browser.items, key = { it.id }) { memory ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(kinds[memory.kind] ?: "记忆", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SearchMessageText(memory.text, browser.query)
                        if ("memoryActions" in state.bot.capabilities) TextButton(onClick = { vm.inspectMemory(memory.id) }) { Text("来源与修改") }
                    }
                }
            }
            if (!browsing) item { Text("当前服务显示最近 100 条有效记忆；升级服务后可搜索和翻页。", style = MaterialTheme.typography.labelSmall) }
            else if (browser.loaded) item {
                Text("已显示 ${browser.items.size} 条${if (browser.next == null) "，已全部加载" else ""}", style = MaterialTheme.typography.labelSmall)
                if (browser.next != null && browser.items.size < 600) TextButton(onClick = vm::moreMemories, enabled = !browser.loading && browser.error == null) { Text("加载更多记忆") }
                if (browser.next != null && browser.items.size >= 600) Text("已达本次浏览上限，请用关键词或类型缩小范围。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::reloadMemories, enabled = !browser.loading) { Text("刷新记忆") }
            }
        }
        }
    }
}

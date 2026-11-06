package com.shadow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.bot.MessageContextPage
import kotlinx.coroutines.launch

@Composable internal fun MessageContextScreen(state: ScreenState, vm: BotViewModel) {
    val page = state.bot.messageContext
    if (page == null) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.bot.contextBusy) { CircularProgressIndicator(); Text("正在定位消息…") }
            else Text(state.bot.contextError ?: "历史内容已更新，请返回搜索后重新定位。")
            TextButton(onClick = { vm.navigate(Destination.Search) }) { Text("返回搜索") }
        }
        return
    }
    MessageContextView(page, state.bot.searchQuery, state.bot.contextBusy, state.bot.offline || state.bot.syncIssue != null,
        vm::moreMessageContext, vm::resetMessageContext)
}

@Composable internal fun MessageContextView(page: MessageContextPage, query: String, busy: Boolean, offline: Boolean,
    onMore: (Boolean) -> Unit, onReset: () -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val anchorIndex = page.items.indexOfFirst { it.id == page.anchor.id && it.role == page.anchor.role }
    // The initial jump is keyed by identity, not by page size or background refresh.
    LaunchedEffect(page.entryId, page.anchor) { if (anchorIndex >= 0) list.scrollToItem(anchorIndex + 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { scope.launch { if (anchorIndex >= 0) list.animateScrollToItem(anchorIndex + 1) } }) { Text("定位消息") }
            TextButton(onClick = onReset, enabled = !busy && !offline) { Text("重新读取") }
        }
        if (offline) Text("当前未连接，恢复后会核对历史内容。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelSmall)
        Box(Modifier.fillMaxWidth().height(2.dp)) { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "older") {
                if (page.before != null && page.items.size < 600) TextButton(onClick = { onMore(true) }, enabled = !busy && !offline) { Text("更早的消息") }
                else if (page.before == null) Text("已到聊天开头", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(page.items, key = { _, message -> "${message.role}:${message.id}" }) { index, message ->
                val anchor = message.id == page.anchor.id && message.role == page.anchor.role
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val day = dayLabel(message.at)
                    if (index == 0 || dayLabel(page.items[index - 1].at) != day) Text(day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(16.dp), color = if (anchor) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (message.role == "user") "你" else message.personaName.ifBlank { "伙伴" }} · ${time(message.at)}${if (anchor) " · 定位消息" else ""}", style = MaterialTheme.typography.labelSmall)
                            if (message.text.isNotBlank()) SearchMessageText(message.text, query)
                            message.attachments.forEach { Text("附件 · ${it.name}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            item(key = "newer") {
                if (page.after != null && page.items.size < 600) TextButton(onClick = { onMore(false) }, enabled = !busy && !offline) { Text("后面的消息") }
                else if (page.after == null) Text("已到本次读取的末尾", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (page.items.size >= 600) Text("本次已展示 600 条，可重新读取定位消息，或返回搜索其他内容。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

package com.shadow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.bot.*

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CommitmentScreen(state: ScreenState, vm: BotViewModel) {
    val data = state.bot.commitments
    val page = data.page?.takeIf { it.entryId == state.bot.selected }
    val browse = "commitmentBrowse" in state.bot.capabilities
    var query by remember(state.bot.selected, data.query) { mutableStateOf(data.query) }
    var filter by remember(state.bot.selected, data.status) { mutableStateOf(data.status.ifBlank { "all" }) }
    var expanded by remember(state.bot.selected) { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(data.query, data.status, page?.revision) { list.scrollToItem(0) }
    val pending = state.bot.pending.filter { it.entryId == state.bot.selected && it.commitmentId != null }
    val enabled = !state.bot.busy && !state.bot.needsLogin && !state.connecting && !data.loading
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("持续跟进的委托保留目标、等待条件和关联工作。新委托可在聊天里说明。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if ("commitmentDetails" !in state.bot.capabilities) item { Text("当前服务尚未开放手机持续事项管理，可在聊天中继续处理。") }
        else {
            if (data.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            data.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (browse) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { if (it.length <= 200) query = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索目标、结果和等待条件") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.searchCommitments(query, data.status) }, enabled = enabled) { Text("搜索持续事项") }
                        if (query.isNotEmpty() || data.query.isNotEmpty()) TextButton(onClick = { query = ""; vm.searchCommitments("", data.status) }, enabled = enabled) { Text("清除搜索") }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ongoing" to "跟进中", "ended" to "已结束", "all" to "全部").forEach { (value, label) ->
                        FilterChip(selected = filter == value, onClick = { filter = value; if (browse) vm.searchCommitments(data.query, if (value == "all") "" else value) }, label = { Text(label) })
                    }
                }
            }
            if (page != null) {
                val rows = if (browse) page.items else page.items.filter { filter == "all" || (it.state in setOf("completed", "cancelled")) == (filter == "ended") }
                if (browse) item { Text("共 ${page.total} 项，已加载 ${page.items.size} 项", style = MaterialTheme.typography.bodySmall) }
                if (rows.isEmpty()) item { Text("当前范围没有持续事项。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.id }) { item ->
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(when (item.state) { "active" -> "跟进中"; "waiting" -> "等待条件"; "paused" -> "已暂停"; "completed" -> "已完成"; "cancelled" -> "已停止"; else -> item.state }, style = MaterialTheme.typography.labelMedium)
                            if (item.match.isNotBlank()) Text(item.match, color = MaterialTheme.colorScheme.primary)
                            Text(item.goal, style = MaterialTheme.typography.titleMedium)
                            if (item.waitReason.isNotBlank()) Text(item.waitReason)
                            item.nextCheckAt?.let { Text("下次检查：${time(it)}", style = MaterialTheme.typography.bodySmall) }
                            if (item.result.isNotBlank()) Text(item.result)
                            if (item.resultTruncated) Text("结果仅显示前 1000 字，可打开关联工作查看。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { expanded = if (expanded == item.id) null else item.id }) { Text(if (expanded == item.id) "收起事项操作" else "查看事项操作") }
                            if (expanded == item.id) {
                                Text(when { item.kind == "page_watch" -> "按约定跟踪页面；到期后需在聊天中重新委托。"; item.completion == "user_judgment" -> "是否完成由你判断。"; item.completion == "ongoing" -> "持续跟进，一次工作结束不代表目标完成。"; else -> "完成状态以实际工作交付为准。" }, style = MaterialTheme.typography.bodySmall)
                                val text = state.commitmentDrafts[item.id] ?: item.goal
                                if (item.allowedActions.any(::commitmentNeedsText)) {
                                    OutlinedTextField(value = text, onValueChange = { vm.commitmentDraft(item.id, it) }, label = { Text("当前完整目标与要求") },
                                        supportingText = { Text("继续或更新时替换目标；最多 4000 字。") }, minLines = 2, maxLines = 6,
                                        isError = text.length > 4000, modifier = Modifier.fillMaxWidth())
                                }
                                item.allowedActions.filter { it in commitmentActions }.forEach { action ->
                                    TextButton(onClick = { vm.submitCommitment(item, action) }, modifier = Modifier.fillMaxWidth(),
                                        enabled = enabled && pending.none { it.commitmentId == item.id } && (!commitmentNeedsText(action) || text.isNotBlank() && text.length <= 4000)) { Text(commitmentActionLabel(action)) }
                                }
                                if ("commitment.cancel" in item.allowedActions) Text("停止后不再跟进，已有结果和外部操作会保留。", style = MaterialTheme.typography.bodySmall)
                                item.workId?.takeIf { "workDetails" in state.bot.capabilities }?.let { workId ->
                                    TextButton(onClick = { vm.openWork(workId) }) { Text("查看关联工作与成果") }
                                }
                            }
                        }
                    }
                }
                if (browse) {
                    if (page.next != null && page.items.size < 600) item { TextButton(onClick = vm::moreCommitments, enabled = enabled) { Text("加载更多持续事项") } }
                    if (page.next != null && page.items.size >= 600) item { Text("已显示 600 项，请缩小搜索范围。") }
                } else item { Text(if (page.hasMore) "仅显示最近 100 项有效持续事项，筛选仅作用于这些记录。更早的委托可在聊天中查找。" else "当前伙伴的有效持续事项；已遗忘或来源失效的记录不会显示。", style = MaterialTheme.typography.bodySmall) }

            }
            item { TextButton(onClick = vm::reloadCommitments, enabled = enabled) { Text("重新读取持续事项") } }
        }
        if (pending.isNotEmpty()) item { Text("等待确认的操作", style = MaterialTheme.typography.titleMedium) }
        items(pending, key = { "pending:${it.id}" }) { command ->
            Column {
                Text(command.label + command.text.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty())
                TextButton(onClick = { vm.retry(command.id) }, enabled = enabled) { Text("重试原操作") }
                TextButton(onClick = { vm.removePending(command.id) }, enabled = !state.bot.busy) { Text("移除待发记录") }
                Text("移除本机记录不会撤回服务端已执行的操作；版本冲突时先刷新核对。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

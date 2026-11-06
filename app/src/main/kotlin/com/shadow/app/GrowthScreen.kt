package com.shadow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun GrowthScreen(state: ScreenState, vm: BotViewModel) {
    val growth = state.bot.growth
    val page = growth.page?.takeIf { it.entryId == state.bot.selected }
    // Equality preserves reading position across unchanged legacy full responses too.
    val list = remember(page) { LazyListState() }
    var expanded by remember(page) { mutableStateOf<Set<Int>>(emptySet()) }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("从真实相处中沿用的习惯，以及从阅读中形成的看法。它们会随你的补充和新材料变化。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if ("growth" !in state.bot.capabilities) item { Text("当前服务尚未开放成长手记。") }
        else {
            if (growth.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            growth.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (page == null && growth.error == null && state.bot.syncIssue != null) item { Text("连接暂时不可用，恢复后会重新读取手记。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (page != null) {
                item {
                    Text("${page.personaName}的相处习惯", style = MaterialTheme.typography.titleLarge)
                    Text("来自你明确说过的聊天偏好。仅列出现在仍适用的部分。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (page.habits.isEmpty()) item { Text("暂时没有可沿用的相处习惯，不代表没有共同记忆。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(page.habits, key = { index, _ -> "habit:$index" }) { index, habit ->
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (habit.scope == "day") "今天适用" else if (habit.scope == "persistent") "持续沿用" else "当前适用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(habit.text, style = MaterialTheme.typography.titleMedium)
                            if (habit.quote.isNotBlank()) {
                                Text("依据原话", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(habit.quote) }
                            }
                            TextButton(onClick = { vm.discussGrowth(page, "habit", index) }) { Text("带回聊天补充") }
                        }
                    }
                }
                item {
                    Text("阅读中形成的看法", style = MaterialTheme.typography.titleLarge)
                    Text("这是伙伴的看法，材料摘录是依据，不代表亲身经历。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (page.views.isEmpty()) item { Text("暂时没有可回查材料的阅读看法。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(page.views, key = { index, _ -> "view:$index" }) { index, view ->
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(view.topic, style = MaterialTheme.typography.titleMedium)
                            Text(if (view.maturity == "established") "有多份材料支持" else "初步看法", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(view.text) }
                            TextButton(onClick = { expanded = if (index in expanded) expanded - index else expanded + index }) { Text(if (index in expanded) "收起阅读依据" else "查看阅读依据") }
                            if (index in expanded) {
                                Text(view.sourceTitle.ifBlank { "阅读材料" }, style = MaterialTheme.typography.titleSmall)
                                SelectionContainer { Text(view.evidenceQuote.ifBlank { "暂无可显示的摘录。" }) }
                            }
                            TextButton(onClick = { vm.discussGrowth(page, "view", index) }) { Text("带回聊天讨论") }
                        }
                    }
                }
                item { Text("只展示当前人格可用的少量手记。带回聊天后可编辑，再由你发送。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { TextButton(onClick = vm::reloadGrowth, enabled = !growth.loading && !state.bot.needsLogin) { Text("重新读取手记") } }
        }
    }
}

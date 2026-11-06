package com.shadow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun MessageSearchScreen(state: ScreenState, vm: BotViewModel) {
    var query by rememberSaveable(state.bot.selected) { mutableStateOf(state.bot.searchQuery) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(query, { query = it.take(200) }, label = { Text("搜索这个聊天的全部记录") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.searchMessages(query) }, enabled = query.isNotBlank() && !state.bot.busy) { Text("搜索") }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.bot.searchDone && state.bot.searchResults.isEmpty()) item { Text("没有找到相关消息，试试其他关键词。") }
            items(state.bot.searchResults, key = { "${it.role}:${it.id}" }) { message ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${if (message.role == "user") "你" else message.personaName.ifBlank { "伙伴" }} · ${time(message.at)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                    HorizontalDivider()
                }
            }
            if (state.bot.searchCursor != null) item {
                TextButton(onClick = { vm.searchMessages(state.bot.searchQuery, true) }, enabled = !state.bot.busy) { Text("更多结果") }
            }
        }
    }
}

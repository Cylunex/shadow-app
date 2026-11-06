package com.shadow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

internal fun highlightedMessage(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isNotBlank()) {
        var start = 0
        // Literal matching, bounded even for a one-letter search in a long message.
        repeat(500) {
            val index = text.indexOf(query, startIndex = start, ignoreCase = true)
            if (index < 0) return@buildAnnotatedString
            addStyle(style, index, index + query.length)
            start = index + query.length
        }
    }
}

@Composable internal fun SearchMessageText(text: String, query: String) {
    val style = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, color = MaterialTheme.colorScheme.onTertiaryContainer)
    val annotated = remember(text, query, style) { highlightedMessage(text, query, style) }
    SelectionContainer { Text(annotated, style = MaterialTheme.typography.bodyLarge) }
}

@Composable internal fun MessageSearchScreen(state: ScreenState, vm: BotViewModel, listState: LazyListState = rememberLazyListState()) {
    var query by remember(state.bot.selected) { mutableStateOf(state.bot.searchQuery) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun search() { if (query.isNotBlank() && !state.bot.busy) { keyboard?.hide(); vm.searchMessages(query.trim()) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(query, { query = it.take(200) }, label = { Text("搜索这个聊天的全部记录") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { search() }, enabled = query.isNotBlank() && !state.bot.busy) { Text("搜索") }
        if (state.bot.searchDone && state.bot.searchResults.isNotEmpty()) Text("已找到 ${state.bot.searchResults.size} 条${if (state.bot.searchCursor != null) "，可继续加载" else ""}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.bot.searchDone && state.bot.searchResults.isEmpty()) item { Text("没有找到相关消息，试试其他关键词。") }
            items(state.bot.searchResults, key = { "${it.role}:${it.id}" }) { message ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${if (message.role == "user") "你" else message.personaName.ifBlank { "伙伴" }} · ${time(message.at)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SearchMessageText(message.text, state.bot.searchQuery)
                    if ("messageContext" in state.bot.capabilities) TextButton(onClick = { keyboard?.hide(); vm.openMessageContext(message) }, enabled = !state.bot.busy) { Text("查看前后文") }
                    HorizontalDivider()
                }
            }
            if (state.bot.searchCursor != null) item {
                TextButton(onClick = { vm.searchMessages(state.bot.searchQuery, true) }, enabled = !state.bot.busy) { Text("更多结果") }
            }
        }
    }
}

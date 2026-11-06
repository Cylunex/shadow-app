package com.shadow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun MemoryScreen(state: ScreenState, vm: BotViewModel) {
    val detail = state.memoryDetail
    var action by remember(detail?.item?.id) { mutableStateOf("") }
    var correction by remember(detail?.item?.id) { mutableStateOf(detail?.item?.text.orEmpty()) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("你与伙伴共享的有效记忆。更正事实、减少主动提及，或停止使用相关来源。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            if (state.memories.isEmpty()) item { Text("暂时没有可显示的记忆。") }
            items(state.memories, key = { it.id }) { memory ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp)) {
                        SelectionContainer { Text(memory.text) }
                        if ("memoryActions" in state.bot.capabilities) TextButton(onClick = { vm.inspectMemory(memory.id) }) { Text("来源与修改") }
                    }
                }
            }
            item { Text("显示最近 100 条有效记忆。", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

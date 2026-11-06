package com.shadow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun WorkDetailScreen(state: ScreenState, vm: BotViewModel) {
    val detail = state.bot.workDetail?.takeIf { it.work.id == state.viewedWorkId }
    if (detail == null) {
        Text(if (state.bot.busy) "正在读取事项…" else "暂时无法显示该事项，请刷新或返回。", Modifier.padding(24.dp))
        return
    }
    val work = detail.work
    val pending = state.bot.pending.filter { it.workId == work.id }
    val draft = state.workDrafts[work.id].orEmpty()
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(work.goal, style = MaterialTheme.typography.headlineSmall)
            Text(if (work.paused) "已暂停" else workState(work.state), color = MaterialTheme.colorScheme.primary)
        }
        if (detail.plan.isNotEmpty()) item { Text("当前计划", style = MaterialTheme.typography.titleMedium) }
        items(detail.plan.size) { index ->
            val step = detail.plan[index]
            Text("${if (step.status == "completed") "✓" else if (step.status == "in_progress") "→" else "○"} ${step.content}")
        }
        if (detail.planTruncated) item { Text("仅显示前 100 项计划。", style = MaterialTheme.typography.labelSmall) }
        if (work.result.isNotBlank()) item {
            Text("最新结果", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(work.result) }
            if (detail.resultTruncated) Text("结果较长，已显示前 16000 字；可在聊天中继续询问。", style = MaterialTheme.typography.labelSmall)
        }
        if (detail.artifacts.isNotEmpty()) item { Text("成果", style = MaterialTheme.typography.titleMedium) }
        items(detail.artifacts, key = { it.id }) { artifact ->
            Column {
                Text(artifact.name)
                Text(if (artifact.size > 0) "${(artifact.size + 1023) / 1024} KB · ${artifact.type}" else artifact.type, style = MaterialTheme.typography.labelSmall)
                if (artifact.textPreview && "artifactText" in state.bot.capabilities) TextButton(onClick = { vm.previewArtifact(artifact.id) }) { Text("查看内容") }
                else Text("此格式暂不支持手机预览，可通过现有工作台查看。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (pending.isNotEmpty()) item { Text("有操作等待确认，请先核对。", color = MaterialTheme.colorScheme.primary) }
        items(pending, key = { it.id }) { command ->
            Column {
                Text(command.label + command.text.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty())
                Row {
                    TextButton(onClick = { vm.retry(command.id) }, enabled = !state.bot.busy && !state.bot.needsLogin) { Text("重试原操作") }
                    TextButton(onClick = { vm.removePending(command.id) }, enabled = !state.bot.busy) { Text("移除待发记录") }
                }
                Text("移除本机记录不撤回服务端已执行的操作。", style = MaterialTheme.typography.labelSmall)
            }
        }
        if ("work.resume" in detail.allowedActions) item {
            OutlinedTextField(draft, { if (it.length <= 4000) vm.workDraft(it) }, label = { Text("补充要求或继续方向") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.submitWork("work.resume") }, enabled = draft.isNotBlank() && !state.bot.busy && !state.bot.needsLogin && pending.isEmpty()) { Text("在原事项继续") }
        }
        if ("work.cancel" in detail.allowedActions) item {
            TextButton(onClick = { vm.submitWork("work.cancel") }, enabled = !state.bot.busy && !state.bot.needsLogin && pending.isEmpty()) { Text("取消后续执行") }
            Text("已产生的结果和外部操作会保留。", style = MaterialTheme.typography.bodySmall)
        }
        if (detail.runs.isNotEmpty()) item { Text("最近执行记录", style = MaterialTheme.typography.titleMedium) }
        items(detail.runs, key = { it.id }) { run ->
            Column {
                Text("${time(run.startedAt)} · ${workState(run.state)}", style = MaterialTheme.typography.titleSmall)
                if (run.finishedAt > 0) Text("结束于 ${time(run.finishedAt)}", style = MaterialTheme.typography.labelSmall)
                if (run.result.isNotBlank()) Text(run.result)
                if (run.truncated) Text("本次结果已截短。", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable internal fun ArtifactScreen(state: ScreenState) {
    val artifact = state.bot.artifactText?.takeIf { it.id == state.viewedArtifactId && it.workId == state.viewedWorkId }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (artifact == null) item { Text(if (state.bot.busy) "正在读取成果…" else "成果暂不可用，请返回事项重新查看。") }
        else {
            item { Text(artifact.name, style = MaterialTheme.typography.titleLarge) }
            item { SelectionContainer { Text(artifact.text) } }
            if (artifact.truncated) item { Text("仅显示前 64000 字，完整文件可从现有工作台获取。", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

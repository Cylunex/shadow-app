package com.shadow.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shadow.bot.BotState

@Composable internal fun Progress(bot: BotState, vm: BotViewModel) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val ongoing = bot.works.count { it.isOngoing() }
    val works = bot.works.filter { when (filter) { 1 -> it.isOngoing(); 2 -> !it.isOngoing(); else -> true } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部 ${bot.works.size}", "进行中 $ongoing", "已结束 ${bot.works.size - ongoing}").forEachIndexed { index, label ->
                FilterChip(filter == index, { filter = index }, label = { Text(label) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (works.isEmpty()) item {
                Text(if (bot.works.isEmpty()) "还没有事项，在聊天里交代就好。" else "这里暂时没有事项。",
                    Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(works, key = { it.id }) { work ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = "workDetails" in bot.capabilities) { vm.openWork(work.id) }) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(if (work.paused) "已暂停" else workState(work.state))
                            Spacer(Modifier.weight(1f))
                            if ("workDetails" in bot.capabilities) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "查看事项", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(work.goal, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val summary = if (work.isOngoing()) work.nextStep.ifBlank { work.result } else work.result.ifBlank { work.nextStep }
                        if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (works.isNotEmpty()) item { Text("手机关闭后，已接收的事项仍会继续。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun StatusPill(label: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun WorkDetailScreen(state: ScreenState, vm: BotViewModel) {
    var saving by remember { mutableStateOf<com.shadow.bot.Artifact?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val artifact = saving; saving = null
        if (uri != null && artifact != null) vm.exportArtifact(artifact, uri)
    }
    val detail = state.bot.workDetail?.takeIf { it.work.id == state.viewedWorkId }
    if (detail == null) {
        Text(if (state.bot.busy) "正在读取事项…" else "暂时无法显示该事项，请刷新或返回。", Modifier.padding(24.dp))
        return
    }
    val work = detail.work
    val pending = state.bot.pending.filter { it.workId == work.id }
    val draft = state.workDrafts[work.id].orEmpty()
    var runsExpanded by rememberSaveable(work.id) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StatusPill(if (work.paused) "已暂停" else workState(work.state))
                Text(work.goal, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (work.nextStep.isNotBlank()) Text(work.nextStep, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (work.result.isNotBlank()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("最新结果")
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    SelectionContainer { Text(work.result, Modifier.fillMaxWidth().padding(16.dp)) }
                }
                if (detail.resultTruncated) Text("结果较长，已显示前 16000 字；可在聊天中继续询问。", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (detail.artifacts.isNotEmpty()) item { SectionTitle("成果 · ${detail.artifacts.size}") }
        items(detail.artifacts, key = { it.id }) { artifact ->
            val canPreview = artifact.textPreview && "artifactText" in state.bot.capabilities
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().clickable(enabled = canPreview) { vm.previewArtifact(artifact.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.List, null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(artifact.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (artifact.size > 0) "${(artifact.size + 1023) / 1024} KB · ${artifact.type}" else artifact.type,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (canPreview) "查看内容" else if (artifact.downloadable) "可保存或分享原件" else "此格式请在工作台查看", style = MaterialTheme.typography.labelMedium)
                        if (artifact.downloadable && "artifactDownload" in state.bot.capabilities) Row {
                            TextButton(onClick = { saving = artifact; save.launch(artifact.name) }) { Text("保存") }
                            TextButton(onClick = { vm.exportArtifact(artifact) }) { Text("分享") }
                        }
                    }
                    if (canPreview) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "查看成果")
                }
            }
        }
        if (detail.plan.isNotEmpty()) item { SectionTitle("当前计划 · ${detail.plan.count { it.status == "completed" }} / ${detail.plan.size}") }
        items(detail.plan.size) { index ->
            val step = detail.plan[index]
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        if (step.status == "completed") Icon(Icons.Outlined.Check, "已完成", Modifier.size(16.dp))
                        else Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(step.content, color = if (step.status == "completed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (step.status == "in_progress") Text("正在处理", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (detail.planTruncated) item { Text("仅显示前 100 项计划。", style = MaterialTheme.typography.labelSmall) }
        if (pending.isNotEmpty()) item { SectionTitle("等待确认的操作") }
        items(pending, key = { it.id }) { command ->
            Column {
                Text(command.label + command.text.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty())
                Row {
                    TextButton(onClick = { vm.retry(command.id) }, enabled = !state.bot.busy && !state.bot.needsLogin) { Text("重试原操作") }
                    TextButton(onClick = { vm.removePending(command.id) }, enabled = !state.bot.busy) { Text("移除记录") }
                }
                Text("移除本机记录不撤回已执行的操作。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if ("work.resume" in detail.allowedActions) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("继续这件事")
                OutlinedTextField(draft, vm::workDraft, label = { Text("补充要求或继续方向") }, minLines = 2, maxLines = 6,
                    isError = draft.length > 4000, supportingText = { if (draft.length > 3600) Text("${draft.length} / 4000") },
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.submitWork("work.resume") }, enabled = draft.isNotBlank() && draft.length <= 4000 && !state.bot.busy && !state.bot.needsLogin && pending.isEmpty(),
                    modifier = Modifier.fillMaxWidth()) { Text("继续处理") }
            }
        }
        if ("work.cancel" in detail.allowedActions) item {
            TextButton(onClick = { vm.submitWork("work.cancel") }, enabled = !state.bot.busy && !state.bot.needsLogin && pending.isEmpty()) { Text("停止后续执行") }
            Text("已产生的结果和外部操作会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (detail.runs.isNotEmpty()) item {
            TextButton(onClick = { runsExpanded = !runsExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text("最近执行记录 · ${detail.runs.size}", Modifier.weight(1f))
                Icon(if (runsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    if (runsExpanded) "收起执行记录" else "展开执行记录")
            }
        }
        if (runsExpanded) items(detail.runs, key = { it.id }) { run ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${time(run.startedAt)} · ${workState(run.state)}", style = MaterialTheme.typography.titleSmall)
                    if (run.finishedAt > 0) Text("结束于 ${time(run.finishedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (run.result.isNotBlank()) Text(run.result)
                    if (run.truncated) Text("本次结果已截短。", style = MaterialTheme.typography.labelSmall)
                }
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
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item { SelectionContainer { Text(artifact.text, style = MaterialTheme.typography.bodyLarge) } }
            if (artifact.truncated) item { Text("仅显示前 64000 字，完整文件可从现有工作台获取。", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

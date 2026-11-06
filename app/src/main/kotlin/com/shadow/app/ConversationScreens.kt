package com.shadow.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shadow.bot.BotRole
import com.shadow.bot.Attachment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable internal fun Contacts(state: ScreenState, vm: BotViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    val roles = state.bot.roles.filter {
        (query.isBlank() || it.name.contains(query.trim(), true) || it.preview.contains(query.trim(), true)) &&
            (!activeOnly || it.activeWorks > 0)
    }.sortedByDescending { it.lastMessageAt }
    Column(Modifier.fillMaxSize()) {
        if (state.sharedText != null || state.sharedUris.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.sharedUris.isEmpty()) "选择伙伴，编辑分享内容后发送" else "选择伙伴，添加 ${state.sharedUris.size} 个附件", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = vm::dismissShare) { Text("取消") }
            }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索伙伴或最近消息") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } },
            singleLine = true, shape = CircleShape, colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!activeOnly, { activeOnly = false }, label = { Text("全部 ${state.bot.roles.size}") })
            FilterChip(activeOnly, { activeOnly = true }, label = { Text("进行中 ${state.bot.roles.count { it.activeWorks > 0 }}") })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::refresh, enabled = !state.bot.busy) { Icon(Icons.Outlined.Refresh, "刷新会话") }
        }
        if (state.bot.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (roles.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when {
                        query.isNotBlank() -> "没有找到相关伙伴"
                        activeOnly -> "暂时没有进行中的事项"
                        state.bot.busy -> "正在连接你的伙伴…"
                        else -> "从一位伙伴开始"
                    }, style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        query.isNotBlank() -> "试试伙伴名字或最近消息中的关键词。"
                        activeOnly -> "在聊天里交代一件事，进展会出现在这里。"
                        else -> "聊一聊，也一起把事情做好。"
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    if (query.isNotBlank() || activeOnly) TextButton(onClick = { query = ""; activeOnly = false }) { Text("查看全部伙伴") }
                    else if ("createRole" in state.bot.capabilities) Button(onClick = { vm.navigate(Destination.CreateRole) }) { Text("添加伙伴") }
                }
            }
            items(roles, key = { it.id }) { role ->
                ContactRow(role, state.bot.pending.count { it.entryId == role.id }, state.drafts[role.id].orEmpty(), state.attachmentDrafts[role.id].orEmpty().size) { vm.open(role) }
            }
        }
    }
}

@Composable private fun ContactRow(role: BotRole, pending: Int, draft: String, attachmentCount: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BotAvatar(role.name, role.id, 52.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(role.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(contactTime(role.lastMessageAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(when {
                pending > 0 -> "$pending 条操作待确认"
                draft.isNotBlank() -> "草稿 · $draft"
                attachmentCount > 0 -> "草稿 · $attachmentCount 个附件"
                else -> role.preview.ifBlank { "开始一段对话" }
            }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = if (draft.isNotBlank() || pending > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (role.activeWorks > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                Text("${role.activeWorks} 项进行中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun Chat(state: ScreenState, vm: BotViewModel) {
    val bot = state.bot
    val role = bot.roles.find { it.id == bot.selected }
    val text = state.drafts[bot.selected].orEmpty()
    val pending = bot.pending.filter { it.entryId == bot.selected }
    val messages = bot.messages.asReversed()
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followingLatest by remember { mutableStateOf(true) }
    val showJump by remember { derivedStateOf { scroll.firstVisibleItemIndex > 1 || scroll.firstVisibleItemScrollOffset > 120 } }
    // Reverse layout anchors existing message keys while newer messages arrive. Only a user's
    // scroll changes follow mode; a snapshot changing the list size cannot pull them out of history.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to (scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset < 120) }
            .distinctUntilChanged().collect { (scrolling, nearLatest) -> if (scrolling) followingLatest = nearLatest }
    }
    LaunchedEffect(bot.messages.lastOrNull()?.id, pending.lastOrNull()?.id) {
        if (followingLatest && (messages.isNotEmpty() || pending.isNotEmpty())) scroll.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize()) {
        val ongoing = bot.works.count { it.isOngoing() }
        Surface(onClick = { vm.navigate(Destination.Progress) }, color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.AutoMirrored.Outlined.List, null, Modifier.size(20.dp))
                Text(if (ongoing > 0) "$ongoing 项进行中" else "事项与成果", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text("查看", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && pending.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BotAvatar(role?.name ?: "S", role?.id.orEmpty(), 64.dp)
                Text(if (bot.busy) "正在读取对话…" else "和${role?.name ?: "伙伴"}聊聊", style = MaterialTheme.typography.titleLarge)
                Text("从一句问候，或一件想做的事开始。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize(), state = scroll, reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(pending.asReversed(), key = { "pending:${it.id}" }) { command ->
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                        MessageBubble(command.text, true, "${command.label} · 等待确认", command.attachments, vm)
                        Row {
                            TextButton(onClick = { vm.retry(command.id) }, enabled = !bot.busy && !bot.needsLogin) { Text("重试") }
                            TextButton(onClick = { vm.removePending(command.id) }, enabled = !bot.busy) { Text("移除记录") }
                        }
                        Text("移除本机记录不会撤回已接收的操作。", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(messages, key = { _, message -> "${message.role}:${message.id}" }) { index, message ->
                    Column {
                        val older = messages.getOrNull(index + 1)
                        if (older == null || dayLabel(older.at) != dayLabel(message.at)) Text(dayLabel(message.at),
                            Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        MessageBubble(message.text, message.role == "user",
                            listOf(if (message.role == "user") "" else message.personaName, messageTime(message.at)).filter(String::isNotBlank).joinToString(" · "), message.attachments, vm)
                    }
                }
            }
            if (showJump) FilledTonalButton(onClick = { scope.launch { scroll.animateScrollToItem(0); followingLatest = true } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp), elevation = ButtonDefaults.filledTonalButtonElevation(4.dp)) {
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("回到最新")
            }
        }
        val attachments = state.attachmentDrafts[bot.selected].orEmpty()
        if (attachments.isNotEmpty()) AttachmentDrafts(attachments, vm)
        Composer(text, attachments, "attachments" in bot.capabilities, !bot.busy && !bot.needsLogin && bot.selected != null,
            onChange = vm::draft, onSend = { followingLatest = true; vm.send(text) }, onFiles = { vm.addAttachments(it) })
    }
}

@Composable private fun Composer(text: String, attachments: List<AttachmentDraft>, canAttach: Boolean, enabled: Boolean, onChange: (String) -> Unit, onSend: () -> Unit, onFiles: (List<android.net.Uri>) -> Unit) {
    var tooLong by remember { mutableStateOf(false) }
    var attachmentMenu by remember { mutableStateOf(false) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8), onFiles)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onFiles)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
                if (canAttach) Box {
                    IconButton(onClick = { attachmentMenu = true }, enabled = attachments.size < 8 && enabled, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Add, "添加图片或文件")
                    }
                    DropdownMenu(attachmentMenu, { attachmentMenu = false }) {
                        DropdownMenuItem(text = { Text("选择图片") }, onClick = { attachmentMenu = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                        DropdownMenuItem(text = { Text("选择文件") }, onClick = { attachmentMenu = false; files.launch(arrayOf("*/*")) })
                    }
                }
                BasicTextField(text, { if (it.length <= 4000) { tooLong = false; onChange(it) } else tooLong = true },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp)
                        .semantics { contentDescription = "消息输入框" }, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text("说点什么…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    })
                FilledIconButton(onClick = onSend, enabled = (text.isNotBlank() || attachments.isNotEmpty()) && enabled && attachments.all { it.uploaded != null }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送消息", modifier = Modifier.size(21.dp))
                }
            }
        }
        if (tooLong || text.length >= 3600) Text(if (tooLong) "最多 4000 字，请分段粘贴或发送。" else "${text.length} / 4000",
            Modifier.align(Alignment.End).padding(top = 4.dp, end = 12.dp), style = MaterialTheme.typography.labelSmall,
            color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun MessageBubble(text: String, own: Boolean, caption: String, attachments: List<Attachment>, vm: BotViewModel) {
    val clipboard = LocalClipboardManager.current
    var actions by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(Modifier.align(if (own) Alignment.TopEnd else Alignment.TopStart).widthIn(max = maxWidth * .90f),
            horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            attachments.forEach { attachment ->
                AttachmentCard(attachment) { vm.previewAttachment(attachment) }
                Spacer(Modifier.height(6.dp))
            }
            if (text.isNotBlank()) Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = if (own) 6.dp else 22.dp,
                bottomStart = if (own) 22.dp else 6.dp),
                color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                SelectionContainer { Text(text, Modifier.padding(horizontal = 16.dp, vertical = 13.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(caption, Modifier.weight(1f, fill = false).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    IconButton(onClick = { actions = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreVert, "消息操作", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(actions, { actions = false }) {
                        DropdownMenuItem(text = { Text("复制全文") }, onClick = { clipboard.setText(AnnotatedString(text.ifBlank { attachments.joinToString("\n") { it.name } })); actions = false })
                    }
                }
            }
        }
    }
}

private fun messageTime(at: Long) = if (at <= 0) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))

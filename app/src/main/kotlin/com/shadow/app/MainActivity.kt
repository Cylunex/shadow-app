package com.shadow.app

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.text.style.TextOverflow
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shadow.bot.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[BotViewModel::class.java] }
    private fun receiveShare(incoming: Intent?) {
        if (incoming == null) return
        try {
            val key = if (incoming.action == Intent.ACTION_PROCESS_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT
            SharedText.read(incoming.action, incoming.type, incoming.getCharSequenceExtra(key))?.let(model::receiveShare)
            incoming.removeExtra(key)
        } catch (_: Exception) { model.shareError("无法接收这段文字，请分享不超过 4000 字的纯文本。") }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveShare(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF426859), onPrimary = Color.White,
                primaryContainer = Color(0xFFDCE9DF), background = Color(0xFFF8F8F4), surface = Color(0xFFF8F8F4),
                surfaceVariant = Color(0xFFEEEEE8))) { BotApp(model) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BotApp(vm: BotViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) vm.foreground(false)
        }
        owner.lifecycle.addObserver(observer)
        vm.foreground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { owner.lifecycle.removeObserver(observer); vm.foreground(false) }
    }
    val role = state.bot.roles.find { it.id == state.bot.selected }
    val back = {
        vm.navigate(when (state.destination) {
            Destination.WorkDetail -> Destination.Progress
            Destination.ArtifactPreview -> Destination.WorkDetail
            Destination.Progress, Destination.Persona, Destination.Memories -> Destination.Chat
            else -> Destination.Conversations
        })
    }
    BackHandler(state.destination != Destination.Conversations && state.bot.connected) { back() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(when (state.destination) {
            Destination.Conversations -> "Shadow"
            Destination.Chat -> role?.name ?: "聊天"
            Destination.Settings -> "连接设置"
            Destination.CreateRole -> "新的伙伴"
            Destination.Progress -> "事项进度"
            Destination.WorkDetail -> "事项详情"
            Destination.ArtifactPreview -> "成果预览"
            Destination.Memories -> "共享记忆"
            Destination.Persona -> "角色与人设"
        }, fontWeight = FontWeight.SemiBold) }, navigationIcon = {
            if (state.destination != Destination.Conversations && state.bot.connected) TextButton(onClick = { back() }) { Text("返回") }
        }, actions = {
            if (state.destination == Destination.Conversations) TextButton(onClick = { vm.navigate(Destination.Settings) }) { Text("设置") }
            else if (state.destination == Destination.Chat && "personas" in state.bot.capabilities) TextButton(onClick = { vm.navigate(Destination.Persona) }) { Text("人设") }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding()) {
            val error = state.notice ?: state.bot.error
            if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (state.bot.needsLogin) vm.navigate(Destination.Settings) else vm.refresh() }) { Text(if (state.bot.needsLogin) "重连" else "刷新") }
                }
            }
            when (state.destination) {
                Destination.Conversations -> Contacts(state, vm)
                Destination.Chat -> Chat(state, vm)
                Destination.Settings -> Settings(state, vm)
                Destination.CreateRole -> CreateRole(state, vm)
                Destination.Progress -> Progress(state.bot, vm)
                Destination.WorkDetail -> WorkDetailScreen(state, vm)
                Destination.ArtifactPreview -> ArtifactScreen(state)
                Destination.Memories -> Memories(state.memories)
                Destination.Persona -> PersonaEditor(state, vm)
            }
        }
    }
}

@Composable private fun Contacts(state: ScreenState, vm: BotViewModel) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        if (state.sharedText != null) Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("选择伙伴，将分享内容放入输入框。", Modifier.weight(1f))
                TextButton(onClick = vm::dismissShare) { Text("取消") }
            }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜索伙伴或最近消息") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Text("聊一聊，也一起把事情做好。", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.bot.roles.isEmpty()) Empty(if (state.bot.busy) "正在连接你的伙伴…" else "连接 Losna，开始一段对话。")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.bot.roles.filter { query.isBlank() || it.name.contains(query, true) || it.preview.contains(query, true) }, key = { it.id }) { role ->
                Surface(shape = RoundedCornerShape(22.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable { vm.open(role) }) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(50.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text(role.name.take(1), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(role.name, style = MaterialTheme.typography.titleMedium)
                            val pending = state.bot.pending.count { it.entryId == role.id }
                            Text(if (pending > 0) "$pending 条操作待确认" else role.preview.ifBlank { "继续对话" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (role.activeWorks > 0) Text("${role.activeWorks} 项正在处理或等待继续", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(time(role.lastMessageAt), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if ("createRole" in state.bot.capabilities) Button(onClick = { vm.navigate(Destination.CreateRole) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("添加伙伴") }
        TextButton(onClick = vm::refresh, enabled = !state.bot.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("刷新会话") }
    }
}

@Composable private fun Chat(state: ScreenState, vm: BotViewModel) {
    val bot = state.bot
    val text = state.drafts[bot.selected].orEmpty()
    val pending = bot.pending.filter { it.entryId == bot.selected }
    val scroll = rememberLazyListState()
    LaunchedEffect(bot.selected, bot.messages.lastOrNull()?.id, pending.size) {
        val count = bot.messages.size + pending.size
        if (count > 0) scroll.animateScrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.navigate(Destination.Progress) }) { Text("事项 ${bot.works.count { it.state !in listOf("completed", "cancelled", "failed") }}") }
            if ("memories" in bot.capabilities) TextButton(onClick = { vm.navigate(Destination.Memories) }) { Text("记忆") }
        }
        if (bot.messages.isEmpty() && pending.isEmpty()) Text("从一句问候开始。", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(bot.messages, key = { "${it.role}:${it.id}" }) { message ->
                MessageBubble(message.text, message.role == "user", if (message.role == "user") time(message.at) else listOf(message.personaName, time(message.at)).filter(String::isNotBlank).joinToString(" · "))
            }
            items(pending, key = { "pending:${it.id}" }) { message ->
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    MessageBubble(message.text.ifBlank { message.label }, true, "${message.label}等待确认")
                    Row {
                        TextButton(onClick = { vm.retry(message.id) }, enabled = !bot.busy && !bot.needsLogin) { Text("重试") }
                        TextButton(onClick = { vm.removePending(message.id) }, enabled = !bot.busy) { Text("移除待发记录") }
                    }
                    Text("移除记录不会取消服务端已接收的消息。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(text, { if (it.length <= 4000) vm.draft(it) }, placeholder = { Text("说点什么…") }, modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.send(text) }, enabled = text.isNotBlank() && !bot.busy && !bot.needsLogin && bot.selected != null, contentPadding = PaddingValues(16.dp)) { Text("发送") }
        }
    }
}
@Composable private fun MessageBubble(text: String, own: Boolean, caption: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
        Surface(shape = RoundedCornerShape(20.dp), color = if (own) MaterialTheme.colorScheme.primaryContainer else Color.White, modifier = Modifier.widthIn(max = 330.dp)) {
            SelectionContainer { Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
        }
        Text(caption, Modifier.padding(horizontal = 6.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun CreateRole(state: ScreenState, vm: BotViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    val id = rememberSaveable { "bot-" + UUID.randomUUID().toString() }
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("从一个名字开始", style = MaterialTheme.typography.headlineSmall)
        Text("创建后可以自由设置性格与表达方式。伙伴共享你的有效记忆，聊天各自延续。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("名字") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.createRole(id, name) }, enabled = name.isNotBlank() && !state.bot.busy, modifier = Modifier.fillMaxWidth()) { Text("开始聊天") }
    }
}
@Composable private fun Settings(state: ScreenState, vm: BotViewModel) {
    var address by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }
    // A launch token must never enter savedInstanceState or the persistent store.
    var token by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("连接你的 Losna", style = MaterialTheme.typography.headlineSmall) }
        item { Text("填写 DSH 根地址，包含已有的代理前缀。启动口令仅在本次登录使用。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(address, { address = it }, label = { Text("DSH 地址") }, placeholder = { Text("https://example.com/dsh/") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(token, { token = it }, label = { Text("当前启动口令") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Button(onClick = { vm.connect(address, token); token = "" }, enabled = !state.connecting && !state.storageError && !state.bot.busy && address.isNotBlank() && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (state.connecting) "正在连接…" else "连接") } }
        item { Text("对话、工作和记忆由现有服务端保存。手机关闭后，伙伴仍可继续处理已接收的事项。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.baseUrl.isNotBlank()) item { TextButton(onClick = vm::disconnect, enabled = !state.connecting && !state.bot.busy) { Text("断开本机连接") } }
        if (state.bot.pending.isNotEmpty()) item { Text("待确认消息", style = MaterialTheme.typography.titleMedium) }
        items(state.bot.pending, key = { it.id }) { pending ->
            Column {
                Text("${pending.label}：${pending.text.ifBlank { "等待服务端确认" }}", maxLines = 3)
                Row {
                    TextButton(onClick = { vm.retry(pending.id) }, enabled = !state.bot.busy && !state.connecting && !state.bot.needsLogin) { Text("重试") }
                    TextButton(onClick = { vm.removePending(pending.id) }, enabled = !state.bot.busy && !state.connecting) { Text("移除待发记录") }
                }
            }
        }
        if (state.bot.pending.isNotEmpty()) item { Text("移除本机记录不会撤回服务端已接收的消息。", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun Progress(bot: BotState, vm: BotViewModel) {
    val works = bot.works
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (works.isEmpty()) item { Empty("还没有事项，直接在聊天里交代就好。") }
        items(works, key = { it.id }) { work -> Surface(shape = RoundedCornerShape(18.dp), color = Color.White,
            modifier = Modifier.clickable(enabled = "workDetails" in bot.capabilities) { vm.openWork(work.id) }) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(work.goal, style = MaterialTheme.typography.titleMedium)
                Text(workState(work.state), color = MaterialTheme.colorScheme.primary)
                if (work.result.isNotBlank()) Text(work.result)
                if (work.nextStep.isNotBlank()) Text(work.nextStep, style = MaterialTheme.typography.bodySmall)
            }
        } }
        item { Text(if ("workDetails" in bot.capabilities) "点开事项查看计划与成果，也可以直接在聊天里继续。" else "需要补充、继续或取消时，在聊天里告诉伙伴。", style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun Memories(memories: List<MemoryItem>) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("这是你与伙伴共享的有效记忆。更正、少提或遗忘，直接在聊天中说明。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (memories.isEmpty()) item { Empty("暂时没有可显示的记忆。") }
        items(memories, key = { it.id }) { memory -> Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            SelectionContainer { Text(memory.text, Modifier.fillMaxWidth().padding(18.dp)) }
        } }
        item { Text("显示最近 100 条有效记忆。", style = MaterialTheme.typography.labelSmall) }
    }
}
@Composable private fun PersonaEditor(state: ScreenState, vm: BotViewModel) {
    val original = state.persona
    if (original == null) { Empty("正在读取人设…"); return }
    var draft by remember(original) { mutableStateOf(original) }
    val card = draft.cards.first { it.id == draft.active }
    fun update(change: (PersonaCard) -> PersonaCard) { draft = draft.copy(cards = draft.cards.map { if (it.id == card.id) change(it) else it }) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("人设可以切换，当前会话与已有关系继续保留。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(draft.cards, key = { it.id }) { choice ->
            Row(Modifier.fillMaxWidth().clickable { draft = draft.copy(active = choice.id) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = draft.active == choice.id, onClick = { draft = draft.copy(active = choice.id) }); Text(choice.name)
            }
        }
        item { TextButton(onClick = {
            val id = "custom-" + UUID.randomUUID().toString().take(8)
            val new = PersonaCard(id, "自定义 ${draft.cards.size}", card.displayName, "", false)
            draft = draft.copy(active = id, cards = draft.cards + new)
        }, enabled = draft.cards.size < 20) { Text("添加自定义人设") } }
        item { OutlinedTextField(card.name, { value -> update { it.copy(name = value.take(60)) } }, label = { Text("人设卡名称") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(card.displayName, { value -> update { it.copy(displayName = value.take(100)) } }, label = { Text("显示名字") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(card.instructions, { value -> update { it.copy(instructions = value.take(12000)) } }, label = { Text("性格、表达与轻量角色设定") }, minLines = 6, modifier = Modifier.fillMaxWidth()) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(card.inheritIdentity, { value -> update { it.copy(inheritIdentity = value) } }); Text("沿用原身份设定", Modifier.padding(start = 12.dp)) } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(draft.contactEnabled, { draft = draft.copy(contactEnabled = it) }); Text("允许主动聊天", Modifier.padding(start = 12.dp)) } }
        item { Button(onClick = { vm.savePersona(draft) }, enabled = !state.bot.busy && card.name.isNotBlank() && card.displayName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存人设") } }
    }
}
@Composable private fun Empty(text: String) { Text(text, Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
internal fun time(at: Long) = if (at <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
internal fun workState(value: String) = when(value) {
    "queued" -> "等待处理"; "running" -> "正在处理"; "waiting" -> "等待继续"; "completed" -> "已完成"; "cancelled" -> "已取消"; "failed" -> "暂未完成"; "excluded" -> "已停止使用"; "interrupted" -> "执行中断"; "superseded" -> "已被新的要求接替"; else -> "状态待更新"
}

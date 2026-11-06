package com.shadow.app

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.text.style.TextOverflow
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        incoming.getStringExtra("bot-entry")?.let { model.openNotification(it); incoming.removeExtra("bot-entry") }
        try {
            if (incoming.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT)) model.dismissShare()
            @Suppress("DEPRECATION")
            val files: List<Uri> = when (incoming.action) {
                Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE -> incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else -> emptyList()
            }
            val key = if (incoming.action == Intent.ACTION_PROCESS_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT
            SharedText.read(if (files.isNotEmpty()) Intent.ACTION_SEND else incoming.action, if (files.isNotEmpty()) "text/plain" else incoming.type, incoming.getCharSequenceExtra(key))?.let(model::receiveShare)
            if (files.isNotEmpty()) model.receiveFiles(files)
            incoming.removeExtra(key)
            incoming.removeExtra(Intent.EXTRA_STREAM)
        } catch (_: Exception) { model.shareError("无法接收这段文字，请分享不超过 4000 字的纯文本。") }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveShare(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveShare(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { BotTheme { BotApp(model) } }
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
    BotScreen(state, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BotScreen(state: ScreenState, vm: BotViewModel) {
    val role = state.bot.roles.find { it.id == state.bot.selected }
    val back = {
        vm.navigate(when (state.destination) {
            Destination.WorkDetail -> Destination.Progress
            Destination.ArtifactPreview -> Destination.WorkDetail
            Destination.Search, Destination.Progress, Destination.Persona, Destination.Memories, Destination.AttachmentPreview -> Destination.Chat
            else -> Destination.Conversations
        })
    }
    BackHandler(state.destination != Destination.Conversations && state.bot.connected) { back() }
    var menuOpen by remember(state.destination) { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = {
            if (state.destination == Destination.Chat) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotAvatar(role?.name ?: "S", role?.id.orEmpty(), 34.dp)
                Column {
                    Text(role?.name ?: "聊天", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (state.bot.syncIssue != null) "连接恢复后会自动更新" else "随时接着聊", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else Text(when (state.destination) {
            Destination.Conversations -> "Shadow"
            Destination.Chat -> role?.name ?: "聊天"
            Destination.Search -> "搜索聊天记录"
            Destination.Settings -> "连接设置"
            Destination.CreateRole -> "新的伙伴"
            Destination.Progress -> "事项与成果"
            Destination.WorkDetail -> "事项详情"
            Destination.ArtifactPreview -> "成果预览"
            Destination.AttachmentPreview -> "附件"
            Destination.Memories -> "共享记忆"
            Destination.Persona -> "角色与人设"
        }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            if (state.destination != Destination.Conversations && state.bot.connected) IconButton(onClick = { back() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
        }, actions = {
            if (state.destination == Destination.Conversations) {
                IconButton(onClick = { vm.navigate(Destination.Settings) }) { Icon(Icons.Outlined.Settings, "连接设置") }
                if ("createRole" in state.bot.capabilities) IconButton(onClick = { vm.navigate(Destination.CreateRole) }) { Icon(Icons.Outlined.Add, "添加伙伴") }
            } else if (state.destination == Destination.Chat) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "聊天选项") }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        if ("messageHistory" in state.bot.capabilities) DropdownMenuItem(text = { Text("搜索聊天记录") }, onClick = { menuOpen = false; vm.navigate(Destination.Search) })
                        DropdownMenuItem(text = { Text("事项与成果") }, onClick = { menuOpen = false; vm.navigate(Destination.Progress) })
                        if ("contactArchive" in state.bot.capabilities) DropdownMenuItem(text = { Text(if (role?.archived == true) "移回聊天列表" else "归档聊天") }, onClick = { menuOpen = false; vm.archiveContact() })
                        if ("personas" in state.bot.capabilities) DropdownMenuItem(text = { Text("角色与人设") }, onClick = { menuOpen = false; vm.navigate(Destination.Persona) })
                        if ("memories" in state.bot.capabilities) DropdownMenuItem(text = { Text("共享记忆") }, onClick = { menuOpen = false; vm.navigate(Destination.Memories) })
                    }
                }
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            val error = state.notice ?: state.bot.error
            if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (state.bot.needsLogin) vm.navigate(Destination.Settings) else vm.refresh() }) { Text(if (state.bot.needsLogin) "重连" else "刷新") }
                }
            }
            when (state.destination) {
                Destination.Conversations -> Contacts(state, vm)
                Destination.Chat -> key(state.bot.selected) { Chat(state, vm) }
                Destination.Settings -> Settings(state, vm)
                Destination.CreateRole -> CreateRole(state, vm)
                Destination.Progress -> Progress(state.bot, vm)
                Destination.WorkDetail -> WorkDetailScreen(state, vm)
                Destination.ArtifactPreview -> ArtifactScreen(state)
                Destination.Search -> MessageSearchScreen(state, vm)
                Destination.AttachmentPreview -> AttachmentPreviewScreen(state, vm)
                Destination.Memories -> MemoryScreen(state, vm)
                Destination.Persona -> PersonaEditor(state, vm)
            }
        }
    }
}

@Composable private fun CreateRole(state: ScreenState, vm: BotViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    val id = rememberSaveable { "bot-" + UUID.randomUUID().toString() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("从一个名字开始", style = MaterialTheme.typography.headlineSmall)
        Text("创建后可以自由设置性格与表达方式。伙伴共享你的有效记忆，聊天各自延续。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("名字") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.createRole(id, name) }, enabled = name.isNotBlank() && !state.bot.busy, modifier = Modifier.fillMaxWidth()) { Text("开始聊天") }
    }
}
@Composable private fun Settings(state: ScreenState, vm: BotViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var notifications by remember { mutableStateOf(BackgroundChecks.enabled(context)) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        notifications = granted; BackgroundChecks.configure(context, granted)
    }
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
        if (state.baseUrl.isNotBlank()) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("后台提醒")
                    Text("定期检查新回复和事项进展，可能延迟；强制停止后需再次打开。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(notifications, { enabled ->
                    if (enabled && android.os.Build.VERSION.SDK_INT >= 33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else { notifications = enabled; BackgroundChecks.configure(context, enabled) }
                })
            }
        }
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

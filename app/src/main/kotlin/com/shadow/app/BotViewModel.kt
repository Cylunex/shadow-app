package com.shadow.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shadow.bot.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Destination {
    data object Conversations : Destination
    data object Settings : Destination
    data object CreateRole : Destination
    data object Chat : Destination
    data object WorkDetail : Destination
    data object ArtifactPreview : Destination
    data object Progress : Destination
    data object Memories : Destination
    data object Persona : Destination
}
data class ScreenState(val bot: BotState = BotState(), val destination: Destination = Destination.Conversations,
    val baseUrl: String = "", val connecting: Boolean = false, val notice: String? = null,
    val drafts: Map<String, String> = emptyMap(), val workDrafts: Map<String, String> = emptyMap(), val sharedText: String? = null, val viewedWorkId: String? = null, val viewedArtifactId: String? = null,
    val persona: PersonaSettings? = null, val memories: List<MemoryItem> = emptyList(), val storageError: Boolean = false)

class BotViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureStateStore(application)
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var repository: BotRepository? = null
    private var collector: Job? = null
    private var polling: Job? = null
    private var foreground = false
    private var detailLoad: Job? = null
    private var mutation: Job? = null
    init {
        try {
            val session = store.read().session
            if (session != null) attach(HttpProductApi.from(session), session.baseUrl)
            else mutable.value = mutable.value.copy(destination = Destination.Settings)
        } catch (_: Exception) { mutable.value = mutable.value.copy(storageError = true, notice = "本地加密资料无法读取；原文件已保留，请恢复资料后重试。") }
    }
    private fun attach(api: HttpProductApi, baseUrl: String) {
        polling?.cancel(); collector?.cancel()
        val repo = BotRepository(api, store); repository = repo
        mutable.value = mutable.value.copy(baseUrl = baseUrl, destination = Destination.Conversations, persona = null, memories = emptyList(), drafts = emptyMap(), workDrafts = emptyMap())
        collector = viewModelScope.launch { repo.state.collect { mutable.value = mutable.value.copy(bot = it) } }
        viewModelScope.launch { repo.refresh() }
        startPolling()
    }
    fun connect(address: String, token: String) {
        if (mutable.value.connecting || mutable.value.storageError || mutable.value.bot.busy || mutation?.isActive == true) return
        mutable.value = mutable.value.copy(connecting = true, notice = null)
        polling?.cancel()
        viewModelScope.launch {
            try {
                val base = HttpProductApi.normalizeBase(address)
                val previous = store.read()
                require(previous.pending.isEmpty() || previous.session?.baseUrl == base) { "请先处理原连接的待发消息，再更换服务地址。" }
                val api = HttpProductApi.from(Session(base, emptyList()))
                api.login(token); api.handshake()
                val current = store.read()
                require(current.pending.isEmpty() || current.session?.baseUrl == base) { "请先处理原连接的待发记录。" }
                store.write(current.copy(session = api.session()))
                attach(api, base)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.value = mutable.value.copy(notice = when(error) {
                is ApiFailure, is IllegalArgumentException -> error.message
                else -> "连接未成功，请检查网络、DSH 地址和启动口令。"
            }) } finally { mutable.value = mutable.value.copy(connecting = false); startPolling() }
        }
    }
    fun disconnect() {
        if (mutable.value.connecting || mutable.value.bot.busy || mutable.value.storageError || mutation?.isActive == true) return
        try {
            if (store.read().pending.isNotEmpty()) {
                mutable.value = mutable.value.copy(notice = "请先处理待发记录，再断开本机连接。")
                return
            }
            store.write(SavedState())
            polling?.cancel(); detailLoad?.cancel(); collector?.cancel(); repository = null
            mutable.value = ScreenState(destination = Destination.Settings)
        } catch (_: Exception) { mutable.value = mutable.value.copy(notice = "未能清除本地会话，请稍后重试。") }
    }
    fun foreground(active: Boolean) {
        foreground = active
        if (active && !mutable.value.connecting) { viewModelScope.launch { repository?.refresh() }; startPolling() }
        else { polling?.cancel(); polling = null; detailLoad?.cancel(); mutable.value = mutable.value.copy(memories = emptyList()) }
    }
    private fun startPolling() {
        polling?.cancel()
        if (!foreground || mutable.value.connecting) return
        polling = viewModelScope.launch {
            while (isActive) {
                delay(4000)
                if (!mutable.value.bot.needsLogin) {
                    repository?.poll()
                    if (mutable.value.destination == Destination.Memories) loadMemories()
                }
            }
        }
    }
    fun refresh() { if (mutable.value.connecting) return; viewModelScope.launch { repository?.refresh() } }
    fun open(role: BotRole) {
        if (mutable.value.connecting) return
        val shared = mutable.value.sharedText
        if (shared != null) {
            try {
                val draft = SharedText.append(mutable.value.drafts[role.id].orEmpty(), shared)
                mutable.value = mutable.value.copy(drafts = mutable.value.drafts + (role.id to draft), sharedText = null)
            } catch (error: IllegalArgumentException) { mutable.value = mutable.value.copy(notice = error.message); return }
        }
        navigate(Destination.Chat)
        viewModelScope.launch { repository?.select(role.id) }
    }
    fun receiveShare(text: String) {
        mutable.value = mutable.value.copy(sharedText = text, notice = null)
        if (repository != null && !mutable.value.connecting) navigate(Destination.Conversations)
    }
    fun shareError(message: String) { mutable.value = mutable.value.copy(notice = message) }
    fun dismissShare() { mutable.value = mutable.value.copy(sharedText = null) }
    fun openWork(id: String) {
        if (mutable.value.connecting) return
        mutable.value = mutable.value.copy(viewedWorkId = id, viewedArtifactId = null)
        navigate(Destination.WorkDetail)
        viewModelScope.launch { repository?.openWork(id) }
    }
    fun previewArtifact(id: String) {
        if (mutable.value.connecting) return
        mutable.value = mutable.value.copy(viewedArtifactId = id)
        navigate(Destination.ArtifactPreview)
        viewModelScope.launch { repository?.previewArtifact(id) }
    }
    fun workDraft(value: String) {
        val id = mutable.value.bot.workDetail?.work?.id ?: return
        mutable.value = mutable.value.copy(workDrafts = mutable.value.workDrafts + (id to value))
    }
    fun submitWork(action: String) {
        if (mutable.value.connecting || mutation?.isActive == true) return
        val id = mutable.value.bot.workDetail?.work?.id ?: return
        val text = mutable.value.workDrafts[id].orEmpty()
        mutation = viewModelScope.launch {
            if (repository?.submitWork(action, text) == true && action == "work.resume" && mutable.value.workDrafts[id] == text)
                mutable.value = mutable.value.copy(workDrafts = mutable.value.workDrafts - id)
        }
    }
    fun navigate(destination: Destination) {
        if (mutable.value.connecting) return
        detailLoad?.cancel()
        if (destination == Destination.WorkDetail) viewModelScope.launch { repository?.closeArtifact() }
        if (destination !in listOf(Destination.WorkDetail, Destination.ArtifactPreview)) viewModelScope.launch { repository?.closeWork() }
        mutable.value = mutable.value.copy(destination = destination, notice = null, persona = null, memories = emptyList())
        if (destination == Destination.Conversations) viewModelScope.launch { repository?.select(null) }
        if (destination == Destination.Memories) loadMemories()
        if (destination == Destination.Persona) mutable.value.bot.selected?.let { id -> load { mutable.value = mutable.value.copy(persona = repository?.persona(id)) } }
    }
    private fun loadMemories() = load {
        val rows = repository?.memories().orEmpty()
        if (mutable.value.destination == Destination.Memories && foreground) mutable.value = mutable.value.copy(memories = rows)
    }
    private fun load(block: suspend () -> Unit) {
        detailLoad?.cancel()
        detailLoad = viewModelScope.launch {
            val destination = mutable.value.destination
            try { block() } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (mutable.value.destination == destination) mutable.value = mutable.value.copy(notice = "暂时无法读取，请返回后重试。")
            }
        }
    }
    fun draft(value: String) {
        val id = mutable.value.bot.selected ?: return
        mutable.value = mutable.value.copy(drafts = mutable.value.drafts + (id to value))
    }
    fun send(text: String) {
        if (mutable.value.connecting || mutation?.isActive == true) return
        val id = mutable.value.bot.selected ?: return
        mutation = viewModelScope.launch {
            if (repository?.send(text) == true && mutable.value.drafts[id] == text)
                mutable.value = mutable.value.copy(drafts = mutable.value.drafts - id)
        }
    }
    fun createRole(id: String, name: String) { if (mutable.value.connecting || mutation?.isActive == true) return; mutation = viewModelScope.launch {
        repository?.createRole(id, name)
        if (repository?.state?.value?.error == null) navigate(Destination.Chat)
    } }
    fun retry(id: String) { if (mutable.value.connecting || mutation?.isActive == true) return; mutation = viewModelScope.launch { repository?.retry(id) } }
    fun removePending(id: String) { if (mutable.value.connecting || mutation?.isActive == true) return; mutation = viewModelScope.launch { repository?.removePending(id) } }
    fun savePersona(settings: PersonaSettings) { if (mutable.value.connecting || mutation?.isActive == true) return; mutation = viewModelScope.launch {
        repository?.savePersona(settings)
        if (repository?.state?.value?.error == null) navigate(Destination.Chat)
    } }
}

package com.shadow.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shadow.bot.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

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
    data object AttachmentPreview : Destination
}
data class AttachmentDraft(val id: String, val uri: String, val name: String = "正在读取附件…", val status: String = "queued", val error: String? = null, val uploaded: Attachment? = null)
data class AttachmentPreviewData(val attachment: Attachment, val bytes: ByteArray)
data class ScreenState(val bot: BotState = BotState(), val destination: Destination = Destination.Conversations,
    val baseUrl: String = "", val connecting: Boolean = false, val notice: String? = null,
    val drafts: Map<String, String> = emptyMap(), val workDrafts: Map<String, String> = emptyMap(), val sharedText: String? = null, val viewedWorkId: String? = null, val viewedArtifactId: String? = null,
    val persona: PersonaSettings? = null, val memories: List<MemoryItem> = emptyList(), val storageError: Boolean = false,
    val attachmentDrafts: Map<String, List<AttachmentDraft>> = emptyMap(), val sharedUris: List<String> = emptyList(),
    val attachmentPreview: AttachmentPreviewData? = null, val attachmentLoading: Boolean = false)

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
    private val uploads = mutableMapOf<String, Job>()
    private val uploadLimit = Semaphore(1)
    init {
        try {
            val session = store.read().session
            if (session != null) attach(HttpProductApi.from(session), session.baseUrl)
            else mutable.value = mutable.value.copy(destination = Destination.Settings)
        } catch (_: Exception) { mutable.value = mutable.value.copy(storageError = true, notice = "本地加密资料无法读取；原文件已保留，请恢复资料后重试。") }
    }
    private fun attach(api: HttpProductApi, baseUrl: String) {
        val sameServer = mutable.value.baseUrl == baseUrl
        uploads.values.forEach { it.cancel() }; uploads.clear()
        polling?.cancel(); collector?.cancel()
        val repo = BotRepository(api, store); repository = repo
        mutable.value = mutable.value.copy(baseUrl = baseUrl, destination = Destination.Conversations, persona = null, memories = emptyList(),
            drafts = if (sameServer) mutable.value.drafts else emptyMap(), workDrafts = if (sameServer) mutable.value.workDrafts else emptyMap(),
            attachmentDrafts = if (sameServer) mutable.value.attachmentDrafts.mapValues { (_, drafts) -> drafts.map { if (it.uploaded != null) it else it.copy(status = "failed", error = "连接已恢复，请重试上传。") } } else emptyMap(),
            attachmentPreview = null)
        collector = viewModelScope.launch { repo.state.collect { bot ->
            val current = mutable.value.copy(bot = bot)
            mutable.value = current.copy(attachmentPreview = current.attachmentPreview?.takeIf { visibleAttachment(current, it.attachment.id, bot.selected) })
        } }
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
            uploads.values.forEach { it.cancel() }; uploads.clear()
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
        val sharedUris = mutable.value.sharedUris
        if (sharedUris.isNotEmpty() && ("attachments" !in mutable.value.bot.capabilities || mutable.value.attachmentDrafts[role.id].orEmpty().size + sharedUris.size > 8)) {
            mutable.value = mutable.value.copy(notice = "当前服务未启用附件，或此对话草稿已超过 8 个附件。")
            return
        }
        if (shared != null) {
            try {
                val draft = SharedText.append(mutable.value.drafts[role.id].orEmpty(), shared)
                mutable.value = mutable.value.copy(drafts = mutable.value.drafts + (role.id to draft), sharedText = null)
            } catch (error: IllegalArgumentException) { mutable.value = mutable.value.copy(notice = error.message); return }
        }
        navigate(Destination.Chat)
        if (sharedUris.isNotEmpty()) { mutable.value = mutable.value.copy(sharedUris = emptyList()); addAttachments(sharedUris.map(Uri::parse), role.id) }
        viewModelScope.launch { repository?.select(role.id) }
    }
    fun receiveShare(text: String) {
        mutable.value = mutable.value.copy(sharedText = text, notice = null)
        if (repository != null && !mutable.value.connecting) navigate(Destination.Conversations)
    }
    fun receiveFiles(uris: List<Uri>) {
        if (uris.size > 8 || uris.any { it.scheme != "content" }) { shareError("一次最多分享 8 个可读取的附件。"); return }
        mutable.value = mutable.value.copy(sharedUris = uris.distinct().map(Uri::toString), notice = null)
        if (repository != null && !mutable.value.connecting) navigate(Destination.Conversations)
    }
    fun shareError(message: String) { mutable.value = mutable.value.copy(notice = message) }
    fun dismissShare() { mutable.value = mutable.value.copy(sharedText = null, sharedUris = emptyList()) }
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
        mutable.value = mutable.value.copy(destination = destination, notice = null, persona = null, memories = emptyList(), attachmentPreview = null, attachmentLoading = false)
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
        val drafts = mutable.value.attachmentDrafts[id].orEmpty()
        if (drafts.any { it.uploaded == null }) return
        val attachments = drafts.mapNotNull { it.uploaded }
        mutation = viewModelScope.launch {
            if (repository?.send(text, attachments) == true) {
                mutable.value = mutable.value.copy(
                    drafts = if (mutable.value.drafts[id] == text) mutable.value.drafts - id else mutable.value.drafts,
                    attachmentDrafts = mutable.value.attachmentDrafts + (id to mutable.value.attachmentDrafts[id].orEmpty().filter { current -> drafts.any { it.id == current.id }.not() }))
            }
        }
    }
    fun addAttachments(uris: List<Uri>, entryId: String? = mutable.value.bot.selected) {
        val entry = entryId ?: return
        if ("attachments" !in mutable.value.bot.capabilities) { shareError("当前服务尚未启用附件上传。"); return }
        val existing = mutable.value.attachmentDrafts[entry].orEmpty()
        if (existing.size + uris.size > 8 || uris.any { it.scheme != "content" }) { shareError("每条消息最多 8 个附件，请选择可读取的文件。"); return }
        val added = uris.map { AttachmentDraft(UUID.randomUUID().toString(), it.toString()) }
        mutable.value = mutable.value.copy(attachmentDrafts = mutable.value.attachmentDrafts + (entry to existing + added))
        added.forEach { uploadAttachment(entry, it) }
    }
    private fun updateAttachment(entry: String, id: String, change: (AttachmentDraft) -> AttachmentDraft) {
        mutable.value = mutable.value.copy(attachmentDrafts = mutable.value.attachmentDrafts + (entry to mutable.value.attachmentDrafts[entry].orEmpty().map { if (it.id == id) change(it) else it }))
    }
    private fun uploadAttachment(entry: String, draft: AttachmentDraft) {
        val repo = repository ?: return
        uploads[draft.id] = viewModelScope.launch {
            try {
                uploadLimit.withPermit {
                    updateAttachment(entry, draft.id) { it.copy(status = "uploading", error = null) }
                    val attachment = withContext(Dispatchers.IO) {
                        val resolver = getApplication<Application>().contentResolver
                        val uri = Uri.parse(draft.uri)
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (!cursor.moveToFirst()) null else {
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) require(cursor.getLong(sizeIndex) <= 20L * 1024 * 1024) { "单个附件不能超过 20 MiB。" }
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIndex >= 0) cursor.getString(nameIndex) else null
                            }
                        }?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.take(240)?.takeIf { it.isNotBlank() } ?: "附件"
                        val type = resolver.getType(uri)?.lowercase()?.takeIf { Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(it) } ?: "application/octet-stream"
                        withContext(Dispatchers.Main) { updateAttachment(entry, draft.id) { it.copy(name = name) } }
                        val bytes = resolver.openInputStream(uri)?.use { readAttachmentBytes(it) } ?: error("无法读取附件。")
                        repo.uploadAttachment(draft.id, entry, name, type, bytes)
                    }
                    ensureActive()
                    if (repository === repo) updateAttachment(entry, draft.id) { it.copy(status = "ready", uploaded = attachment) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (repository === repo) updateAttachment(entry, draft.id) { it.copy(status = "failed", error = if (error is ApiFailure || error is IllegalArgumentException) error.message else "上传失败，请重试或重新选择文件。") } }
            finally { if (uploads[draft.id] === coroutineContext[Job]) uploads.remove(draft.id) }
        }
    }
    fun retryAttachment(id: String) {
        val entry = mutable.value.bot.selected ?: return
        if (uploads[id]?.isActive == true) return
        mutable.value.attachmentDrafts[entry]?.find { it.id == id && it.uploaded == null }?.let { uploadAttachment(entry, it) }
    }
    fun removeAttachment(id: String) {
        val entry = mutable.value.bot.selected ?: return
        uploads.remove(id)?.cancel()
        mutable.value = mutable.value.copy(attachmentDrafts = mutable.value.attachmentDrafts + (entry to mutable.value.attachmentDrafts[entry].orEmpty().filter { it.id != id }))
    }
    fun previewAttachment(attachment: Attachment) {
        val entry = mutable.value.bot.selected ?: return
        val repo = repository ?: return
        navigate(Destination.AttachmentPreview)
        mutable.value = mutable.value.copy(attachmentLoading = true)
        load {
            try {
                val bytes = repo.attachmentContent(attachment, entry)
                if (repository === repo && mutable.value.destination == Destination.AttachmentPreview && visibleAttachment(mutable.value, attachment.id, entry))
                    mutable.value = mutable.value.copy(attachmentPreview = AttachmentPreviewData(attachment, bytes))
            } finally { mutable.value = mutable.value.copy(attachmentLoading = false) }
        }
    }
    fun saveAttachment(uri: Uri, preview: AttachmentPreviewData) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(preview.bytes) } ?: error("无法保存文件。") }
                android.widget.Toast.makeText(getApplication(), "附件已保存", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { mutable.value = mutable.value.copy(notice = "附件保存失败，请重新选择位置。") }
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

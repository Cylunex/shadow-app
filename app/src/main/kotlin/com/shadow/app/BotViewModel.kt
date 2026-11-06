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
    data object Search : Destination
    data object WorkDetail : Destination
    data object ArtifactPreview : Destination
    data object Progress : Destination
    data object Memories : Destination
    data object Persona : Destination
    data object AttachmentPreview : Destination
}
typealias AttachmentDraft = com.shadow.bot.AttachmentDraft
data class AttachmentPreviewData(val attachment: Attachment, val bytes: ByteArray)
data class ScreenState(val bot: BotState = BotState(), val destination: Destination = Destination.Conversations,
    val baseUrl: String = "", val connecting: Boolean = false, val notice: String? = null,
    val drafts: Map<String, String> = emptyMap(), val workDrafts: Map<String, String> = emptyMap(), val sharedText: String? = null, val viewedWorkId: String? = null, val viewedArtifactId: String? = null,
    val persona: PersonaSettings? = null, val personaDraft: PersonaSettings? = null, val memories: List<MemoryItem> = emptyList(), val storageError: Boolean = false, val memoryDetail: MemoryDetail? = null,
    val attachmentDrafts: Map<String, List<AttachmentDraft>> = emptyMap(), val sharedUris: List<String> = emptyList(),
    val attachmentPreview: AttachmentPreviewData? = null, val attachmentLoading: Boolean = false, val speaking: Boolean = false)

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
    private var personaEditorEpoch = 0L
    private var personaImportTarget: Triple<Long, String, String>? = null
    private var personaExportTarget: Pair<Long, PersonaCard>? = null
    private var speech: DeviceSpeech? = null
    private var spokenText: String? = null
    private var dictationEntry: String? = null
    private var dictationServer: String? = null
    fun beginDictation() { stopSpeaking(); dictationEntry = mutable.value.bot.selected; dictationServer = mutable.value.baseUrl }
    fun speak(text: String) {
        if (text.isBlank()) return
        if (speech == null) speech = DeviceSpeech(getApplication()) { active, error ->
            mutable.value = mutable.value.copy(speaking = active, notice = error ?: mutable.value.notice)
        }
        spokenText = text
        speech?.speak(text)
    }
    fun stopSpeaking() { spokenText = null; speech?.stop() }
    fun dictate(text: String) {
        val entry = dictationEntry; dictationEntry = null
        if (entry == null || mutable.value.bot.selected != entry || dictationServer != mutable.value.baseUrl) { shareError("聊天已切换，请在原聊天重新输入语音。"); return }
        try { draft(SharedText.append(mutable.value.drafts[entry].orEmpty(), text)) }
        catch (_: IllegalArgumentException) { shareError("识别内容过长，请分段输入。"); }
    }
    override fun onCleared() { speech?.close(); super.onCleared() }
    private val uploads = mutableMapOf<String, Job>()
    private val uploadLimit = Semaphore(1)
    init {
        try {
            val saved = store.read()
            val session = saved.session
            mutable.value = mutable.value.copy(baseUrl = session?.baseUrl.orEmpty(), drafts = saved.drafts, workDrafts = saved.workDrafts, attachmentDrafts = saved.attachmentDrafts)
            store.pruneDraftFiles()
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
            if (spokenText != null && bot.messages.none { it.text == spokenText }) stopSpeaking()
            val current = mutable.value.copy(bot = bot)
            mutable.value = current.copy(attachmentPreview = current.attachmentPreview?.takeIf { !bot.needsLogin && visibleAttachment(current, it.attachment.id, bot.selected) },
                memories = if (bot.needsLogin) emptyList() else current.memories, memoryDetail = if (bot.needsLogin) null else current.memoryDetail)
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
                if (current.session?.baseUrl != base) { store.clearCache(); java.io.File(getApplication<Application>().cacheDir, "shared-results").deleteRecursively() }
                store.write(if (current.session?.baseUrl == base) current.copy(session = api.session()) else SavedState(session = api.session()))
                store.pruneDraftFiles()
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
            BackgroundChecks.configure(getApplication(), false)
            store.writePrivate("notifications", "{}")
            store.writePrivate("read-marks", "{}")
            store.clearCache()
            java.io.File(getApplication<Application>().cacheDir, "shared-results").deleteRecursively()
            store.write(SavedState())
            store.pruneDraftFiles()
            uploads.values.forEach { it.cancel() }; uploads.clear()
            polling?.cancel(); detailLoad?.cancel(); collector?.cancel(); repository = null
            mutable.value = ScreenState(destination = Destination.Settings)
        } catch (_: Exception) { mutable.value = mutable.value.copy(notice = "未能清除本地会话，请稍后重试。") }
    }
    fun foreground(active: Boolean) {
        if (!active) { stopSpeaking(); repository?.clearLiveReply() }
        foreground = active
        BackgroundChecks.foreground = active
        if (active && !mutable.value.connecting) {
            viewModelScope.launch { repository?.refresh(silent = true) }
            if (mutable.value.destination == Destination.Memories) loadMemories()
            startPolling()
        }
        else { polling?.cancel(); polling = null; detailLoad?.cancel(); mutable.value = mutable.value.copy(memories = emptyList(), memoryDetail = null) }
    }
    private fun startPolling() {
        polling?.cancel()
        if (!foreground || mutable.value.connecting) return
        polling = viewModelScope.launch {
            while (isActive) {
                delay(if (mutable.value.bot.selected != null && "liveReplies" in mutable.value.bot.capabilities) 1000 else 4000)
                if (!mutable.value.bot.needsLogin) {
                    repository?.poll()
                    if (mutable.value.destination == Destination.Memories) loadMemories()
                }
            }
        }
    }
    fun loadHistory() { viewModelScope.launch { repository?.loadHistory() } }
    fun searchMessages(query: String, more: Boolean = false) { viewModelScope.launch { repository?.searchMessages(query.trim(), more) } }
    fun refresh() { if (mutable.value.connecting) return; viewModelScope.launch { repository?.refresh() } }
    fun openNotification(entry: String) {
        viewModelScope.launch {
            repository?.refresh(silent = true)
            mutable.value.bot.roles.find { it.id == entry }?.let(::open)
        }
    }
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
                if (!saveDrafts(mutable.value.copy(drafts = mutable.value.drafts + (role.id to draft), sharedText = null))) return
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
        saveDrafts(mutable.value.copy(workDrafts = mutable.value.workDrafts + (id to value)))
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
        stopSpeaking()
        if (mutable.value.connecting) return
        detailLoad?.cancel()
        personaEditorEpoch++
        if (destination == Destination.WorkDetail) viewModelScope.launch { repository?.closeArtifact() }
        if (destination !in listOf(Destination.WorkDetail, Destination.ArtifactPreview)) viewModelScope.launch { repository?.closeWork() }
        mutable.value = mutable.value.copy(destination = destination, notice = null, persona = null, personaDraft = null, memories = emptyList(), memoryDetail = null, attachmentPreview = null, attachmentLoading = false)
        if (destination == Destination.Conversations) viewModelScope.launch { repository?.select(null) }
        if (destination == Destination.Memories) loadMemories()
        if (destination == Destination.Persona) mutable.value.bot.selected?.let { id -> load { val value = repository?.persona(id); mutable.value = mutable.value.copy(persona = value, personaDraft = value) } }
    }
    fun inspectMemory(id: String) = load {
        mutable.value = mutable.value.copy(memoryDetail = null)
        val detail = repository?.memory(id)
        if (mutable.value.destination == Destination.Memories) mutable.value = mutable.value.copy(memoryDetail = detail)
    }
    fun closeMemory() { detailLoad?.cancel(); mutable.value = mutable.value.copy(memoryDetail = null) }
    fun editMemory(item: MemoryItem, action: String, text: String) {
        if (mutation?.isActive == true) return
        mutation = viewModelScope.launch {
            if (repository?.submitMemory(item, action, text) == true) {
                mutable.value = mutable.value.copy(memoryDetail = null)
                loadMemories()
            }
        }
    }
    fun archiveContact() {
        val role = mutable.value.bot.roles.find { it.id == mutable.value.bot.selected } ?: return
        if (mutation?.isActive == true) return
        mutation = viewModelScope.launch {
            repository?.archiveContact(role, !role.archived)
            if (repository?.state?.value?.error == null) navigate(Destination.Conversations)
        }
    }
    private fun loadMemories() = load {
        val rows = repository?.memories().orEmpty()
        if (mutable.value.destination == Destination.Memories && foreground) mutable.value = mutable.value.copy(memories = rows,
            memoryDetail = mutable.value.memoryDetail?.takeIf { detail -> rows.any { it.id == detail.item.id && it.version == detail.item.version } })
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
    private fun saveDrafts(next: ScreenState): Boolean {
        return try {
            store.write(store.read().copy(drafts = next.drafts.filterValues { it.isNotEmpty() },
                workDrafts = next.workDrafts.filterValues { it.isNotEmpty() }, attachmentDrafts = next.attachmentDrafts.filterValues { it.isNotEmpty() }))
            mutable.value = next
            true
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(notice = "草稿未能保存，请检查手机存储空间后重试。")
            false
        }
    }
    fun draft(value: String) {
        stopSpeaking()
        val id = mutable.value.bot.selected ?: return
        saveDrafts(mutable.value.copy(drafts = mutable.value.drafts + (id to value)))
    }
    fun send(text: String) {
        stopSpeaking()
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
                store.pruneDraftFiles()
            }
        }
    }
    fun addAttachments(uris: List<Uri>, entryId: String? = mutable.value.bot.selected) {
        val entry = entryId ?: return
        if ("attachments" !in mutable.value.bot.capabilities) { shareError("当前服务尚未启用附件上传。"); return }
        val existing = mutable.value.attachmentDrafts[entry].orEmpty()
        if (existing.size + uris.size > 8 || uris.any { it.scheme != "content" }) { shareError("每条消息最多 8 个附件，请选择可读取的文件。"); return }
        val added = uris.map { AttachmentDraft(UUID.randomUUID().toString(), it.toString()) }
        if (!saveDrafts(mutable.value.copy(attachmentDrafts = mutable.value.attachmentDrafts + (entry to existing + added)))) return
        added.forEach { uploadAttachment(entry, it) }
    }
    private fun updateAttachment(entry: String, id: String, change: (AttachmentDraft) -> AttachmentDraft) {
        check(saveDrafts(mutable.value.copy(attachmentDrafts = mutable.value.attachmentDrafts + (entry to mutable.value.attachmentDrafts[entry].orEmpty().map { if (it.id == id) change(it) else it })))) { "附件草稿未能保存。" }
    }
    private fun uploadAttachment(entry: String, draft: AttachmentDraft) {
        val repo = repository ?: return
        uploads[draft.id] = viewModelScope.launch {
            try {
                uploadLimit.withPermit {
                    updateAttachment(entry, draft.id) { it.copy(status = "uploading", error = null) }
                    val attachment = withContext(Dispatchers.IO) {
                        if (draft.localReady) return@withContext repo.uploadAttachment(draft.id, entry, draft.name, draft.mediaType, store.readDraftFile(draft.id))
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
                        store.saveDraftFile(draft.id, bytes)
                        withContext(Dispatchers.Main) { updateAttachment(entry, draft.id) { it.copy(name = name, mediaType = type, localReady = true) } }
                        repo.uploadAttachment(draft.id, entry, name, type, bytes)
                    }
                    ensureActive()
                    if (repository === repo) updateAttachment(entry, draft.id) { it.copy(status = "ready", uploaded = attachment) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (repository === repo) runCatching { updateAttachment(entry, draft.id) { it.copy(status = "failed", error = if (error is ApiFailure || error is IllegalArgumentException) error.message else "上传失败，请重试或重新选择文件。") } } }
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
        if (saveDrafts(mutable.value.copy(attachmentDrafts = mutable.value.attachmentDrafts + (entry to mutable.value.attachmentDrafts[entry].orEmpty().filter { it.id != id })))) store.removeDraftFile(id)
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
    fun exportArtifact(artifact: Artifact, uri: Uri? = null) {
        val entry = mutable.value.bot.selected ?: return
        val repo = repository ?: return
        val workId = mutable.value.viewedWorkId ?: return
        if (mutation?.isActive == true) return
        mutation = viewModelScope.launch {
            try {
                val bytes = repo.artifactContent(artifact, entry)
                check(repository === repo && mutable.value.bot.selected == entry && mutable.value.viewedWorkId == workId)
                val app = getApplication<Application>()
                if (uri != null) {
                    withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法保存文件。") }
                    android.widget.Toast.makeText(app, "成果已保存", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val file = withContext(Dispatchers.IO) {
                        val root = java.io.File(app.cacheDir, "shared-results").apply { mkdirs() }
                        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000 }?.forEach { it.deleteRecursively() }
                        val directory = java.io.File(root, UUID.randomUUID().toString()).apply { mkdirs() }
                        val name = artifact.name.replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(160).ifBlank { "成果" }
                        java.io.File(directory, name).apply { writeBytes(bytes) }
                    }
                    val content = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".results", file)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = artifact.type.ifBlank { "application/octet-stream" }
                        putExtra(android.content.Intent.EXTRA_STREAM, content)
                        clipData = android.content.ClipData.newRawUri("成果", content)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    app.startActivity(android.content.Intent.createChooser(send, "分享成果").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.value = mutable.value.copy(notice = if (error is ApiFailure) error.message else "成果导出失败，请刷新事项后重试。") }
        }
    }
    fun createRole(id: String, name: String) { if (mutable.value.connecting || mutation?.isActive == true) return; mutation = viewModelScope.launch {
        repository?.createRole(id, name)
        if (repository?.state?.value?.error == null) navigate(Destination.Chat)
    } }
    fun retry(id: String) { if (mutable.value.connecting || mutation?.isActive == true) return; mutation = viewModelScope.launch { repository?.retry(id) } }
    fun removePending(id: String) { if (mutable.value.connecting || mutation?.isActive == true) return; mutation = viewModelScope.launch { repository?.removePending(id) } }
    fun editPersona(settings: PersonaSettings) {
        if (settings.entryId == mutable.value.persona?.entryId && !mutable.value.bot.busy)
            mutable.value = mutable.value.copy(personaDraft = settings)
    }
    fun preparePersonaImport() {
        val current = mutable.value
        personaImportTarget = current.personaDraft?.let { Triple(personaEditorEpoch, it.entryId, current.baseUrl) }
    }
    fun importPersona(uri: Uri?) {
        val target = personaImportTarget; personaImportTarget = null
        if (uri == null || target == null || target.first != personaEditorEpoch) return
        val (epoch, entry, server) = target
        val repo = repository
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(PersonaFiles::read) ?: error("无法读取文件。")
                }
                val current = mutable.value
                if (epoch != personaEditorEpoch || repository !== repo || current.baseUrl != server || current.destination != Destination.Persona || current.personaDraft?.entryId != entry || current.bot.busy) return@launch
                mutable.value = current.copy(personaDraft = PersonaFiles.add(current.personaDraft, imported.card), notice = imported.note)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (epoch == personaEditorEpoch && repository === repo && mutable.value.destination == Destination.Persona) shareError(if (error is IllegalArgumentException) error.message ?: "人设格式无效。" else "无法读取人设文件，请重新选择。") }
        }
    }
    fun preparePersonaExport(card: PersonaCard) { personaExportTarget = personaEditorEpoch to card }
    fun exportPersona(uri: Uri?) {
        val target = personaExportTarget; personaExportTarget = null
        if (uri == null || target == null || target.first != personaEditorEpoch || mutable.value.destination != Destination.Persona) return
        val card = target.second
        viewModelScope.launch {
            try {
                val text = PersonaFiles.encode(card)
                withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("无法保存文件。") }
                android.widget.Toast.makeText(getApplication(), "人设已导出", android.widget.Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { shareError("人设导出失败，请检查资料并重新选择位置。") }
        }
    }
    fun savePersona(settings: PersonaSettings) { if (mutable.value.connecting || mutation?.isActive == true) return; mutation = viewModelScope.launch {
        val validation = PersonaFiles.validation(settings)
        if (validation != null) { shareError(validation); return@launch }
        repository?.savePersona(settings)
        if (repository?.state?.value?.error == null) navigate(Destination.Chat)
    } }
}

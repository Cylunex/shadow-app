package com.shadow.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class BotState(
    val connected: Boolean = false, val roles: List<BotRole> = emptyList(), val selected: String? = null,
    val messages: List<Message> = emptyList(), val works: List<Work> = emptyList(),
    val pending: List<PendingCommand> = emptyList(), val capabilities: Set<String> = emptySet(),
    val workDetail: WorkDetail? = null, val artifactText: ArtifactText? = null,
    val busy: Boolean = false, val needsLogin: Boolean = false, val error: String? = null,
    val syncIssue: String? = null,
)

/** Serializes user writes with snapshot/event refresh; cursors are never shared between contacts. */
class BotRepository(private val api: ProductApi, private val store: StateStore) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(BotState(pending = store.read().pending))
    val state = mutable.asStateFlow()
    private var generation: String? = null
    private var cursor = 0L
    private var openWorkId: String? = null
    private var openArtifactId: String? = null

    private suspend fun operation(silent: Boolean = false, block: suspend () -> Unit) = mutex.withLock {
        if (!silent) mutable.value = mutable.value.copy(busy = true, error = null)
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val message = when (error) {
                is ApiFailure -> error.message
                is IllegalArgumentException -> error.message ?: "输入无效。"
                else -> "连接中断，稍后可刷新；未确认的消息保留原编号重试。"
            }
            val expired = error is ApiFailure && error.status == 401
            mutable.value = if (silent && !expired) mutable.value.copy(syncIssue = "连接暂时中断，正在重试")
                else mutable.value.copy(error = message, needsLogin = expired)

        } finally { if (!silent) mutable.value = mutable.value.copy(busy = false) }
    }

    private suspend fun refreshLocked(refreshDetails: Boolean = true) {
        val hello = api.handshake()
        val previous = mutable.value
        val selected = previous.selected?.takeIf { id -> hello.roles.any { it.id == id } }
        // Handshake has identities only. Do not publish its empty summaries while inbox loads.
        var roles = hello.roles.map { role ->
            previous.roles.find { it.id == role.id }?.let { old ->
                role.copy(preview = old.preview, lastMessageAt = old.lastMessageAt, activeWorks = old.activeWorks)
            } ?: role
        }
        var messages = if (selected == null) emptyList() else previous.messages
        var works = if (selected == null) emptyList() else previous.works
        var nextCursor = cursor
        if (selected == null && "inbox" in hello.capabilities) {
            val inbox = api.inbox()
            if (inbox.generation != hello.generation) { generation = null; throw ApiFailure(409, "服务正在重启，请刷新。") }
            roles = hello.roles.map { role ->
                val summary = inbox.items.find { it.entryId == role.id }
                role.copy(preview = summary?.text.orEmpty(), lastMessageAt = summary?.at ?: 0, activeWorks = summary?.activeWorks ?: 0)
            }.sortedByDescending { it.lastMessageAt }
        }
        if (selected != null) {
            val snapshot = api.snapshot(selected)
            if (snapshot.generation != hello.generation) { generation = null; throw ApiFailure(409, "服务正在重启，请刷新后继续。") }
            check(snapshot.conversationId == hello.roles.first { it.id == selected }.conversationId)
            nextCursor = snapshot.cursor
            // Replace authoritative content, including withdrawals, in one publication.
            messages = snapshot.messages
            works = snapshot.works.filter { it.entryId == selected }
        }
        generation = hello.generation
        cursor = nextCursor
        mutable.value = mutable.value.copy(connected = true, needsLogin = false, roles = roles,
            capabilities = hello.capabilities, selected = selected, messages = messages, works = works, syncIssue = null)
        if (selected == null) clearWorkLocked()
        else if (refreshDetails) refreshWorkLocked()
    }
    suspend fun refresh(silent: Boolean = false) = operation(silent = silent && mutable.value.connected) { refreshLocked() }
    suspend fun select(entryId: String?) = operation {
        require(entryId == null || mutable.value.roles.any { it.id == entryId })
        mutable.value = mutable.value.copy(selected = entryId, messages = emptyList(), works = emptyList())
        cursor = 0
        clearWorkLocked()
        refreshLocked()
    }
    suspend fun poll() = operation(silent = true) {
        if (mutable.value.needsLogin) return@operation
        if (generation == null || mutable.value.selected == null || mutable.value.syncIssue != null) { refreshLocked(); return@operation }
        val events = api.events(cursor)
        if (events.generation != generation || events.resetRequired || events.changed || events.hasMore) refreshLocked()
        else { cursor = events.cursor; mutable.value = mutable.value.copy(syncIssue = null) }
    }
    private fun persist(pending: List<PendingCommand>) {
        store.write(store.read().copy(pending = pending)) // disk commit precedes network and UI acknowledgement
        mutable.value = mutable.value.copy(pending = pending)
    }
    suspend fun uploadAttachment(id: String, entryId: String, name: String, mediaType: String, bytes: ByteArray): Attachment {
        require("attachments" in mutable.value.capabilities && mutable.value.roles.any { it.id == entryId }) { "当前服务尚未启用附件上传。" }
        try { return api.uploadAttachment(id, entryId, name, mediaType, bytes) }
        catch (error: ApiFailure) {
            if (error.status == 401) mutable.value = mutable.value.copy(needsLogin = true, error = error.message)
            throw error
        }
    }
    suspend fun attachmentContent(attachment: Attachment, entryId: String) = api.attachmentContent(attachment, entryId)
    suspend fun send(text: String, attachments: List<Attachment> = emptyList()): Boolean {
        var queued = false
        val entryId = mutable.value.selected
        operation {
            requireNotNull(entryId)
            require((text.isNotBlank() || attachments.isNotEmpty()) && text.length <= 4000) { "请输入消息或选择附件，文字最多 4000 字。" }
            require(attachments.isEmpty() || "attachments" in mutable.value.capabilities) { "当前服务尚未启用附件消息。" }
            require(mutable.value.pending.size < 20) { "请先处理已有待发送消息。" }
            val message = PendingCommand(UUID.randomUUID().toString(), entryId, text.trim(), System.currentTimeMillis(), attachments = attachments)
            persist(mutable.value.pending + message)
            queued = true
            transmit(message)
        }
        return queued
    }
    suspend fun retry(id: String) = operation {
        val message = mutable.value.pending.first { it.id == id }
        transmit(message)
    }
    private suspend fun transmit(message: PendingCommand) {
        refreshLocked(refreshDetails = false)
        require(mutable.value.roles.any { it.id == message.entryId }) { "原角色已不可用，消息尚未重发。" }
        if (message.action != "message") require("workDetails" in mutable.value.capabilities) { "当前服务不支持原生事项操作。" }
        api.send(message, requireNotNull(generation))
        persist(mutable.value.pending.filterNot { it.id == message.id })
        // An accepted command is not a completed answer; the subsequent snapshot is authoritative.
        refreshLocked()
    }
    private fun clearWorkLocked() {
        openWorkId = null; openArtifactId = null
        mutable.value = mutable.value.copy(workDetail = null, artifactText = null)
    }
    suspend fun closeArtifact() = mutex.withLock { openArtifactId = null; mutable.value = mutable.value.copy(artifactText = null) }
    suspend fun closeWork() = mutex.withLock { clearWorkLocked() }
    private suspend fun refreshWorkLocked() {
        val id = openWorkId ?: return
        val entryId = mutable.value.selected ?: return clearWorkLocked()
        val detail = try {
            api.work(id, entryId).also { check(it.work.entryId == entryId && it.work.id == id) }
        } catch (error: Exception) {
            if (error is ApiFailure && error.status in setOf(401, 403, 404, 410))
                mutable.value = mutable.value.copy(workDetail = null, artifactText = null)
            throw error
        }
        val preview = try {
            openArtifactId?.let { artifactId ->
                api.artifact(artifactId, entryId).also { check(it.workId == id && it.id == artifactId) }
            }
        } catch (error: Exception) {
            if (error is ApiFailure && error.status in setOf(401, 403, 404, 410))
                mutable.value = mutable.value.copy(workDetail = detail, artifactText = null)
            throw error
        }
        mutable.value = mutable.value.copy(workDetail = detail, artifactText = preview)
    }
    suspend fun openWork(id: String) = operation {
        require("workDetails" in mutable.value.capabilities) { "当前服务尚未开放事项详情。" }
        clearWorkLocked(); openWorkId = id
        refreshWorkLocked()
    }
    suspend fun previewArtifact(id: String) = operation {
        require(mutable.value.workDetail?.artifacts?.any { it.id == id && it.textPreview } == true)
        openArtifactId = id
        mutable.value = mutable.value.copy(artifactText = null)
        refreshWorkLocked()
    }
    suspend fun submitWork(action: String, text: String): Boolean {
        val detail = mutable.value.workDetail
        var queued = false
        operation {
            requireNotNull(detail) { "请先读取事项当前状态。" }
            require(action in detail.allowedActions) { "当前状态不支持该操作。" }
            require(action == "work.cancel" || text.isNotBlank() && text.length <= 4000) { "请填写 1–4000 字的补充要求。" }
            require(mutable.value.pending.size < 20) { "请先处理已有待发记录。" }
            val command = PendingCommand(UUID.randomUUID().toString(), detail.work.entryId, if (action == "work.cancel") "" else text.trim(),
                System.currentTimeMillis(), action, detail.work.id, detail.work.version)
            persist(mutable.value.pending + command)
            queued = true
            transmit(command)
        }
        return queued
    }
    suspend fun removePending(id: String) = operation { persist(mutable.value.pending.filterNot { it.id == id }) }
    suspend fun createRole(id: String, name: String) = operation {
        val entryId = api.createRole(id, name)
        mutable.value = mutable.value.copy(selected = entryId, messages = emptyList(), works = emptyList())
        refreshLocked()
    }
    suspend fun persona(entryId: String): PersonaSettings = mutex.withLock { api.personas(entryId) }
    suspend fun savePersona(settings: PersonaSettings) = operation { api.savePersona(settings); refreshLocked() }
    suspend fun memories(): List<MemoryItem> = mutex.withLock { api.memories() }
}

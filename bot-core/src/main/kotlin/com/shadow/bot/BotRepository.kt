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
            mutable.value = mutable.value.copy(error = when (error) {
                is ApiFailure -> error.message
                is IllegalArgumentException -> error.message ?: "输入无效。"
                else -> "连接中断，稍后可刷新；未确认的消息保留原编号重试。"
            }, needsLogin = error is ApiFailure && error.status == 401)
        } finally { if (!silent) mutable.value = mutable.value.copy(busy = false) }
    }

    private suspend fun refreshLocked(refreshDetails: Boolean = true) {
        val hello = api.handshake()
        generation = hello.generation
        val selected = mutable.value.selected?.takeIf { id -> hello.roles.any { it.id == id } }
        mutable.value = mutable.value.copy(connected = true, needsLogin = false, roles = hello.roles,
            capabilities = hello.capabilities, selected = selected,
            messages = if (selected == null) emptyList() else mutable.value.messages,
            works = if (selected == null) emptyList() else mutable.value.works)
        if (selected == null && "inbox" in hello.capabilities) {
            val inbox = api.inbox()
            if (inbox.generation != hello.generation) { generation = null; throw ApiFailure(409, "服务正在重启，请刷新。") }
            mutable.value = mutable.value.copy(roles = hello.roles.map { role ->
                val summary = inbox.items.find { it.entryId == role.id }
                role.copy(preview = summary?.text.orEmpty(), lastMessageAt = summary?.at ?: 0, activeWorks = summary?.activeWorks ?: 0)
            }.sortedByDescending { it.lastMessageAt })
        }
        if (selected != null) {
            val snapshot = api.snapshot(selected)
            if (snapshot.generation != hello.generation) { generation = null; throw ApiFailure(409, "服务正在重启，请刷新后继续。") }
            check(snapshot.conversationId == hello.roles.first { it.id == selected }.conversationId)
            cursor = snapshot.cursor
            // Replacement is intentional: deleted/forgotten messages cannot survive a refresh.
            mutable.value = mutable.value.copy(messages = snapshot.messages, works = snapshot.works.filter { it.entryId == selected })
            if (refreshDetails) refreshWorkLocked()
        }
    }
    suspend fun refresh() = operation { refreshLocked() }
    suspend fun select(entryId: String?) = operation {
        require(entryId == null || mutable.value.roles.any { it.id == entryId })
        mutable.value = mutable.value.copy(selected = entryId, messages = emptyList(), works = emptyList())
        cursor = 0
        clearWorkLocked()
        refreshLocked()
    }
    suspend fun poll() = operation(silent = true) {
        if (mutable.value.needsLogin) return@operation
        if (generation == null || mutable.value.selected == null) { refreshLocked(); return@operation }
        val events = api.events(cursor)
        if (events.generation != generation || events.resetRequired || events.changed || events.hasMore) refreshLocked()
        else cursor = events.cursor
    }
    private fun persist(pending: List<PendingCommand>) {
        store.write(store.read().copy(pending = pending)) // disk commit precedes network and UI acknowledgement
        mutable.value = mutable.value.copy(pending = pending)
    }
    suspend fun send(text: String): Boolean {
        var queued = false
        val entryId = mutable.value.selected
        operation {
            requireNotNull(entryId)
            require(text.isNotBlank() && text.length <= 4000) { "请输入 1–4000 字的消息。" }
            require(mutable.value.pending.size < 20) { "请先处理已有待发送消息。" }
            val message = PendingCommand(UUID.randomUUID().toString(), entryId, text.trim(), System.currentTimeMillis())
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
        try {
            val detail = api.work(id, entryId)
            check(detail.work.entryId == entryId && detail.work.id == id)
            mutable.value = mutable.value.copy(workDetail = detail)
            openArtifactId?.let { artifactId ->
                mutable.value = mutable.value.copy(artifactText = null)
                val preview = api.artifact(artifactId, entryId)
                check(preview.workId == id && preview.id == artifactId)
                mutable.value = mutable.value.copy(artifactText = preview)
            }
        } catch (error: Exception) {
            mutable.value = mutable.value.copy(workDetail = null, artifactText = null)
            throw error
        }
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

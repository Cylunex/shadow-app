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
    val syncIssue: String? = null, val offline: Boolean = false, val cachedAt: Long = 0, val historyCursor: String? = null, val historyBusy: Boolean = false,
    val liveReply: LiveReply? = null, val searchQuery: String = "", val searchResults: List<Message> = emptyList(), val searchCursor: String? = null, val searchDone: Boolean = false,
    val messageContext: MessageContextPage? = null, val contextBusy: Boolean = false, val contextError: String? = null,
)

/** Serializes user writes with snapshot/event refresh; cursors are never shared between contacts. */
class BotRepository(private val api: ProductApi, private val store: StateStore) {
    private val mutex = Mutex()
    private val boundSession = store.read().session
    private fun currentSession(session: Session? = store.read().session) = session?.id == boundSession?.id &&
        (session == null && boundSession == null || session != null && boundSession != null && session.sameAccount(boundSession))
    private fun cacheForSession() = store.readCache()?.takeIf { it.server == boundSession?.baseUrl && it.sessionId == boundSession.id }
    private val mutable = MutableStateFlow(BotState(pending = store.read().pending))
    val state = mutable.asStateFlow()
    private var generation: String? = null
    private var cursor = 0L
    private var memoryEpoch = -1L
    private var olderMessages = emptyList<Message>()
    private var olderCursor: String? = null
    private var openWorkId: String? = null
    private var openArtifactId: String? = null
    private var contextRequest = 0L

    fun clearMessageContext() {
        contextRequest++
        mutable.value = mutable.value.copy(messageContext = null, contextBusy = false, contextError = null)
    }

    suspend fun openMessageContext(anchor: MessageRef) {
        clearMessageContext()
        val request = contextRequest
        val entry = mutable.value.selected ?: return
        contextPage(entry, anchor, request)
    }
    suspend fun moreMessageContext(older: Boolean) {
        val page = mutable.value.messageContext ?: return
        if (page.items.size >= 600) return
        val position = (if (older) page.before else page.after) ?: return
        contextPage(page.entryId, page.anchor, contextRequest, if (older) position else null, if (older) null else position)
    }
    private suspend fun contextPage(entry: String, anchor: MessageRef, request: Long, before: String? = null, after: String? = null) = operation {
        if (request != contextRequest || entry != mutable.value.selected) return@operation
        require("messageContext" in mutable.value.capabilities) { "当前服务尚未开放消息定位。" }
        mutable.value = mutable.value.copy(contextBusy = true, contextError = null)
        try {
            val old = mutable.value.messageContext
            if (old != null && old.items.size >= 600) return@operation
            val page = api.messageContext(entry, anchor, memoryEpoch, before, after, minOf(30, 600 - (old?.items?.size ?: 0)))
            if (request != contextRequest || entry != mutable.value.selected) return@operation
            check(page.entryId == entry && page.anchor == anchor)
            if (page.generation != generation || page.epoch != memoryEpoch) throw ApiFailure(409, "历史已更新，请重新搜索。")
            val items = when {
                before != null -> page.items + old?.items.orEmpty()
                after != null -> old?.items.orEmpty() + page.items
                else -> page.items
            }.distinctBy { it.role to it.id }
            mutable.value = mutable.value.copy(messageContext = page.copy(items = items,
                before = if (after != null) old?.before else page.before, after = if (before != null) old?.after else page.after))
        } catch (error: ApiFailure) {
            if (request != contextRequest && error.status !in setOf(401, 403)) return@operation
            if (error.status in setOf(404, 409)) {
                mutable.value = mutable.value.copy(messageContext = null, contextError = "原消息或历史内容已更新，请返回重新搜索。",
                    searchResults = emptyList(), searchCursor = null, searchDone = false)
            } else throw error
        } finally { if (request == contextRequest) mutable.value = mutable.value.copy(contextBusy = false) }
    }

    private suspend fun operation(silent: Boolean = false, block: suspend () -> Unit) = mutex.withLock {
        if (!currentSession()) return@withLock
        if (!silent) mutable.value = mutable.value.copy(busy = true, error = null)
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val message = when (error) {
                is ApiFailure -> error.message
                is IllegalArgumentException -> error.message ?: "输入无效。"
                else -> "连接中断，稍后可刷新；未确认的消息保留原编号重试。"
            }
            mutable.value = mutable.value.copy(liveReply = null)
            val expired = error is ApiFailure && error.status == 401
            if (error is ApiFailure && error.status in setOf(401, 403)) {
                clearMessageContext()
                store.clearCache(); olderMessages = emptyList(); olderCursor = null
                mutable.value = mutable.value.copy(messages = emptyList(), roles = emptyList(), works = emptyList(), searchResults = emptyList(), searchCursor = null, historyCursor = null, workDetail = null, artifactText = null)
            } else if (error !is ApiFailure && error !is IllegalArgumentException) {
                val cache = runCatching { cacheForSession() }.getOrNull()
                if (cache != null) mutable.value = mutable.value.copy(connected = true, offline = true, cachedAt = cache.savedAt,
                    roles = if (mutable.value.roles.isEmpty()) cache.roles else mutable.value.roles,
                    messages = if (mutable.value.messages.isEmpty() && mutable.value.selected == cache.entryId) cache.messages else mutable.value.messages)
            }
            mutable.value = if (silent && !expired) mutable.value.copy(syncIssue = "连接暂时中断，正在重试")
                else mutable.value.copy(error = message, needsLogin = expired)

        } finally { if (!silent) mutable.value = mutable.value.copy(busy = false) }
    }

    private suspend fun refreshLocked(refreshDetails: Boolean = true) {
        val hello = api.handshake()
        if (!currentSession()) return
        if (generation != null && generation != hello.generation) {
            clearMessageContext()
            mutable.value = mutable.value.copy(searchResults = emptyList(), searchCursor = null, searchDone = false)
        }
        val cache = cacheForSession()
        if (cache != null && cache.epoch != hello.memoryEpoch || memoryEpoch >= 0 && memoryEpoch != hello.memoryEpoch) {
            clearMessageContext()
            store.clearCache(); olderMessages = emptyList(); olderCursor = null
            mutable.value = mutable.value.copy(messages = emptyList(), roles = mutable.value.roles.map { it.copy(preview = "") }, works = emptyList(), searchResults = emptyList(), searchCursor = null, searchDone = false, workDetail = null, artifactText = null, liveReply = null)
        }
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
            if (!currentSession()) return
            if (snapshot.generation != hello.generation) { generation = null; throw ApiFailure(409, "服务正在重启，请刷新后继续。") }
            check(snapshot.conversationId == hello.roles.first { it.id == selected }.conversationId)
            nextCursor = snapshot.cursor
            // Replace authoritative content, including withdrawals, in one publication.
            if (memoryEpoch != snapshot.memoryEpoch || generation != hello.generation) {
                clearMessageContext()
                olderMessages = emptyList(); olderCursor = snapshot.historyCursor
                mutable.value = mutable.value.copy(liveReply = null)
                mutable.value = mutable.value.copy(searchResults = emptyList(), searchCursor = null, searchDone = false)
            } else if (olderMessages.isEmpty()) olderCursor = snapshot.historyCursor
            memoryEpoch = snapshot.memoryEpoch
            val currentIds = snapshot.messages.map { it.role to it.id }.toSet()
            if (olderMessages.isNotEmpty()) olderMessages = previous.messages.filter { (it.role to it.id) !in currentIds }
            messages = olderMessages.filter { (it.role to it.id) !in currentIds } + snapshot.messages
            works = snapshot.works.filter { it.entryId == selected }
            runCatching { store.markRead(selected, snapshot.messages.lastOrNull()?.id.orEmpty(), snapshot.cursor) }
        }
        if (!currentSession()) return
        generation = hello.generation
        cursor = nextCursor
        mutable.value = mutable.value.copy(connected = true, needsLogin = false, roles = roles,
            capabilities = hello.capabilities, selected = selected, messages = messages, works = works, historyCursor = olderCursor, syncIssue = null, offline = false, cachedAt = 0)
        if (selected == null || "messageContext" !in hello.capabilities) clearMessageContext()
        runCatching {
            val server = boundSession?.baseUrl
            if (server != null) {
                val old = cacheForSession()?.takeIf { it.epoch == hello.memoryEpoch }
                val saved = OfflineCache(server, hello.memoryEpoch, System.currentTimeMillis(), roles, selected ?: old?.entryId, if (selected != null) messages.takeLast(200) else old?.messages.orEmpty(), boundSession.id)
                if (old?.copy(savedAt = saved.savedAt) != saved) store.writeCache(saved)
            }
        }
        if (selected == null) clearWorkLocked()
        else if (refreshDetails) refreshWorkLocked()
    }
    suspend fun refresh(silent: Boolean = false) = operation(silent = silent && mutable.value.connected) { refreshLocked() }
    suspend fun select(entryId: String?) = operation {
        clearMessageContext()
        require(entryId == null || mutable.value.roles.any { it.id == entryId })
        mutable.value = mutable.value.copy(selected = entryId, messages = emptyList(), works = emptyList(), liveReply = null)
        cursor = 0; memoryEpoch = -1; olderMessages = emptyList(); olderCursor = null
        mutable.value = mutable.value.copy(historyCursor = null, searchResults = emptyList(), searchCursor = null, searchQuery = "", searchDone = false)
        clearWorkLocked()
        refreshLocked()
    }
    suspend fun poll() = operation(silent = true) {
        if (mutable.value.needsLogin) return@operation
        if (generation == null || mutable.value.selected == null || mutable.value.syncIssue != null) { refreshLocked(); return@operation }
        if ("liveReplies" in mutable.value.capabilities) {
            val entry = requireNotNull(mutable.value.selected)
            val live = api.live(entry)
            check(live.entryId == entry)
            if (live.generation != generation) { mutable.value = mutable.value.copy(liveReply = null); refreshLocked(); return@operation }
            val ended = mutable.value.liveReply != null && live.reply == null
            mutable.value = mutable.value.copy(liveReply = live.reply)
            if (ended) { refreshLocked(); return@operation }
        }
        val events = api.events(cursor)
        if (events.generation != generation || events.resetRequired || events.changed || events.hasMore) refreshLocked()
        else { cursor = events.cursor; mutable.value = mutable.value.copy(syncIssue = null) }
    }
    suspend fun loadHistory() = operation(silent = true) {
        val entry = mutable.value.selected ?: return@operation
        val before = olderCursor ?: return@operation
        mutable.value = mutable.value.copy(historyBusy = true)
        try {
            val page = api.history(entry, before)
            if (page.generation != generation || page.epoch != memoryEpoch) { refreshLocked(); return@operation }
            check(page.entryId == entry && page.query.isEmpty())
            olderMessages = (page.items + olderMessages).distinctBy { it.role to it.id }
            olderCursor = page.next
            val messages = (page.items + mutable.value.messages).distinctBy { it.role to it.id }
            mutable.value = mutable.value.copy(messages = messages, historyCursor = olderCursor)
        } catch (error: ApiFailure) {
            if (error.status == 409) { memoryEpoch = -1; refreshLocked() } else throw error
        } finally { mutable.value = mutable.value.copy(historyBusy = false) }
    }
    suspend fun searchMessages(query: String, more: Boolean = false) = operation {
        val entry = mutable.value.selected ?: return@operation
        require(query.isNotBlank() && query.length <= 200) { "请输入 1–200 字的搜索词。" }
        val before = if (more && mutable.value.searchQuery == query) mutable.value.searchCursor ?: return@operation else null
        if (before == null) mutable.value = mutable.value.copy(searchQuery = query, searchResults = emptyList(), searchCursor = null, searchDone = false)
        try {
            val page = api.history(entry, before, query)
            check(page.entryId == entry && page.query == query.trim())
            if (page.generation != generation || page.epoch != memoryEpoch) throw ApiFailure(409, "历史已更新，请重新搜索。")
            mutable.value = mutable.value.copy(searchResults = (mutable.value.searchResults + page.items.asReversed()).distinctBy { it.role to it.id },
                searchCursor = page.next, searchDone = true)
        } catch (error: ApiFailure) {
            if (error.status == 409) {
                clearMessageContext()
                mutable.value = mutable.value.copy(searchResults = emptyList(), searchCursor = null, searchDone = false)
                refreshLocked()
            }
            throw error
        }
    }
    suspend fun artifactContent(artifact: Artifact, entryId: String) = api.artifactContent(artifact, entryId)
    fun clearLiveReply() { mutable.value = mutable.value.copy(liveReply = null) }
    private fun persist(pending: List<PendingCommand>, consume: PendingCommand? = null) {
        store.update { current ->
            check(currentSession(current.session)) { "连接已变更，请在当前连接重试。" }
            var saved = current.copy(pending = pending)
            // Consuming the draft and queuing its command is one durable write.
            // A killed process cannot restore both an already queued message and its sendable draft.
            if (consume != null) saved = if (consume.action == "message") saved.copy(
                drafts = if (saved.drafts[consume.entryId]?.trim() == consume.text) saved.drafts - consume.entryId else saved.drafts,
                attachmentDrafts = saved.attachmentDrafts + (consume.entryId to saved.attachmentDrafts[consume.entryId].orEmpty()
                    .filterNot { draft -> consume.attachments.any { it.id == draft.id } }))
            else saved.copy(workDrafts = if (consume.action == "work.resume" && saved.workDrafts[consume.workId]?.trim() == consume.text)
                saved.workDrafts - requireNotNull(consume.workId) else saved.workDrafts)
            saved
        } // disk commit precedes network and UI acknowledgement
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
            persist(mutable.value.pending + message, consume = message)
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
        if (message.action.startsWith("work.")) require("workDetails" in mutable.value.capabilities) { "当前服务不支持原生事项操作。" }
        if (message.action.startsWith("memory.")) require("memoryActions" in mutable.value.capabilities) { "当前服务不支持逐条记忆操作。" }
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
            persist(mutable.value.pending + command, consume = command)
            queued = true
            transmit(command)
        }
        return queued
    }
    private suspend fun <T> guardedRead(block: suspend () -> T): T = mutex.withLock {
        try { block() } catch (error: ApiFailure) {
            if (error.status in setOf(401, 403)) {
                clearMessageContext()
                store.clearCache(); olderMessages = emptyList(); olderCursor = null
                mutable.value = mutable.value.copy(needsLogin = error.status == 401, messages = emptyList(), roles = emptyList(), works = emptyList(),
                    searchResults = emptyList(), liveReply = null, workDetail = null, artifactText = null, error = error.message)
            }
            throw error
        }
    }
    suspend fun memory(id: String): MemoryDetail = guardedRead { api.memory(id) }
    suspend fun submitMemory(item: MemoryItem, action: String, text: String): Boolean {
        var queued = false
        operation {
            require(action in setOf("memory.correct", "memory.quiet", "memory.forget"))
            require(action != "memory.correct" || text.isNotBlank() && text.length <= 2000) { "更正内容须为 1–2000 字。" }
            require(mutable.value.pending.size < 20) { "请先处理已有待发记录。" }
            val entry = requireNotNull(mutable.value.selected)
            val command = PendingCommand(UUID.randomUUID().toString(), entry, if (action == "memory.correct") text.trim() else "", System.currentTimeMillis(),
                action = action, expectedVersion = item.version, memoryId = item.id)
            persist(mutable.value.pending + command)
            queued = true; transmit(command)
        }
        return queued
    }
    suspend fun archiveContact(role: BotRole, archived: Boolean) = operation {
        api.saveContact(role, archived); refreshLocked()
    }
    suspend fun removePending(id: String) = operation { persist(mutable.value.pending.filterNot { it.id == id }) }
    suspend fun createRole(id: String, name: String) = operation {
        val entryId = api.createRole(id, name)
        mutable.value = mutable.value.copy(selected = entryId, messages = emptyList(), works = emptyList(), liveReply = null)
        refreshLocked()
    }
    suspend fun persona(entryId: String): PersonaSettings = guardedRead { api.personas(entryId) }
    suspend fun savePersona(settings: PersonaSettings) = operation { api.savePersona(settings); refreshLocked() }
    suspend fun memories(): List<MemoryItem> = guardedRead { api.memories() }
}

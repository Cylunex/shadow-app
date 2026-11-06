package com.shadow.bot

import org.json.JSONArray
import org.json.JSONObject

// A contact owns a conversation; the selected persona may change independently.
data class BotRole(val id: String, val conversationId: String, val name: String, val personaId: String = "", val preview: String = "", val lastMessageAt: Long = 0, val activeWorks: Int = 0, val archived: Boolean = false, val contactVersion: Long = 0)
data class Attachment(val id: String, val name: String, val mediaType: String, val bytes: Long, val sha256: String, val assetVersionId: String) {
    init {
        require(Regex("[a-zA-Z0-9_-]{8,100}").matches(id) && name.isNotBlank() && name.length <= 240)
        require(bytes in 1..20L * 1024 * 1024 && Regex("[a-f0-9]{64}").matches(sha256))
        require(mediaType.length <= 160 && Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(mediaType))
        require(Regex("assetv_[a-zA-Z0-9_-]+").matches(assetVersionId))
    }
}
data class Message(val id: String, val role: String, val text: String, val at: Long, val personaName: String = "", val state: String = "", val attachments: List<Attachment> = emptyList())
data class Work(val id: String, val entryId: String, val goal: String, val state: String, val result: String, val nextStep: String, val version: Long = 0, val paused: Boolean = false)
data class MemoryItem(val id: String, val text: String, val kind: String, val version: Long = 0)
data class MemorySource(val id: String, val text: String, val at: String, val truncated: Boolean)
data class MemoryDetail(val item: MemoryItem, val sources: List<MemorySource>)
data class Handshake(val generation: String, val roles: List<BotRole>, val capabilities: Set<String>, val memoryEpoch: Long = 0)
data class Snapshot(val generation: String, val conversationId: String, val messages: List<Message>, val works: List<Work>, val cursor: Long, val historyCursor: String? = null, val memoryEpoch: Long = 0)
data class MessagePage(val generation: String, val entryId: String, val epoch: Long, val query: String, val items: List<Message>, val next: String?)
data class MessageRef(val id: String, val role: String)
data class MessageContextPage(val generation: String, val entryId: String, val epoch: Long, val anchor: MessageRef,
    val items: List<Message>, val before: String?, val after: String?)
data class LiveReply(val id: String, val text: String)
data class LiveReplyState(val generation: String, val entryId: String, val reply: LiveReply?)
data class Events(val generation: String, val cursor: Long, val changed: Boolean, val resetRequired: Boolean, val hasMore: Boolean)
data class PendingCommand(val id: String, val entryId: String, val text: String, val createdAt: Long,
    val action: String = "message", val workId: String? = null, val expectedVersion: Long? = null, val attachments: List<Attachment> = emptyList(), val memoryId: String? = null) {
    init {
        require(action in setOf("message", "work.resume", "work.cancel", "memory.correct", "memory.quiet", "memory.forget"))
        require(when {
            action == "message" -> workId == null && memoryId == null && expectedVersion == null
            action.startsWith("memory.") -> workId == null && !memoryId.isNullOrBlank() && expectedVersion != null && expectedVersion >= 0
            else -> !workId.isNullOrBlank() && memoryId == null && expectedVersion != null && expectedVersion >= 0
        })
        require(attachments.size <= 8 && attachments.map { it.id }.distinct().size == attachments.size && (action == "message" || attachments.isEmpty()))
    }
    val label: String get() = when (action) { "work.resume" -> "继续事项"; "work.cancel" -> "取消事项"; "memory.correct" -> "更正记忆"; "memory.quiet" -> "以后少提"; "memory.forget" -> "遗忘记忆"; else -> "消息" }
}
data class InboxItem(val entryId: String, val text: String, val at: Long, val activeWorks: Int, val messageId: String = "", val messageRole: String = "", val workSequence: Long = 0)
data class Inbox(val generation: String, val items: List<InboxItem>)
data class PlanStep(val content: String, val status: String)
data class WorkRun(val id: String, val state: String, val startedAt: Long, val finishedAt: Long, val result: String, val truncated: Boolean)
data class Artifact(val id: String, val name: String, val size: Long, val type: String, val textPreview: Boolean, val sha256: String = "", val downloadable: Boolean = false)
data class WorkDetail(val work: Work, val plan: List<PlanStep>, val runs: List<WorkRun>, val artifacts: List<Artifact>,
    val allowedActions: Set<String>, val resultTruncated: Boolean, val planTruncated: Boolean)
data class ArtifactText(val id: String, val workId: String, val name: String, val text: String, val truncated: Boolean)
data class PersonaCard(val id: String, val name: String, val displayName: String, val instructions: String, val inheritIdentity: Boolean)
data class PersonaSettings(val entryId: String, val revision: Int, val active: String, val contactEnabled: Boolean, val cards: List<PersonaCard>)
data class Session(val baseUrl: String, val cookies: List<String>)
data class AttachmentDraft(val id: String, val uri: String = "", val name: String = "正在读取附件…", val status: String = "queued",
    val error: String? = null, val uploaded: Attachment? = null, val mediaType: String = "application/octet-stream", val localReady: Boolean = false)
data class SavedState(val session: Session? = null, val pending: List<PendingCommand> = emptyList(),
    val drafts: Map<String, String> = emptyMap(), val workDrafts: Map<String, String> = emptyMap(),
    val attachmentDrafts: Map<String, List<AttachmentDraft>> = emptyMap())
interface StateStore {
    fun read(): SavedState
    fun write(state: SavedState)
    fun markRead(entryId: String, messageId: String, sequence: Long) {}
    fun readCache(): OfflineCache? = null
    fun writeCache(cache: OfflineCache) {}
    fun clearCache() {}
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.string(key: String) = if (isNull(key)) "" else optString(key)

object StateCodec {
    fun encode(state: SavedState): String = JSONObject().apply {
        put("version", 4)
        state.session?.let { put("session", JSONObject().put("baseUrl", it.baseUrl).put("cookies", JSONArray(it.cookies))) }
        put("pending", JSONArray(state.pending.map { JSONObject().put("id", it.id).put("entryId", it.entryId).put("text", it.text).put("createdAt", it.createdAt).put("action", it.action).put("workId", it.workId).put("expectedVersion", it.expectedVersion).put("memoryId", it.memoryId).put("attachments", JSONArray(it.attachments.map(::attachmentJson))) }))
        put("drafts", JSONObject(state.drafts)); put("workDrafts", JSONObject(state.workDrafts))
        put("attachmentDrafts", JSONObject().apply { state.attachmentDrafts.forEach { (entry, drafts) ->
            put(entry, JSONArray(drafts.map { draft -> JSONObject().put("id", draft.id).put("name", draft.name)
                .put("mediaType", draft.mediaType).put("localReady", draft.localReady)
                .apply { draft.uploaded?.let { put("uploaded", attachmentJson(it)) } } }))
        } })
    }.toString()
    fun decode(raw: String): SavedState {
        val json = JSONObject(raw)
        require(json.getInt("version") in 1..4) { "本地资料版本不受支持" }
        val session = json.optJSONObject("session")?.let { row ->
            val cookies = row.getJSONArray("cookies")
            Session(row.getString("baseUrl"), (0 until cookies.length()).map(cookies::getString))
        }
        return SavedState(session, json.getJSONArray("pending").objects().map {
            PendingCommand(it.getString("id"), it.getString("entryId"), it.getString("text"), it.getLong("createdAt"), it.optString("action", "message"),
                it.string("workId").takeIf(String::isNotEmpty), if (it.has("expectedVersion") && !it.isNull("expectedVersion")) it.getLong("expectedVersion") else null,
                (it.optJSONArray("attachments") ?: JSONArray()).objects().map(::parseAttachment), it.string("memoryId").takeIf(String::isNotEmpty))
        }, drafts = stringMap(json.optJSONObject("drafts")), workDrafts = stringMap(json.optJSONObject("workDrafts")),
            attachmentDrafts = json.optJSONObject("attachmentDrafts")?.let { entries -> entries.keys().asSequence().associateWith { entry ->
                entries.getJSONArray(entry).objects().map { row ->
                    val uploaded = row.optJSONObject("uploaded")?.let(::parseAttachment)
                    AttachmentDraft(row.getString("id"), name = row.getString("name"), uploaded = uploaded,
                        mediaType = row.getString("mediaType"), localReady = row.optBoolean("localReady"),
                        status = if (uploaded != null) "ready" else "failed",
                        error = if (row.optBoolean("localReady")) "上传尚未完成，点击重试继续。" else "附件尚未读取完成，请移除后重新选择。")
                }
            } } ?: emptyMap())
    }
    private fun stringMap(json: JSONObject?): Map<String, String> = json?.let { row -> row.keys().asSequence().associateWith(row::getString) } ?: emptyMap()
}

internal fun attachmentJson(value: Attachment) = JSONObject().put("id", value.id).put("name", value.name).put("mediaType", value.mediaType)
    .put("bytes", value.bytes).put("sha256", value.sha256).put("assetVersionId", value.assetVersionId)
internal fun parseAttachment(row: JSONObject) = Attachment(row.getString("id"), row.getString("name"), row.getString("mediaType"),
    row.getLong("bytes"), row.getString("sha256"), row.getString("assetVersionId"))

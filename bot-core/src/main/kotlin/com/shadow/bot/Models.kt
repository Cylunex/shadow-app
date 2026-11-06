package com.shadow.bot

import org.json.JSONArray
import org.json.JSONObject

// A contact owns a conversation; the selected persona may change independently.
data class BotRole(val id: String, val conversationId: String, val name: String, val personaId: String = "", val preview: String = "", val lastMessageAt: Long = 0, val activeWorks: Int = 0)
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
data class MemoryItem(val id: String, val text: String, val kind: String)
data class Handshake(val generation: String, val roles: List<BotRole>, val capabilities: Set<String>)
data class Snapshot(val generation: String, val conversationId: String, val messages: List<Message>, val works: List<Work>, val cursor: Long)
data class Events(val generation: String, val cursor: Long, val changed: Boolean, val resetRequired: Boolean, val hasMore: Boolean)
data class PendingCommand(val id: String, val entryId: String, val text: String, val createdAt: Long,
    val action: String = "message", val workId: String? = null, val expectedVersion: Long? = null, val attachments: List<Attachment> = emptyList()) {
    init {
        require(action in setOf("message", "work.resume", "work.cancel"))
        require(if (action == "message") workId == null && expectedVersion == null else !workId.isNullOrBlank() && expectedVersion != null && expectedVersion >= 0)
        require(attachments.size <= 8 && attachments.map { it.id }.distinct().size == attachments.size && (action == "message" || attachments.isEmpty()))
    }
    val label: String get() = when (action) { "work.resume" -> "继续事项"; "work.cancel" -> "取消事项"; else -> "消息" }
}
data class InboxItem(val entryId: String, val text: String, val at: Long, val activeWorks: Int)
data class Inbox(val generation: String, val items: List<InboxItem>)
data class PlanStep(val content: String, val status: String)
data class WorkRun(val id: String, val state: String, val startedAt: Long, val finishedAt: Long, val result: String, val truncated: Boolean)
data class Artifact(val id: String, val name: String, val size: Long, val type: String, val textPreview: Boolean)
data class WorkDetail(val work: Work, val plan: List<PlanStep>, val runs: List<WorkRun>, val artifacts: List<Artifact>,
    val allowedActions: Set<String>, val resultTruncated: Boolean, val planTruncated: Boolean)
data class ArtifactText(val id: String, val workId: String, val name: String, val text: String, val truncated: Boolean)
data class PersonaCard(val id: String, val name: String, val displayName: String, val instructions: String, val inheritIdentity: Boolean)
data class PersonaSettings(val entryId: String, val revision: Int, val active: String, val contactEnabled: Boolean, val cards: List<PersonaCard>)
data class Session(val baseUrl: String, val cookies: List<String>)
data class SavedState(val session: Session? = null, val pending: List<PendingCommand> = emptyList())
interface StateStore { fun read(): SavedState; fun write(state: SavedState) }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.string(key: String) = if (isNull(key)) "" else optString(key)

object StateCodec {
    fun encode(state: SavedState): String = JSONObject().apply {
        put("version", 3)
        state.session?.let { put("session", JSONObject().put("baseUrl", it.baseUrl).put("cookies", JSONArray(it.cookies))) }
        put("pending", JSONArray(state.pending.map { JSONObject().put("id", it.id).put("entryId", it.entryId).put("text", it.text).put("createdAt", it.createdAt).put("action", it.action).put("workId", it.workId).put("expectedVersion", it.expectedVersion).put("attachments", JSONArray(it.attachments.map(::attachmentJson))) }))
    }.toString()
    fun decode(raw: String): SavedState {
        val json = JSONObject(raw)
        require(json.getInt("version") in 1..3) { "本地资料版本不受支持" }
        val session = json.optJSONObject("session")?.let { row ->
            val cookies = row.getJSONArray("cookies")
            Session(row.getString("baseUrl"), (0 until cookies.length()).map(cookies::getString))
        }
        return SavedState(session, json.getJSONArray("pending").objects().map {
            PendingCommand(it.getString("id"), it.getString("entryId"), it.getString("text"), it.getLong("createdAt"), it.optString("action", "message"),
                it.string("workId").takeIf(String::isNotEmpty), if (it.has("expectedVersion") && !it.isNull("expectedVersion")) it.getLong("expectedVersion") else null,
                (it.optJSONArray("attachments") ?: JSONArray()).objects().map(::parseAttachment))
        })
    }
}

internal fun attachmentJson(value: Attachment) = JSONObject().put("id", value.id).put("name", value.name).put("mediaType", value.mediaType)
    .put("bytes", value.bytes).put("sha256", value.sha256).put("assetVersionId", value.assetVersionId)
internal fun parseAttachment(row: JSONObject) = Attachment(row.getString("id"), row.getString("name"), row.getString("mediaType"),
    row.getLong("bytes"), row.getString("sha256"), row.getString("assetVersionId"))

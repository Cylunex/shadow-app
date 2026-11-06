package com.shadow.bot

import org.json.JSONArray
import org.json.JSONObject

// A contact owns a conversation; the selected persona may change independently.
data class BotRole(val id: String, val conversationId: String, val name: String, val personaId: String = "")
data class Message(val id: String, val role: String, val text: String, val at: Long, val personaName: String = "", val state: String = "")
data class Work(val id: String, val entryId: String, val goal: String, val state: String, val result: String, val nextStep: String)
data class MemoryItem(val id: String, val text: String, val kind: String)
data class Handshake(val generation: String, val roles: List<BotRole>, val capabilities: Set<String>)
data class Snapshot(val generation: String, val conversationId: String, val messages: List<Message>, val works: List<Work>, val cursor: Long)
data class Events(val generation: String, val cursor: Long, val changed: Boolean, val resetRequired: Boolean, val hasMore: Boolean)
data class PendingMessage(val id: String, val entryId: String, val text: String, val createdAt: Long)
data class PersonaCard(val id: String, val name: String, val displayName: String, val instructions: String, val inheritIdentity: Boolean)
data class PersonaSettings(val entryId: String, val revision: Int, val active: String, val contactEnabled: Boolean, val cards: List<PersonaCard>)
data class Session(val baseUrl: String, val cookies: List<String>)
data class SavedState(val session: Session? = null, val pending: List<PendingMessage> = emptyList())
interface StateStore { fun read(): SavedState; fun write(state: SavedState) }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.string(key: String) = if (isNull(key)) "" else optString(key)

object StateCodec {
    fun encode(state: SavedState): String = JSONObject().apply {
        put("version", 1)
        state.session?.let { put("session", JSONObject().put("baseUrl", it.baseUrl).put("cookies", JSONArray(it.cookies))) }
        put("pending", JSONArray(state.pending.map { JSONObject().put("id", it.id).put("entryId", it.entryId).put("text", it.text).put("createdAt", it.createdAt) }))
    }.toString()
    fun decode(raw: String): SavedState {
        val json = JSONObject(raw)
        require(json.getInt("version") == 1) { "本地资料版本不受支持" }
        val session = json.optJSONObject("session")?.let { row ->
            val cookies = row.getJSONArray("cookies")
            Session(row.getString("baseUrl"), (0 until cookies.length()).map(cookies::getString))
        }
        return SavedState(session, json.getJSONArray("pending").objects().map {
            PendingMessage(it.getString("id"), it.getString("entryId"), it.getString("text"), it.getLong("createdAt"))
        })
    }
}

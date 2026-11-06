package com.shadow.bot

import org.json.JSONArray
import org.json.JSONObject

/** One bounded, encrypted local view; never a message or memory authority. */
data class OfflineCache(val server: String, val epoch: Long, val savedAt: Long, val roles: List<BotRole>, val entryId: String?, val messages: List<Message>)
object OfflineCacheCodec {
    fun encode(cache: OfflineCache): String = JSONObject().apply {
        put("server", cache.server); put("epoch", cache.epoch); put("savedAt", cache.savedAt); put("entryId", cache.entryId)
        put("roles", JSONArray(cache.roles.map { JSONObject().put("id", it.id).put("conversationId", it.conversationId).put("name", it.name)
            .put("preview", it.preview).put("lastMessageAt", it.lastMessageAt).put("activeWorks", it.activeWorks).put("archived", it.archived).put("contactVersion", it.contactVersion) }))
        put("messages", JSONArray(cache.messages.takeLast(200).map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)
            .put("at", it.at).put("personaName", it.personaName).put("state", it.state).put("attachments", JSONArray(it.attachments.map(::attachmentJson))) }))
    }.toString()
    fun decode(raw: String): OfflineCache = JSONObject(raw).let { row ->
        OfflineCache(row.getString("server"), row.getLong("epoch"), row.getLong("savedAt"), row.getJSONArray("roles").objects().map {
            BotRole(it.getString("id"), it.getString("conversationId"), it.getString("name"), preview = it.string("preview"), lastMessageAt = it.optLong("lastMessageAt"),
                activeWorks = it.optInt("activeWorks"), archived = it.optBoolean("archived"), contactVersion = it.optLong("contactVersion"))
        }, row.string("entryId").takeIf(String::isNotEmpty), row.getJSONArray("messages").objects().map {
            Message(it.getString("id"), it.getString("role"), it.string("text"), it.getLong("at"), it.string("personaName"), it.string("state"), it.getJSONArray("attachments").objects().map(::parseAttachment))
        })
    }
}

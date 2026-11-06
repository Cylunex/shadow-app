package com.shadow.bot

import org.json.JSONObject

data class DeviceGrant(val id: String, val token: String, val expiresAt: Long)
data class BotDevice(val id: String, val name: String, val current: Boolean, val active: Boolean, val createdAt: Long,
    val lastSeenAt: Long, val expiresAt: Long, val revision: Long, val push: String)
data class PushConfig(val enabled: Boolean, val vapid: String?)
data class PushSubscription(val endpoint: String, val publicKey: String, val auth: String)
data class Playback(val entryId: String, val generation: String, val revision: Long, val state: String,
    val messageId: String = "", val deviceId: String = "", val audioId: String = "", val positionMs: Int = 0,
    val leaseUntil: Long = 0, val epoch: Long = 0) {
    fun ownedBy(grant: DeviceGrant) = deviceId == grant.id && state == "playing"
}
internal fun parsePlayback(row: JSONObject) = Playback(row.getString("entryId"), row.getString("generation"), row.getLong("revision"), row.getString("state"),
    row.optString("messageId"), row.optString("deviceId"), row.optString("audioId"), row.optInt("positionMs"), row.optLong("leaseUntil"), row.optLong("epoch"))
internal fun parseDevice(row: JSONObject) = BotDevice(row.getString("id"), row.getString("name"), row.getBoolean("current"), row.getBoolean("active"),
    row.getLong("createdAt"), row.getLong("lastSeenAt"), row.getLong("expiresAt"), row.getLong("revision"), row.getString("push"))

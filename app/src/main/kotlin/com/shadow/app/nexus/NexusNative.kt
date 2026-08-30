package com.shadow.app.nexus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.shadow.app.MainActivity
import com.shadow.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Native Nexus capabilities: a Keystore-encrypted offline action queue and deduplicated briefs. */
object NexusNative {
    private const val PREFS = "nexus_native"
    private const val QUEUE = "encrypted_actions"
    private const val LAST_BRIEF = "last_brief_id"
    private const val KEY_ALIAS = "shadow-nexus-offline-v1"
    private const val CHANNEL = "shadow-briefs"
    private const val MAX_ACTIONS = 100

    @JvmStatic
    fun enqueueAction(context: Context, raw: String): String {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 64 * 1024) { "action too large" }
        val input = JSONObject(raw)
        require(input.optString("domain").matches(Regex("^[a-z][a-z0-9-]{1,63}$")))
        require(input.optString("actionId").matches(Regex("^[a-z][a-z0-9-]{1,63}$")))
        require(input.optJSONObject("fields") != null)
        val actions = readActions(context)
        require(actions.length() < MAX_ACTIONS) { "offline queue is full" }
        val id = "offline_${UUID.randomUUID()}"
        input.put("id", id).put("createdAt", Instant.now().toString())
        actions.put(input)
        writeActions(context, actions)
        return id
    }

    @JvmStatic
    fun actionsJson(context: Context): String = readActions(context).toString()

    @JvmStatic
    fun completeAction(context: Context, id: String) {
        val current = readActions(context)
        val next = JSONArray()
        for (index in 0 until current.length()) {
            val item = current.optJSONObject(index) ?: continue
            if (item.optString("id") != id) next.put(item)
        }
        writeActions(context, next)
    }

    @JvmStatic
    fun showBrief(context: Context, raw: String) {
        val brief = JSONObject(raw)
        if (!brief.optBoolean("notify", false)) return
        val id = brief.optString("id")
        val title = brief.optString("title")
        val body = brief.optString("body")
        if (id.isBlank() || title.isBlank() || body.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(LAST_BRIEF, "") == id) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Shadow 简报", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Nexus 例外、建议与数据新鲜度简报"
            })
        }
        val open = PendingIntent.getActivity(context, 3101, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_shadow).setContentIntent(open).setAutoCancel(true).build()
        try {
            manager.notify(id.hashCode(), notification)
            prefs.edit().putString(LAST_BRIEF, id).apply()
        } catch (_: SecurityException) {
            // Android 13+ may deny notification permission; the brief remains visible in Nexus.
        }
    }

    private fun readActions(context: Context): JSONArray {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(QUEUE, "") ?: ""
        if (encoded.isBlank()) return JSONArray()
        return try { JSONArray(decrypt(encoded)) } catch (_: Exception) { JSONArray() }
    }

    private fun writeActions(context: Context, actions: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(QUEUE, encrypt(actions.toString())).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }
}

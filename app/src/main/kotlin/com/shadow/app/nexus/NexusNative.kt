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
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.shadow.app.MainActivity
import com.shadow.app.R
import com.shadow.app.core.NotificationAccess
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
    private const val TAG = "NexusNative"
    private const val PREFS = "nexus_native"
    private const val QUEUE = "encrypted_actions"
    private const val LAST_BRIEF = "last_brief_id"
    private const val KEY_ALIAS = "shadow-nexus-offline-v1"
    private const val CHANNEL = "shadow-briefs"
    private const val REPLAY_WORK = "nexus-offline-replay-reminder"
    private const val MAX_ACTIONS = 100
    private val lock = Any()
    private val forbiddenFieldNames = Regex(
        "^(authorization|cookie|credential|html|password|script|secret|token|uri|url)$",
        RegexOption.IGNORE_CASE
    )

    @JvmStatic
    fun enqueueAction(context: Context, raw: String): String {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 64 * 1024) { "action too large" }
        val input = JSONObject(raw)
        require(input.keys().asSequence().toSet() == setOf("domain", "actionId", "fields")) {
            "unexpected action fields"
        }
        require(input.optString("domain").matches(Regex("^[a-z][a-z0-9-]{1,63}$")))
        require(input.optString("actionId").matches(Regex("^[a-z][a-z0-9-]{1,63}$")))
        val fields = requireNotNull(input.optJSONObject("fields"))
        validateFields(fields, 0)
        val id = "offline_${UUID.randomUUID()}"
        input.put("id", id).put("createdAt", Instant.now().toString())
        synchronized(lock) {
            val actions = readActions(context)
            require(actions.length() < MAX_ACTIONS) { "offline queue is full" }
            actions.put(input)
            writeActions(context, actions)
        }
        scheduleReplayReminder(context)
        return id
    }

    @JvmStatic
    fun actionsJson(context: Context): String = synchronized(lock) {
        readActions(context).toString()
    }

    @JvmStatic
    fun completeAction(context: Context, id: String) {
        require(id.matches(Regex("^offline_[0-9a-fA-F-]{36}$"))) { "invalid action id" }
        val remaining = synchronized(lock) {
            val current = readActions(context)
            val next = JSONArray()
            for (index in 0 until current.length()) {
                val item = current.optJSONObject(index) ?: continue
                if (item.optString("id") != id) next.put(item)
            }
            writeActions(context, next)
            next.length()
        }
        if (remaining == 0) {
            WorkManager.getInstance(context).cancelUniqueWork(REPLAY_WORK)
        } else {
            scheduleReplayReminder(context)
        }
    }

    /** Re-establishes the network reminder after process replacement without replaying business IO. */
    @JvmStatic
    fun restore(context: Context) {
        val pending = synchronized(lock) { readActions(context).length() }
        if (pending > 0) scheduleReplayReminder(context)
    }

    @JvmStatic
    fun showBrief(context: Context, raw: String) {
        val brief = JSONObject(raw)
        if (!brief.optBoolean("notify", false)) return
        val id = brief.optString("id")
        val title = brief.optString("title")
        val body = brief.optString("body")
        if (!id.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$")) || title.isBlank() || body.isBlank()) return
        if (title.length > 160 || body.length > 2_000 || !NotificationAccess.isAllowed(context)) return
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString(QUEUE, "") ?: ""
        if (encoded.isBlank()) return JSONArray()
        return try {
            JSONArray(decrypt(encoded))
        } catch (error: Exception) {
            // A restored ciphertext cannot be decrypted by the device-bound Keystore key. The
            // backup rules exclude it, but fail closed for upgrades or OEM transfer bugs too.
            Log.w(TAG, "discarding unreadable offline action queue", error)
            prefs.edit().remove(QUEUE).apply()
            JSONArray()
        }
    }

    private fun writeActions(context: Context, actions: JSONArray) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(QUEUE, encrypt(actions.toString())).commit()) {
            "offline action queue could not be persisted"
        }
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

    private fun scheduleReplayReminder(context: Context) {
        if (!NotificationAccess.isAllowed(context)) return
        val request = OneTimeWorkRequestBuilder<NexusReplayReminderWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(REPLAY_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun validateFields(value: Any?, depth: Int) {
        require(depth <= 4) { "action fields are too deeply nested" }
        when (value) {
            null, JSONObject.NULL -> Unit
            is Boolean, is Number -> Unit
            is String -> require(value.length <= 4_096) { "action field is too long" }
            is JSONObject -> {
                require(value.length() <= 64) { "too many action fields" }
                value.keys().forEach { key ->
                    require(key.matches(Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$"))) {
                        "invalid action field name"
                    }
                    require(!forbiddenFieldNames.matches(key.substringAfterLast('.'))) {
                        "credentials, scripts and URLs cannot be queued"
                    }
                    validateFields(value.opt(key), depth + 1)
                }
            }
            is JSONArray -> {
                require(value.length() <= 100) { "action array is too large" }
                for (index in 0 until value.length()) validateFields(value.opt(index), depth + 1)
            }
            else -> throw IllegalArgumentException("unsupported action field")
        }
    }
}

/**
 * WorkManager reliability hook: when connectivity returns, prompt the user to reopen Nexus.
 * The worker intentionally does not submit domain actions; Nexus still owns validation/replay.
 */
class NexusReplayReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val pending = try {
            JSONArray(NexusNative.actionsJson(applicationContext)).length()
        } catch (_: Exception) {
            0
        }
        if (pending == 0 || !NotificationAccess.isAllowed(applicationContext)) {
            return Result.success()
        }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
            ?: return Result.success()
        if (manager.getNotificationChannel("shadow-offline-actions") == null) {
            manager.createNotificationChannel(NotificationChannel(
                "shadow-offline-actions", "Nexus 离线动作", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "网络恢复后提醒继续由 Nexus 安全重放待处理动作" })
        }
        val open = PendingIntent.getActivity(
            applicationContext, 3102, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(applicationContext, "shadow-offline-actions")
            .setContentTitle("Nexus 有待处理动作")
            .setContentText("网络已恢复，打开 Nexus 继续处理 $pending 项离线动作")
            .setSmallIcon(R.drawable.ic_stat_shadow)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(3102, notification)
            Result.success()
        } catch (_: SecurityException) {
            Result.success()
        }
    }
}

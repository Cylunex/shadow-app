package com.shadow.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.shadow.bot.sameAccount
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object BackgroundChecks {
    private const val NAME = "bot-background-checks"
    @Volatile var foreground = false
    fun enabled(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun configure(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        val work = WorkManager.getInstance(context)
        if (enabled) work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BotNotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        else {
            work.cancelUniqueWork(NAME)
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
    }
}

class BotNotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!BackgroundChecks.enabled(context)) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val store = SecureStateStore(context)
        val session = try { store.read().session } catch (_: Exception) { return Result.failure() } ?: return Result.success()
        return try {
            val api = authenticatedApi(context, store, session)
            val hello = api.handshake()
            val inbox = api.inbox()
            if (hello.generation != inbox.generation) return Result.retry()
            val ledger = store.readPrivate("notifications")?.let(::JSONObject) ?: JSONObject()
            val sameServer = ledger.optString("server") == session.baseUrl && ledger.optLong("epoch", -1) == hello.memoryEpoch
            val previous = if (sameServer) ledger.optJSONObject("entries") ?: JSONObject() else JSONObject()
            val readMarks = store.readPrivate("read-marks")?.let(::JSONObject)?.takeIf { it.optString("server") == session.baseUrl }?.optJSONObject("entries") ?: JSONObject()
            val next = JSONObject()
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!sameServer) manager.cancelAll()
            manager.createNotificationChannel(NotificationChannel("bot-updates", "伙伴消息与事项", NotificationManager.IMPORTANCE_DEFAULT))
            for (item in inbox.items) {
                val old = previous.optJSONObject(item.entryId)
                next.put(item.entryId, JSONObject().put("message", item.messageId).put("workSequence", item.workSequence))
                val role = hello.roles.find { it.id == item.entryId } ?: continue
                if (old == null || role.archived || BackgroundChecks.foreground) continue
                val read = readMarks.optJSONObject(item.entryId)
                val kind = notificationKind(item, old.optString("message"), old.optLong("workSequence"), read?.optString("message"), read?.optLong("sequence") ?: 0) ?: continue
                // Recheck session and opt-in immediately before posting; never include private message text.
                if (store.read().session?.let { it.id == session.id && it.sameAccount(session) } != true || !BackgroundChecks.enabled(context)) return Result.success()
                val intent = Intent(context, MainActivity::class.java).putExtra("bot-entry", item.entryId)
                    .setAction("com.shadow.app.OPEN.${item.entryId}").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val content = PendingIntent.getActivity(context, item.entryId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(item.entryId.hashCode(), NotificationCompat.Builder(context, "bot-updates")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Shadow")
                    .setContentText(if (kind == "message") "伙伴有新回复，打开聊天查看。" else "事项有新进展，打开聊天查看。")
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(content).build())
            }
            if (store.read().session?.let { it.id == session.id && it.sameAccount(session) } == true && BackgroundChecks.enabled(context)) store.writePrivate("notifications", JSONObject().put("server", session.baseUrl).put("epoch", hello.memoryEpoch).put("entries", next).toString())
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: com.shadow.bot.ApiFailure) {
            if (error.status in listOf(401, 403)) {
                if (store.read().session?.id == session.id) { store.clearCache(); context.getSystemService(NotificationManager::class.java).cancelAll() }
                Result.success()
            } else Result.retry()
        }
        catch (_: Exception) { Result.retry() }
    }
}

internal fun notificationKind(item: com.shadow.bot.InboxItem, previousMessage: String, previousWork: Long, readMessage: String?, readWork: Long): String? = when {
    item.messageRole == "assistant" && item.messageId.isNotEmpty() && item.messageId != previousMessage && item.messageId != readMessage -> "message"
    item.workSequence > previousWork && item.workSequence > readWork -> "work"
    else -> null
}

package com.shadow.app

import android.app.Activity
import android.content.Context
import androidx.work.*
import com.shadow.bot.*
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Endpoint capability URLs and auth keys stay in the encrypted private store. */
internal object RealtimePush {
    fun accepts(row: JSONObject, sessionId: String?, instance: String) = row.optBoolean("enabled") && !sessionId.isNullOrBlank() && row.optString("session") == sessionId && row.optString("instance") == instance
    fun state(context: Context): JSONObject = SecureStateStore(context).readPrivate("push")?.let(::JSONObject) ?: JSONObject()
    fun enabled(context: Context): Boolean = runCatching {
        val row = state(context); row.optBoolean("enabled") && row.optString("session") == SecureStateStore(context).read().session?.id
    }.getOrDefault(false)
    fun status(context: Context): String = runCatching { state(context).optString("status", "off") }.getOrDefault("error")
    private fun save(context: Context, row: JSONObject) { SecureStateStore(context).writePrivate("push", row.put("revision", state(context).optLong("revision") + 1).toString()) }
    fun enable(activity: Activity, session: Session, config: PushConfig, report: (String) -> Unit) {
        require(session.device != null && config.enabled)
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
            if (!success) { report("未找到可用的推送服务，请先安装并配置 UnifiedPush 分发器。"); return@tryUseCurrentOrDefaultDistributor }
            if (SecureStateStore(activity).read().session?.id != session.id) return@tryUseCurrentOrDefaultDistributor
            save(activity, JSONObject().put("enabled", true).put("session", session.id).put("instance", session.id).put("status", "registering").put("vapid", config.vapid))
            UnifiedPush.register(activity, instance = session.id, messageForDistributor = "Shadow 伙伴通知", vapid = config.vapid)
            report("正在连接推送服务…")
        }
    }
    fun disable(context: Context) {
        val row = state(context); val instance = row.optString("instance")
        save(context, row.put("enabled", false).put("status", "off").apply { remove("endpoint"); remove("publicKey"); remove("auth") })
        if (instance.isNotEmpty()) UnifiedPush.unregister(context, instance)
        sync(context)
        WorkManager.getInstance(context).cancelUniqueWork("bot-push-wake")
    }
    fun reconnect(context: Context) {
        val row = state(context)
        if (row.optString("session") != SecureStateStore(context).read().session?.id) { disable(context); return }
        if (!enabled(context)) return
        if (UnifiedPush.getAckDistributor(context) == null) { status(context, row.optString("instance"), "unavailable"); return }
        UnifiedPush.register(context, instance = row.getString("instance"), messageForDistributor = "Shadow 伙伴通知", vapid = row.optString("vapid").takeIf { it.isNotBlank() && it != "null" })
        sync(context)
    }
    fun endpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val row = state(context)
        if (!accepts(row, SecureStateStore(context).read().session?.id, instance)) return
        val keys = endpoint.pubKeySet ?: run { status(context, instance, "error"); return }
        save(context, row.put("endpoint", endpoint.url).put("publicKey", keys.pubKey).put("auth", keys.auth).put("status", "registering"))
        sync(context)
    }
    fun status(context: Context, instance: String, value: String) {
        val row = state(context); if (row.optString("instance") != instance) return
        save(context, row.put("status", value))
    }
    fun unregistered(context: Context, instance: String) {
        val row = state(context); if (!accepts(row, SecureStateStore(context).read().session?.id, instance)) return
        save(context, row.put("enabled", false).put("status", "unavailable").apply { remove("endpoint"); remove("publicKey"); remove("auth") }); sync(context)
    }
    fun wake(context: Context, message: PushMessage, instance: String) {
        if (!accepts(state(context), SecureStateStore(context).read().session?.id, instance) || !message.decrypted || !message.content.contentEquals("wake".toByteArray())) return
        WorkManager.getInstance(context).enqueueUniqueWork("bot-push-wake", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<BotNotificationWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
    private fun sync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("bot-push-registration", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PushRegistrationWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}

class BotPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) { runCatching { RealtimePush.endpoint(this, endpoint, instance) } }
    override fun onMessage(message: PushMessage, instance: String) { runCatching { RealtimePush.wake(this, message, instance) } }
    override fun onRegistrationFailed(reason: FailedReason, instance: String) { runCatching { RealtimePush.status(this, instance, "unavailable") } }
    override fun onUnregistered(instance: String) { runCatching { RealtimePush.unregistered(this, instance) } }
    override fun onTempUnavailable(instance: String) { runCatching { RealtimePush.status(this, instance, "unavailable") } }
}

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = SecureStateStore(applicationContext)
        val session = runCatching { store.read().session }.getOrNull() ?: return Result.success()
        val row = runCatching { RealtimePush.state(applicationContext) }.getOrNull() ?: return Result.failure()
        return try {
            if (session.device == null || row.optString("session") != session.id) return Result.success()
            val enabled = row.optBoolean("enabled")
            if (enabled && row.optString("endpoint").isBlank()) return Result.success()
            val api = authenticatedApi(applicationContext, store, session)
            // Seed the same notification ledger before the endpoint becomes active.
            BackgroundChecks.ledgerLock.withLock {
            if (enabled && store.readPrivate("notifications")?.let(::JSONObject)?.optString("session") != session.id) {
                val hello = api.handshake(); val inbox = api.inbox()
                if (hello.generation != inbox.generation) return Result.retry()
                val entries = JSONObject(); inbox.items.forEach { entries.put(it.entryId, JSONObject().put("message", it.messageId).put("workSequence", it.workSequence)) }
                if (store.read().session?.id != session.id) return Result.success()
                store.writePrivate("notifications", JSONObject().put("server", session.baseUrl).put("session", session.id).put("epoch", hello.memoryEpoch).put("entries", entries).toString())
            }
            }
            if (store.read().session?.id != session.id || RealtimePush.state(applicationContext).optLong("revision") != row.optLong("revision")) return Result.success()
            api.configurePush(if (enabled) PushSubscription(row.getString("endpoint"), row.getString("publicKey"), row.getString("auth")) else null, row.getLong("revision"))
            if (store.read().session?.id == session.id && RealtimePush.state(applicationContext).optString("revision") == row.optString("revision"))
                RealtimePush.status(applicationContext, row.optString("instance"), if (enabled) "ready" else "off")
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: ApiFailure) {
            if (error.status in setOf(400, 401, 403, 404)) {
                if (store.read().session?.id == session.id && RealtimePush.state(applicationContext).optLong("revision") == row.optLong("revision")) RealtimePush.status(applicationContext, row.optString("instance"), "error")
                Result.failure()
            }
            else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }
}

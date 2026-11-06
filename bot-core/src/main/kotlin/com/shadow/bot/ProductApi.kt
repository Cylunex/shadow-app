package com.shadow.bot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int, message: String) : IOException(message)
interface ProductApi {
    suspend fun enrollDevice(name: String): DeviceGrant = throw ApiFailure(503, "设备会话尚未启用。")
    suspend fun devices(): List<BotDevice> = throw ApiFailure(503, "设备会话尚未启用。")
    suspend fun revokeDevice(device: BotDevice) { throw ApiFailure(503, "设备会话尚未启用。") }
    suspend fun pushConfig(): PushConfig = throw ApiFailure(503, "实时通知尚未启用。")
    suspend fun configurePush(subscription: PushSubscription?, version: Long) { throw ApiFailure(503, "实时通知尚未启用。") }
    suspend fun playback(entryId: String): Playback = throw ApiFailure(503, "伙伴音色尚未启用。")
    suspend fun playbackCommand(state: Playback, action: String, messageId: String = state.messageId, positionMs: Int = state.positionMs, id: String = java.util.UUID.randomUUID().toString()): Playback = throw ApiFailure(503, "伙伴音色尚未启用。")
    suspend fun playbackAudio(state: Playback): ByteArray = throw ApiFailure(503, "伙伴音色尚未启用。")
    suspend fun browserSessions(entryId: String): BrowserSessions = throw ApiFailure(503, "当前服务尚未开放手机浏览器接手。")
    suspend fun browser(entryId: String, generation: String, sessionId: String, version: Long, action: String,
        commandId: String? = null, ref: String? = null, text: String? = null): BrowserView = throw ApiFailure(503, "浏览器接手暂不可用。")
    suspend fun commitmentPage(entryId: String, query: String = "", status: String = "", before: String? = null, ifRevision: String? = null, limit: Int = 50): CommitmentPage =
        throw ApiFailure(503, "当前服务尚未开放持续事项检索。")
    suspend fun commitments(entryId: String, ifRevision: String? = null): CommitmentPage = throw ApiFailure(503, "当前服务尚未开放持续事项管理。")
    suspend fun growth(entryId: String, ifRevision: String? = null): GrowthPage = throw ApiFailure(503, "当前服务尚未开放成长手记。")
    suspend fun workPage(entryId: String, query: String = "", status: String = "", before: String? = null, ifRevision: String? = null, limit: Int = 50): WorkPage =
        throw ApiFailure(503, "当前服务尚未开放事项检索。")
    suspend fun messageContext(entryId: String, anchor: MessageRef, epoch: Long, before: String? = null, after: String? = null, limit: Int = 30): MessageContextPage = throw ApiFailure(503, "当前服务尚未开放消息定位。")
    suspend fun uploadAttachment(id: String, entryId: String, name: String, mediaType: String, bytes: ByteArray): Attachment = throw ApiFailure(503, "附件上传暂不可用。")
    suspend fun attachmentContent(attachment: Attachment, entryId: String): ByteArray = throw ApiFailure(503, "附件暂不可用。")
    suspend fun live(entryId: String): LiveReplyState = throw ApiFailure(503, "当前服务尚未开放生成预览。")
    suspend fun history(entryId: String, before: String? = null, query: String = ""): MessagePage = throw ApiFailure(503, "当前服务尚未开放历史检索。")
    suspend fun artifactContent(artifact: Artifact, entryId: String): ByteArray = throw ApiFailure(503, "当前服务尚未开放成果下载。")
    suspend fun createRole(id: String, name: String): String
    suspend fun inbox(): Inbox
    suspend fun work(id: String, entryId: String): WorkDetail
    suspend fun artifact(id: String, entryId: String): ArtifactText
    suspend fun handshake(): Handshake
    suspend fun snapshot(entryId: String): Snapshot
    suspend fun events(after: Long): Events
    suspend fun send(message: PendingCommand, generation: String): String
    suspend fun personas(entryId: String): PersonaSettings
    suspend fun savePersona(settings: PersonaSettings): PersonaSettings
    suspend fun saveContact(role: BotRole, archived: Boolean) { throw ApiFailure(503, "当前服务尚未开放联系人归档。") }
    suspend fun memory(id: String): MemoryDetail = throw ApiFailure(503, "当前服务尚未开放记忆详情。")
    suspend fun memories(): List<MemoryItem>
    suspend fun memoryPage(query: String = "", kind: String = "", before: String? = null, ifRevision: String? = null, limit: Int = 50): MemoryPage =
        throw ApiFailure(503, "当前服务尚未开放记忆检索。")
}

fun interface BearerTokens { suspend fun token(): String }

class HttpProductApi private constructor(private val base: HttpUrl, cookies: List<String>, private val bearer: BearerTokens? = null) : ProductApi {
    private val connectionId = java.util.UUID.randomUUID().toString()
    private val jar = object : CookieJar {
        val values = cookies.mapNotNull { Cookie.parse(base, it) }.toMutableList()
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(values) {
            values.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(values) {
            for (cookie in cookies) {
                values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                values.add(cookie)
            }
        }
    }
    // Never forward credentials to redirect destinations or silently retry a POST.
    private val client = OkHttpClient.Builder().cookieJar(if (bearer == null) jar else CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            if (bearer == null) chain.proceed(request) else {
                require(request.url.scheme == base.scheme && request.url.host == base.host && request.url.port == base.port && request.url.encodedPath.startsWith(endpoint("").encodedPath))
                val token = kotlinx.coroutines.runBlocking { bearer.token() }
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").removeHeader("Cookie").build())
            }
        }
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private fun endpoint(path: String) = base.resolve("losna/agent/api/$path")!!
    private suspend fun request(path: String, body: JSONObject? = null, timeout: Long = 30): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint(path)).header("Accept", "application/json")
        if (body != null) builder.header("X-Losna-Request", "1").post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS).readTimeout(timeout, TimeUnit.SECONDS).build().newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, when (response.code) {
                401 -> "登录已过期，请重新连接。"
                403 -> "服务拒绝了当前请求，请检查入口配置。"
                404 -> "内容已不可用，可能已被移除或遗忘。"
                409 -> when {
                    path.startsWith("speech") -> "播放已转移或更新，请重新读取。"
                    path.startsWith("auth/devices") -> "设备状态已更新或数量已达上限，请刷新设备列表。"
                    path == "push" -> "通知登记已更新，请重新连接推送服务。"
                    else -> "事项已更新。请查看最新状态，再决定是否提交新的操作；原记录不会自动覆盖新版本。"
                }
                415 -> "此成果暂不支持文本预览。"
                413 -> if (path == "speech") "这段回复较长，请使用手机朗读。" else "消息过长，请缩短后发送。"
                else -> "服务暂时不可用（${response.code}）。"
            })
            if (response.body?.contentType()?.subtype != "json") throw IOException("地址未返回 Bot 数据，请检查 DSH 地址与代理前缀。")
            JSONObject(response.body!!.string())
        }
    }
    override suspend fun enrollDevice(name: String): DeviceGrant = request("auth/devices", JSONObject().put("name", name)).let { DeviceGrant(it.getString("id"), it.getString("token"), it.getLong("expiresAt")) }
    override suspend fun devices(): List<BotDevice> = request("auth/devices").getJSONArray("items").objects().map(::parseDevice)
    override suspend fun revokeDevice(device: BotDevice) { request("auth/devices/revoke", JSONObject().put("id", device.id).put("revision", device.revision)) }
    override suspend fun pushConfig(): PushConfig = request("push/config").let { PushConfig(it.getBoolean("enabled"), it.string("vapid").takeIf(String::isNotEmpty)) }
    override suspend fun configurePush(subscription: PushSubscription?, version: Long) {
        request("push", JSONObject().put("enabled", subscription != null).put("version", version).apply { subscription?.let {
            put("endpoint", it.endpoint); put("keys", JSONObject().put("p256dh", it.publicKey).put("auth", it.auth))
        } })
    }
    override suspend fun playback(entryId: String) = parsePlayback(request("speech?entryId=${encode(entryId)}", timeout = 5))
    override suspend fun playbackCommand(state: Playback, action: String, messageId: String, positionMs: Int, id: String) = parsePlayback(request("speech",
        JSONObject().put("entryId", state.entryId).put("generation", state.generation).put("revision", state.revision).put("id", id).put("action", action).put("messageId", messageId).put("positionMs", positionMs), timeout = if (action == "play") 70 else 5))
    override suspend fun playbackAudio(state: Playback): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint("speech/audio?entryId=${encode(state.entryId)}&audioId=${encode(state.audioId)}&revision=${state.revision}")).build()
        client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, "播放已转移或过期，请重新读取。")
            val body = requireNotNull(response.body)
            require(body.contentLength() in 100..5 * 1024 * 1024 && body.contentType()?.toString() == "audio/mpeg")
            val bytes = body.byteStream().use { it.readNBytes(5 * 1024 * 1024 + 1) }
            require(bytes.size <= 5 * 1024 * 1024 && sha256(bytes) == response.header("x-shadow-sha256"))
            bytes
        }
    }
    suspend fun nativeLoginConfig(): JSONObject = request("auth/native/config")
    suspend fun nativeIdentity(): JSONObject = request("auth/me")
    override suspend fun createRole(id: String, name: String): String = request("roles", JSONObject().put("id", id).put("name", name)).getString("entryId")
    override suspend fun browserSessions(entryId: String): BrowserSessions {
        val row = request("browser-sessions?entryId=${encode(entryId)}")
        return BrowserSessions(row.getString("entryId"), row.getString("generation"), row.getLong("epoch"), row.getJSONArray("items").objects().map {
            check(it.getString("state") == "handoff")
            BrowserSession(it.getString("id"), it.getString("workId"), it.getString("entryId"), it.getLong("version"), it.string("goal"))
        })
    }
    override suspend fun browser(entryId: String, generation: String, sessionId: String, version: Long, action: String,
        commandId: String?, ref: String?, text: String?): BrowserView {
        val row = request("browser/${encode(sessionId)}", JSONObject().put("entryId", entryId).put("generation", generation)
            .put("expectedVersion", version).put("action", action).put("commandId", commandId).put("ref", ref).put("text", text))
        return BrowserView(row.getString("sessionId"), row.getString("workId"), row.getString("entryId"), row.getString("generation"), row.getLong("epoch"),
            if (row.has("version")) row.getLong("version") else null, row.getString("state"), row.string("title"), row.string("text"), row.string("screenshot"),
            (row.optJSONArray("controls") ?: JSONArray()).objects().map { BrowserControl(it.getString("ref"), it.string("label"), it.getString("tag"), it.string("type"), it.optBoolean("fillable"), it.optBoolean("disabled")) })
    }
    override suspend fun commitments(entryId: String, ifRevision: String?): CommitmentPage {
        val row = request("commitments?entryId=${encode(entryId)}" + (ifRevision?.let { "&ifRevision=${encode(it)}" } ?: ""))
        return parseCommitments(row)
    }
    override suspend fun commitmentPage(entryId: String, query: String, status: String, before: String?, ifRevision: String?, limit: Int): CommitmentPage =
        parseCommitments(request("commitment-page?entryId=${encode(entryId)}&query=${encode(query)}&status=${encode(status)}&limit=$limit" +
            (before?.let { "&before=${encode(it)}" } ?: "") + (ifRevision?.let { "&ifRevision=${encode(it)}" } ?: "")))
    private fun parseCommitments(row: JSONObject): CommitmentPage {
        return CommitmentPage(row.getString("entryId"), row.getString("generation"), row.getLong("epoch"), row.getString("revision"),
            row.getJSONArray("items").objects().map { item ->
                val actions = item.getJSONArray("allowedActions")
                Commitment(item.getString("id"), item.getString("entryId"), item.getString("goal"), item.getString("state"), item.getString("kind"),
                    item.getString("completion"), item.getLong("version"), item.string("result"), item.optBoolean("resultTruncated"),
                    item.string("waitKind"), item.string("waitReason"), if (item.has("nextCheckAt") && !item.isNull("nextCheckAt")) item.getLong("nextCheckAt") else null,
                    item.string("workId").takeIf(String::isNotEmpty), (0 until actions.length()).map(actions::getString).toSet(), item.string("match"))
            }, row.getBoolean("hasMore"), row.optBoolean("unchanged"), row.string("query"), row.string("status"), row.string("next").takeIf(String::isNotEmpty), row.optInt("total", row.getJSONArray("items").length()))
    }
    override suspend fun growth(entryId: String, ifRevision: String?): GrowthPage {
        val row = request("growth?entryId=${encode(entryId)}" + (ifRevision?.let { "&ifRevision=${encode(it)}" } ?: ""))
        val persona = row.getJSONObject("persona")
        return GrowthPage(row.getString("entryId"), persona.getString("id"), persona.getString("name"),
            row.getJSONArray("habits").objects().map { GrowthHabit(it.getString("text"), it.string("quote"), it.string("scope")) },
            row.getJSONArray("views").objects().map { GrowthView(it.getString("topic"), it.getString("text"), it.string("maturity"), it.string("evidenceQuote"), it.string("sourceTitle")) },
            row.string("generation").takeIf(String::isNotEmpty), if (row.has("epoch")) row.getLong("epoch") else null,
            row.string("revision").takeIf(String::isNotEmpty), row.optBoolean("unchanged"))
    }
    override suspend fun handshake(): Handshake {
        val row = request("handshake")
        require(row.getInt("protocolVersion") == 1) { "服务协议不兼容，请更新客户端。" }
        val capabilities = row.getJSONObject("capabilities")
        return Handshake(row.getString("generation"), row.getJSONArray("conversations").objects().map {
            BotRole(it.getString("entryId"), it.getString("id"), it.getString("name"), it.string("personaId"), archived = it.optBoolean("archived"), contactVersion = it.optLong("contactVersion"))
        }, capabilities.keys().asSequence().filter { capabilities.optBoolean(it) }.toSet(), row.optLong("memoryEpoch"))
    }
    override suspend fun snapshot(entryId: String): Snapshot {
        val row = request("snapshot?entryId=${encode(entryId)}")
        return Snapshot(row.getString("generation"), row.getJSONObject("conversation").getString("id"),
            row.getJSONArray("messages").objects().map(::parseMessage),
            (row.optJSONArray("entryWorks") ?: row.getJSONArray("works")).objects().map(::parseWork), row.getLong("cursor"), row.string("historyCursor").takeIf(String::isNotEmpty), row.optLong("memoryEpoch"))
    }
    override suspend fun live(entryId: String): LiveReplyState = request("live?entryId=${encode(entryId)}").let { row ->
        LiveReplyState(row.getString("generation"), row.getString("entryId"), row.optJSONObject("reply")?.let { LiveReply(it.getString("id"), it.getString("text")) })
    }
    private fun parseMessage(it: JSONObject) = Message(it.getString("id"), it.getString("role"), it.string("text"), it.optLong("at"), it.string("personaName"), it.string("state"),
        (it.optJSONArray("attachments") ?: JSONArray()).objects().map(::parseAttachment))
    override suspend fun history(entryId: String, before: String?, query: String): MessagePage {
        val row = request("history?entryId=${encode(entryId)}&query=${encode(query)}" + (before?.let { "&before=${encode(it)}" } ?: ""))
        return MessagePage(row.getString("generation"), row.getString("entryId"), row.getLong("epoch"), row.getString("query"),
            row.getJSONArray("items").objects().map(::parseMessage), row.string("next").takeIf(String::isNotEmpty))
    }
    override suspend fun messageContext(entryId: String, anchor: MessageRef, epoch: Long, before: String?, after: String?, limit: Int): MessageContextPage {
        val row = request("message-context?entryId=${encode(entryId)}&id=${encode(anchor.id)}&role=${encode(anchor.role)}&epoch=$epoch&limit=$limit" +
            (before?.let { "&before=${encode(it)}" } ?: "") + (after?.let { "&after=${encode(it)}" } ?: ""))
        val ref = row.getJSONObject("anchor")
        return MessageContextPage(row.getString("generation"), row.getString("entryId"), row.getLong("epoch"), MessageRef(ref.getString("id"), ref.getString("role")),
            row.getJSONArray("items").objects().map(::parseMessage), row.string("before").takeIf(String::isNotEmpty), row.string("after").takeIf(String::isNotEmpty))
    }
    override suspend fun artifactContent(artifact: Artifact, entryId: String): ByteArray = withContext(Dispatchers.IO) {
        require(artifact.downloadable && artifact.size in 0..20L * 1024 * 1024 && Regex("[a-f0-9]{64}").matches(artifact.sha256))
        val request = Request.Builder().url(endpoint("artifact-content/${encode(artifact.id)}?entryId=${encode(entryId)}")).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, "成果暂不可用，请返回事项刷新。")
            val output = java.io.ByteArrayOutputStream()
            response.body!!.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size().toLong() + count <= artifact.size) { "成果大小不匹配。" }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray().also { require(it.size.toLong() == artifact.size && sha256(it) == artifact.sha256) { "成果校验失败，请重试。" } }
        }
    }
    override suspend fun events(after: Long): Events = request("events?after=$after").let {
        Events(it.getString("generation"), it.getLong("cursor"), it.getJSONArray("items").length() > 0, it.optBoolean("resetRequired"), it.optBoolean("hasMore"))
    }
    override suspend fun send(message: PendingCommand, generation: String): String {
        val body = JSONObject().put("id", message.id).put("generation", generation).put("action", message.action).put("entryId", message.entryId)
        if (message.action != "work.cancel") body.put("text", message.text)
        message.workId?.let { body.put("workId", it).put("expectedVersion", message.expectedVersion) }
        message.commitmentId?.let { body.put("commitmentId", it).put("expectedVersion", message.expectedVersion) }
        message.memoryId?.let { body.put("memoryId", it).put("expectedVersion", message.expectedVersion) }
        if (message.attachments.isNotEmpty()) body.put("attachmentIds", JSONArray(message.attachments.map { it.id }))
        val result = request("commands", body)
        check(result.optBoolean("accepted")) { "消息尚未被接收。" }
        return result.getString("sourceId")
    }
    override suspend fun inbox(): Inbox = request("inbox").let { row ->
        Inbox(row.getString("generation"), row.getJSONArray("items").objects().map {
            val last = it.optJSONObject("lastMessage")
            InboxItem(it.getString("entryId"), last?.string("text").orEmpty(), last?.optLong("at") ?: 0, it.optInt("activeWorks"), last?.string("id").orEmpty(), last?.string("role").orEmpty(), it.optLong("workSequence"))
        })
    }
    private fun parseWork(row: JSONObject) = Work(row.getString("id"), row.optString("entryId", "losna"), row.string("goal"), row.string("state"),
        row.string("result"), row.string("nextStep"), row.optLong("version"), row.optBoolean("paused"), row.string("match"))
    override suspend fun workPage(entryId: String, query: String, status: String, before: String?, ifRevision: String?, limit: Int): WorkPage {
        val row = request("work-page?entryId=${encode(entryId)}&query=${encode(query)}&status=${encode(status)}&limit=$limit" +
            (before?.let { "&before=${encode(it)}" } ?: "") + (ifRevision?.let { "&ifRevision=${encode(it)}" } ?: ""))
        return WorkPage(row.getString("generation"), row.getLong("epoch"), row.getString("entryId"), row.getString("query"), row.getString("status"),
            row.getString("revision"), row.getJSONArray("items").objects().map(::parseWork), row.string("next").takeIf(String::isNotEmpty), row.getInt("total"), row.optBoolean("unchanged"))
    }
    override suspend fun work(id: String, entryId: String): WorkDetail = request("work-detail/${encode(id)}?entryId=${encode(entryId)}").let { row ->
        val actions = row.getJSONArray("allowedActions")
        WorkDetail(parseWork(row), row.getJSONArray("plan").objects().map { PlanStep(it.string("content"), it.string("status")) },
            row.getJSONArray("runs").objects().map { WorkRun(it.getString("id"), it.string("state"), it.optLong("startedAt"), it.optLong("finishedAt"), it.string("result"), it.optBoolean("resultTruncated")) },
            row.getJSONArray("artifacts").objects().map { Artifact(it.getString("id"), it.string("name"), it.optLong("size"), it.string("type"), it.optBoolean("textPreview"), it.string("sha256"), it.optBoolean("downloadable")) },
            (0 until actions.length()).map(actions::getString).toSet(), row.optBoolean("resultTruncated"), row.optBoolean("planTruncated"))
    }
    override suspend fun artifact(id: String, entryId: String): ArtifactText = request("artifact-text/${encode(id)}?entryId=${encode(entryId)}").let {
        ArtifactText(it.getString("id"), it.getString("workId"), it.getString("name"), it.getString("text"), it.getBoolean("truncated"))
    }
    override suspend fun personas(entryId: String) = parsePersona(request("persona?entryId=${encode(entryId)}"))
    override suspend fun savePersona(settings: PersonaSettings): PersonaSettings = parsePersona(request("persona", JSONObject()
        .put("entryId", settings.entryId).put("revision", settings.revision).put("active", settings.active).put("contactEnabled", settings.contactEnabled)
        .put("cards", JSONArray(settings.cards.map { JSONObject().put("id", it.id).put("name", it.name).put("displayName", it.displayName)
            .put("instructions", it.instructions).put("inheritIdentity", it.inheritIdentity) }))))
    override suspend fun memories(): List<MemoryItem> = request("memories").getJSONArray("items").objects().map {
        MemoryItem(it.getString("id"), it.getString("text"), it.getString("kind"), it.optLong("version"))
    }
    override suspend fun memoryPage(query: String, kind: String, before: String?, ifRevision: String?, limit: Int): MemoryPage {
        val row = request("memories?query=${encode(query)}&kind=${encode(kind)}&limit=$limit" +
            (before?.let { "&before=${encode(it)}" } ?: "") + (ifRevision?.let { "&ifRevision=${encode(it)}" } ?: ""))
        return MemoryPage(row.getString("generation"), row.getLong("epoch"), row.getString("query"), row.getString("kind"),
            row.getString("revision"), row.getJSONArray("items").objects().map {
                MemoryItem(it.getString("id"), it.getString("text"), it.getString("kind"), it.getLong("version"))
            }, row.string("next").takeIf(String::isNotEmpty), row.optBoolean("unchanged"))
    }
    override suspend fun memory(id: String): MemoryDetail = request("memory/${encode(id)}").let { row ->
        MemoryDetail(MemoryItem(row.getString("id"), row.getString("text"), row.getString("kind"), row.getLong("version")),
            row.getJSONArray("sources").objects().map { MemorySource(it.getString("id"), it.string("text"), it.string("at"), it.optBoolean("truncated")) })
    }
    override suspend fun saveContact(role: BotRole, archived: Boolean) {
        request("contact", JSONObject().put("entryId", role.id).put("version", role.contactVersion).put("archived", archived))
    }
    private fun parsePersona(row: JSONObject) = PersonaSettings(row.getString("entryId"), row.getInt("revision"), row.getString("active"), row.getBoolean("contactEnabled"),
        row.getJSONArray("cards").objects().map { PersonaCard(it.getString("id"), it.getString("name"), it.optString("displayName", row.string("baseName")), it.getString("instructions"), it.optBoolean("inheritIdentity", true)) })
    fun session() = Session(base.toString(), synchronized(jar.values) { jar.values.map(Cookie::toString) }, id = connectionId)
    override suspend fun uploadAttachment(id: String, entryId: String, name: String, mediaType: String, bytes: ByteArray): Attachment = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "单个附件不能超过 20 MiB。" }
        val url = endpoint("attachments").newBuilder().addQueryParameter("id", id).addQueryParameter("entryId", entryId).addQueryParameter("name", name).build()
        val request = Request.Builder().url(url).header("X-Losna-Request", "1").post(bytes.toRequestBody(mediaType.toMediaType())).build()
        client.newBuilder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, if (response.code == 401) "登录已过期，请重新连接后重试附件。" else "附件上传未完成（${response.code}），请重试。")
            val receipt = parseAttachment(JSONObject(response.body!!.string()))
            require(receipt.id == id && receipt.name == name && receipt.bytes == bytes.size.toLong() && receipt.sha256 == sha256(bytes)) { "附件回执不匹配，请重试原附件。" }
            receipt
        }
    }
    override suspend fun attachmentContent(attachment: Attachment, entryId: String): ByteArray = withContext(Dispatchers.IO) {
        require(attachment.bytes in 1..20L * 1024 * 1024)
        val request = Request.Builder().url(endpoint("attachments/${encode(attachment.id)}?entryId=${encode(entryId)}")).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, "附件暂不可用，请返回聊天刷新。")
            val bytes = response.body!!.byteStream().use(::readAttachmentBytes)
            require(bytes.size.toLong() == attachment.bytes && sha256(bytes) == attachment.sha256) { "附件内容不完整，请重试。" }
            bytes
        }
    }
    suspend fun login(token: String) = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "请输入当前 DSH 启动口令。" }
        val url = base.newBuilder().addQueryParameter("token", token.trim()).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code !in listOf(302, 303) || jar.loadForRequest(endpoint("handshake")).none { it.name.startsWith("dsh-auth-") })
                throw ApiFailure(response.code, "登录未成功，请检查 DSH 地址和当前启动口令。")
        }
    }
    companion object {
        fun normalizeBase(value: String): String {
            val url = value.trim().toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "请填写不含口令或参数的 HTTPS DSH 地址。" }
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString()
        }
        fun from(session: Session, bearer: BearerTokens? = null): HttpProductApi {
            val effective = session.device?.let { device -> BearerTokens { device.token } } ?: bearer
            require(session.oidc == null || effective != null) { "统一登录需要有效的令牌提供方。" }
            return HttpProductApi(normalizeBase(session.baseUrl).toHttpUrl(), if (effective != null) emptyList() else session.cookies, effective)
        }
        // Only synthetic loopback test fixtures bypass the production HTTPS requirement.
        internal fun fixture(base: HttpUrl, bearer: BearerTokens? = null) = HttpProductApi(base, emptyList(), bearer)
        private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

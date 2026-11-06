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
    suspend fun memories(): List<MemoryItem>
}

class HttpProductApi private constructor(private val base: HttpUrl, cookies: List<String>) : ProductApi {
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
    private val client = OkHttpClient.Builder().cookieJar(jar).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private fun endpoint(path: String) = base.resolve("losna/agent/api/$path")!!
    private suspend fun request(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint(path)).header("Accept", "application/json")
        if (body != null) builder.header("X-Losna-Request", "1").post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, when (response.code) {
                401 -> "登录已过期，请重新连接。"
                403 -> "服务拒绝了当前请求，请检查入口配置。"
                404 -> "内容已不可用，可能已被移除或遗忘。"
                409 -> "事项已更新。请查看最新状态，再决定是否提交新的操作；原记录不会自动覆盖新版本。"
                415 -> "此成果暂不支持文本预览。"
                413 -> "消息过长，请缩短后发送。"
                else -> "服务暂时不可用（${response.code}）。"
            })
            if (response.body?.contentType()?.subtype != "json") throw IOException("地址未返回 Bot 数据，请检查 DSH 地址与代理前缀。")
            JSONObject(response.body!!.string())
        }
    }
    override suspend fun createRole(id: String, name: String): String = request("roles", JSONObject().put("id", id).put("name", name)).getString("entryId")
    override suspend fun handshake(): Handshake {
        val row = request("handshake")
        require(row.getInt("protocolVersion") == 1) { "服务协议不兼容，请更新客户端。" }
        val capabilities = row.getJSONObject("capabilities")
        return Handshake(row.getString("generation"), row.getJSONArray("conversations").objects().map {
            BotRole(it.getString("entryId"), it.getString("id"), it.getString("name"), it.string("personaId"))
        }, capabilities.keys().asSequence().filter { capabilities.optBoolean(it) }.toSet())
    }
    override suspend fun snapshot(entryId: String): Snapshot {
        val row = request("snapshot?entryId=${encode(entryId)}")
        return Snapshot(row.getString("generation"), row.getJSONObject("conversation").getString("id"),
            row.getJSONArray("messages").objects().map { Message(it.getString("id"), it.getString("role"), it.string("text"), it.optLong("at"), it.string("personaName"), it.string("state")) },
            (row.optJSONArray("entryWorks") ?: row.getJSONArray("works")).objects().map(::parseWork), row.getLong("cursor"))
    }
    override suspend fun events(after: Long): Events = request("events?after=$after").let {
        Events(it.getString("generation"), it.getLong("cursor"), it.getJSONArray("items").length() > 0, it.optBoolean("resetRequired"), it.optBoolean("hasMore"))
    }
    override suspend fun send(message: PendingCommand, generation: String): String {
        val body = JSONObject().put("id", message.id).put("generation", generation).put("action", message.action).put("entryId", message.entryId)
        if (message.action != "work.cancel") body.put("text", message.text)
        message.workId?.let { body.put("workId", it).put("expectedVersion", message.expectedVersion) }
        val result = request("commands", body)
        check(result.optBoolean("accepted")) { "消息尚未被接收。" }
        return result.getString("sourceId")
    }
    override suspend fun inbox(): Inbox = request("inbox").let { row ->
        Inbox(row.getString("generation"), row.getJSONArray("items").objects().map {
            val last = it.optJSONObject("lastMessage")
            InboxItem(it.getString("entryId"), last?.string("text").orEmpty(), last?.optLong("at") ?: 0, it.optInt("activeWorks"))
        })
    }
    private fun parseWork(row: JSONObject) = Work(row.getString("id"), row.optString("entryId", "losna"), row.string("goal"), row.string("state"),
        row.string("result"), row.string("nextStep"), row.optLong("version"), row.optBoolean("paused"))
    override suspend fun work(id: String, entryId: String): WorkDetail = request("work-detail/${encode(id)}?entryId=${encode(entryId)}").let { row ->
        val actions = row.getJSONArray("allowedActions")
        WorkDetail(parseWork(row), row.getJSONArray("plan").objects().map { PlanStep(it.string("content"), it.string("status")) },
            row.getJSONArray("runs").objects().map { WorkRun(it.getString("id"), it.string("state"), it.optLong("startedAt"), it.optLong("finishedAt"), it.string("result"), it.optBoolean("resultTruncated")) },
            row.getJSONArray("artifacts").objects().map { Artifact(it.getString("id"), it.string("name"), it.optLong("size"), it.string("type"), it.optBoolean("textPreview")) },
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
        MemoryItem(it.getString("id"), it.getString("text"), it.getString("kind"))
    }
    private fun parsePersona(row: JSONObject) = PersonaSettings(row.getString("entryId"), row.getInt("revision"), row.getString("active"), row.getBoolean("contactEnabled"),
        row.getJSONArray("cards").objects().map { PersonaCard(it.getString("id"), it.getString("name"), it.optString("displayName", row.string("baseName")), it.getString("instructions"), it.optBoolean("inheritIdentity", true)) })
    fun session() = Session(base.toString(), synchronized(jar.values) { jar.values.map(Cookie::toString) })
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
        fun from(session: Session) = HttpProductApi(normalizeBase(session.baseUrl).toHttpUrl(), session.cookies)
        // Only synthetic loopback test fixtures bypass the production HTTPS requirement.
        internal fun fixture(base: HttpUrl) = HttpProductApi(base, emptyList())
        private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
    }
}

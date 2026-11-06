package com.shadow.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shadow.bot.*
import kotlinx.coroutines.*
import net.openid.appauth.*
import java.util.UUID
import org.json.JSONObject

/** This Activity saves only an attempt ID; AppAuth owns its transient browser/PKCE state. */
class OidcLoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var service: AuthorizationService
    private lateinit var store: SecureStateStore
    private lateinit var status: TextView
    private var attemptId = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = SecureStateStore(this); service = AuthorizationService(this, strictAuthConfiguration())
        status = TextView(this).apply { text = "正在打开统一登录…"; setPadding(32, 48, 32, 32); setTextColor(android.graphics.Color.WHITE) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.rgb(16, 16, 16)); addView(status)
            addView(Button(this@OidcLoginActivity).apply { text = "返回"; setOnClickListener { cancelAttempt(); finish() } })
        })
        attemptId = savedInstanceState?.getString("attempt").orEmpty()
        if (savedInstanceState != null) {
            if (pending() == null) failed("登录已中断，请返回重新登录。")
            return
        }
        scope.launch {
            try {
                val base = HttpProductApi.normalizeBase(requireNotNull(intent.getStringExtra("base")))
                val current = store.read()
                require(current.pending.isEmpty() || current.session?.baseUrl == base) { "请先处理原服务的待发记录。" }
                val config = NativeLoginConfig.parse(base, HttpProductApi.from(Session(base, emptyList())).nativeLoginConfig())
                AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(config.issuer), { discovery, error ->
                    if (isFinishing || isDestroyed) return@fetchFromIssuer
                    try {
                        require(discovery != null && error == null) { "无法连接统一认证，请稍后重试。" }
                        val document = requireNotNull(discovery.discoveryDoc).docJson
                        require(document.getString("issuer") == config.issuer)
                        for (endpoint in listOf(discovery.authorizationEndpoint, requireNotNull(discovery.tokenEndpoint))) {
                            val parsed = java.net.URI(endpoint.toString()); val issuer = java.net.URI(config.issuer)
                            require(parsed.scheme == "https" && parsed.rawUserInfo == null && parsed.host == issuer.host && parsed.port == issuer.port && parsed.fragment == null)
                        }
                        require(document.optJSONArray("code_challenge_methods_supported")?.let { (0 until it.length()).any { i -> it.getString(i) == "S256" } } == true)
                        require(!document.optBoolean("require_pushed_authorization_requests")) { "当前认证要求暂不支持的登录方式。" }
                        val request = AuthorizationRequest.Builder(discovery, config.clientId, ResponseTypeValues.CODE, Uri.parse(OIDC_REDIRECT))
                            .setScope("openid profile offline_access").setAdditionalParameters(mapOf("resource" to config.resource)).build()
                        check(request.codeVerifier != null && request.nonce != null && request.state != null)
                        attemptId = UUID.randomUUID().toString()
                        store.writePrivate("oidc-login", JSONObject().put("id", attemptId).put("created", System.currentTimeMillis())
                            .put("previous", store.read().session?.id).put("base", config.base).put("issuer", config.issuer)
                            .put("request", request.jsonSerializeString()).toString())
                        status.text = "请在浏览器完成登录，完成后会自动返回。"
                        @Suppress("DEPRECATION")
                        startActivityForResult(service.getAuthorizationRequestIntent(request), 110)
                    } catch (failure: Exception) { failed((failure as? IllegalArgumentException)?.message ?: "无法开始统一登录，请稍后重试。") }
                }, OidcConnections)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failed(when {
                error is ApiFailure && error.status in setOf(401, 404) -> "当前服务尚未启用统一登录，可返回使用口令连接。"
                error is IllegalArgumentException -> error.message ?: "登录地址无效。"
                else -> "统一认证暂不可用，请检查网络后重试。"
            }) }
        }
    }
    private fun pending(): JSONObject? = runCatching { validLoginAttempt(store.readPrivate("oidc-login"), attemptId, System.currentTimeMillis()) }.getOrNull()
    private fun cancelAttempt() {
        runCatching { if (store.readPrivate("oidc-login")?.let(::JSONObject)?.optString("id") == attemptId) store.writePrivate("oidc-login", "{}") }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("attempt", attemptId); super.onSaveInstanceState(outState) }
    @Deprecated("AppAuth uses the platform activity result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 110) return
        try {
            val attempt = pending() ?: error("Expired attempt")
            store.writePrivate("oidc-login", attempt.put("consumed", true).toString())
            val response = data?.let(AuthorizationResponse::fromIntent)
            if (response == null) { cancelAttempt(); setResult(RESULT_CANCELED); finish(); return }
            val expected = AuthorizationRequest.jsonDeserialize(attempt.getString("request"))
            validateLoginResponse(expected, response)
            val auth = AuthState(response, null)
            status.text = "正在连接伙伴…"
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, error ->
                if (isFinishing || isDestroyed) return@performTokenRequest
                if (tokens == null || error != null || tokens.accessToken == null) { failed("登录凭据未能取得，请返回重试。"); return@performTokenRequest }
                auth.update(tokens, error)
                scope.launch {
                    try {
                        val base = attempt.getString("base")
                        val provisional = Session(base, emptyList(), OidcSession(attempt.getString("issuer"), "", expected.clientId, "", auth.jsonSerializeString()), UUID.randomUUID().toString())
                        val api = HttpProductApi.from(provisional, BearerTokens { requireNotNull(auth.accessToken) })
                        val identity = api.nativeIdentity()
                        check(identity.getString("issuer") == provisional.oidc!!.issuer && identity.getString("client_id") == expected.clientId && identity.getString("sub").isNotBlank())
                        api.handshake()
                        val session = provisional.copy(oidc = requireNotNull(provisional.oidc).copy(subject = identity.getString("sub"), name = identity.optString("name", "本人")))
                        var keepData = false
                        check(store.readPrivate("oidc-login")?.let(::JSONObject)?.optString("id") == attemptId)
                        store.update { current ->
                            check((current.session?.id ?: "") == attempt.optString("previous", "")) { "连接已变更，请重新登录。" }
                            keepData = current.session?.canKeepData(session) == true
                            require(current.pending.isEmpty() || keepData) { "请使用原账号登录并处理待发记录后，再切换账号。" }
                            if (keepData) current.copy(session = session) else SavedState(session = session)
                        }
                        if (!keepData) {
                            store.clearCache(); store.writePrivate("notifications", "{}"); store.writePrivate("read-marks", "{}")
                            java.io.File(cacheDir, "shared-results").deleteRecursively()
                        }
                        store.pruneDraftFiles(); cancelAttempt()
                        setResult(RESULT_OK); finish()
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { failed(when {
                        error is ApiFailure && error.status == 403 -> "此账号未获准访问当前伙伴，请使用已绑定的本人账号。"
                        error is IllegalArgumentException -> error.message ?: "无法保存登录。"
                        else -> "登录尚未完成，请检查网络后重试。"
                    }) }
                }
            }
        } catch (_: Exception) { failed("登录请求已失效，请返回重新登录。") }
    }
    private fun failed(message: String) { cancelAttempt(); status.text = message }
    override fun onDestroy() { if (isFinishing) cancelAttempt(); scope.cancel(); service.dispose(); super.onDestroy() }
}

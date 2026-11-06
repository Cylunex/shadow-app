package com.shadow.bot

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

const val OIDC_REDIRECT = "com.shadow.app:/oauth2redirect"
data class NativeLoginConfig(val base: String, val issuer: String, val clientId: String, val resource: String) {
    companion object {
        fun parse(base: String, json: JSONObject): NativeLoginConfig {
            val normalized = HttpProductApi.normalizeBase(base)
            val issuer = json.getString("issuer"); val url = issuer.toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            require(json.getString("base_url") == normalized && json.getString("resource") == normalized.toHttpUrl().resolve("losna/agent/api").toString()) { "登录配置与当前服务地址不匹配。" }
            require(json.getString("redirect_uri") == OIDC_REDIRECT && json.getString("client_id").isNotBlank()) { "服务尚未配置手机统一登录。" }
            return NativeLoginConfig(normalized, issuer, json.getString("client_id"), json.getString("resource"))
        }
    }
}

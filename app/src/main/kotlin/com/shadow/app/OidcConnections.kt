package com.shadow.app

import android.net.Uri
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.connectivity.ConnectionBuilder
import java.net.HttpURLConnection
import java.net.URL

internal object OidcConnections : ConnectionBuilder {
    override fun openConnection(uri: Uri): HttpURLConnection {
        val parsed = java.net.URI(uri.toString())
        require(parsed.scheme == "https" && parsed.rawUserInfo == null && parsed.fragment == null) { "统一认证需要 HTTPS。" }
        return (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 15000; readTimeout = 15000
        }
    }
}
internal fun strictAuthConfiguration() = AppAuthConfiguration.Builder().setConnectionBuilder(OidcConnections).build()

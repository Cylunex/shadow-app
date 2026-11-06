package com.shadow.app

import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import org.json.JSONObject

internal fun validLoginAttempt(raw: String?, id: String, now: Long): JSONObject? = runCatching {
    raw?.let(::JSONObject)?.takeIf { id.isNotBlank() && it.optString("id") == id && !it.optBoolean("consumed") && now - it.optLong("created") in 0..600_000 }
}.getOrNull()
internal fun validateLoginResponse(expected: AuthorizationRequest, response: AuthorizationResponse) {
    check(expected.state != null && expected.nonce != null && expected.codeVerifier != null)
    check(response.state == expected.state && response.request.jsonSerializeString() == expected.jsonSerializeString())
}

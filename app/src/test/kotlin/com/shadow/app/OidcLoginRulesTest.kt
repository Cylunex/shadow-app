package com.shadow.app

import android.net.Uri
import com.shadow.bot.OIDC_REDIRECT
import net.openid.appauth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OidcLoginRulesTest {
    private fun request() = AuthorizationRequest.Builder(
        AuthorizationServiceConfiguration(Uri.parse("https://auth.example.com/authorize"), Uri.parse("https://auth.example.com/token")),
        "shadow-bot-native", ResponseTypeValues.CODE, Uri.parse(OIDC_REDIRECT))
        .setScope("openid profile offline_access").setAdditionalParameters(mapOf("resource" to "https://example.com/dsh/losna/agent/api")).build()

    @Test fun appAuthUsesRandomStateNonceAndS256WithoutAClientSecret() {
        val first = request(); val second = request()
        assertNotEquals(first.state, second.state); assertNotEquals(first.nonce, second.nonce)
        assertEquals("S256", first.codeVerifierChallengeMethod)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(first.codeVerifier!!.toByteArray(Charsets.US_ASCII))), first.codeVerifierChallenge)
        assertEquals("code", first.responseType); assertEquals(OIDC_REDIRECT, first.redirectUri.toString())
        assertFalse(first.toUri().toString().contains(first.codeVerifier!!))
        assertFalse(first.toUri().queryParameterNames.contains("client_secret"))
    }
    @Test fun callbackCannotSwapStateOrPersistedRequest() {
        val expected = request()
        validateLoginResponse(expected, AuthorizationResponse.Builder(expected).setState(expected.state).setAuthorizationCode("one-use-code").build())
        assertThrows(IllegalStateException::class.java) { validateLoginResponse(expected, AuthorizationResponse.Builder(expected).setState("wrong").build()) }
        assertThrows(IllegalStateException::class.java) { validateLoginResponse(expected, AuthorizationResponse.Builder(request()).setState(expected.state).build()) }
    }
    @Test fun abandonedConsumedExpiredAndFutureAttemptsAreRejected() {
        val raw = JSONObject().put("id", "one").put("created", 1000)
        assertNotNull(validLoginAttempt(raw.toString(), "one", 2000))
        assertNull(validLoginAttempt(raw.toString(), "other", 2000))
        assertNull(validLoginAttempt(raw.toString(), "one", 999))
        assertNull(validLoginAttempt(raw.toString(), "one", 601001))
        assertNull(validLoginAttempt(raw.put("consumed", true).toString(), "one", 2000))
        assertNull(validLoginAttempt("invalid", "one", 2000))
    }
}

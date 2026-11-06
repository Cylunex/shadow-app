package com.shadow.bot

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NativeLoginTest {
    private val session = Session("https://example.com/dsh/", emptyList(), OidcSession("https://auth.example.com", "owner", "bot", "本人", "refresh-one"), "login-one")
    private class Store(var value: SavedState) : StateStore {
        override fun read() = value
        override fun write(state: SavedState) { value = state }
    }
    @Test fun managedGrantRoundTripsWithoutUpstreamCredentialsOrIdentityLoss() {
        val session = Session("https://example.com/", emptyList(), OidcSession("https://auth.example.com", "owner", "bot", "本人", ""), "connection", DeviceGrant("device", "synthetic-grant", 12345))
        val saved = SavedState(session = session, drafts = mapOf("losna" to "synthetic draft"))
        assertEquals(saved, StateCodec.decode(StateCodec.encode(saved)))
        assertTrue(session.sameAccount(session.copy(device = null)))
        assertTrue(StateCodec.encode(saved).contains("synthetic-grant")) // The enclosing SecureStateStore encrypts this document.
    }

    @Test fun discoveryCannotMoveTheApiOrCallbackAndOldCookiesStillDecode() {
        val config = JSONObject().put("base_url", session.baseUrl).put("issuer", session.oidc!!.issuer).put("client_id", "bot")
            .put("resource", "https://example.com/dsh/losna/agent/api").put("redirect_uri", OIDC_REDIRECT)
        assertEquals("bot", NativeLoginConfig.parse(session.baseUrl, config).clientId)
        for ((key, value) in listOf("resource" to "https://other.example.com/api", "base_url" to "https://other.example.com/", "redirect_uri" to "another:/callback", "issuer" to "http://auth.example.com", "issuer" to "https://me:secret@auth.example.com")) {
            val changed = JSONObject(config.toString()).put(key, value)
            assertThrows(IllegalArgumentException::class.java) { NativeLoginConfig.parse(session.baseUrl, changed) }
        }
        val old = """{"version":4,"session":{"baseUrl":"https://example.com/","cookies":["synthetic=one"]},"pending":[]}"""
        assertNull(StateCodec.decode(old).session!!.oidc)
        val saved = SavedState(session, drafts = mapOf("losna" to "保留草稿"))
        assertEquals(saved, StateCodec.decode(StateCodec.encode(saved)))
        assertFalse(session.sameAccount(session.copy(oidc = session.oidc.copy(subject = "other"))))
        assertTrue(Session(session.baseUrl, emptyList()).canKeepData(session))
        assertFalse(session.canKeepData(Session(session.baseUrl, emptyList())))
    }
    @Test fun refreshCommitsBeforeUseAndPreservesChangesMadeWhileWaiting() = runBlocking<Unit> {
        val store = Store(SavedState(session)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val provider = RefreshingBearerTokens(store, session) { raw ->
            assertEquals("refresh-one", raw); entered.complete(Unit); release.await(); FreshBearer("access-two", "refresh-two")
        }
        val request = async { provider.token() }; entered.await()
        val command = PendingCommand("message", "losna", "hello", 1)
        store.value = store.value.copy(pending = listOf(command), drafts = mapOf("doll" to "keep"))
        release.complete(Unit); assertEquals("access-two", request.await())
        assertEquals("refresh-two", store.value.session!!.oidc!!.authState)
        assertEquals(listOf(command), store.value.pending); assertEquals("keep", store.value.drafts["doll"])
        val second = RefreshingBearerTokens(store, session) { raw -> assertEquals("refresh-two", raw); FreshBearer("access-two", raw) }
        assertEquals("access-two", second.token())
    }
    @Test fun transientFailureRetainsSessionAndPermanentFailureRequiresLoginWithoutDroppingPending() = runBlocking<Unit> {
        val command = PendingCommand("message", "losna", "hello", 1)
        val store = Store(SavedState(session, listOf(command)))
        try { RefreshingBearerTokens(store, session) { throw IOException("offline") }.token(); fail() } catch (_: IOException) {}
        assertEquals(session, store.value.session)
        try { RefreshingBearerTokens(store, session) { throw ApiFailure(401, "invalid_grant") }.token(); fail() } catch (error: ApiFailure) { assertEquals(401, error.status) }
        assertEquals("", store.value.session!!.oidc!!.authState); assertEquals(listOf(command), store.value.pending)
        try { RefreshingBearerTokens(store, session) { error("must not refresh an invalid grant") }.token(); fail() } catch (error: ApiFailure) { assertEquals(401, error.status) }
    }
    @Test fun cancelledRefreshFinishesRotationButCannotResurrectASignedOutSession() = runBlocking<Unit> {
        for (signOut in listOf(false, true)) {
            val store = Store(SavedState(session)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val provider = RefreshingBearerTokens(store, session) { entered.complete(Unit); release.await(); FreshBearer("access-two", "refresh-two") }
            val request = launch { try { provider.token() } catch (_: ApiFailure) {} }
            entered.await(); request.cancel()
            if (signOut) store.value = SavedState()
            release.complete(Unit); request.join()
            if (signOut) assertNull(store.value.session) else assertEquals("refresh-two", store.value.session!!.oidc!!.authState)
        }
    }
    @Test fun parallelClientsReadTheRotatedStateAndDiskFailureNeverReleasesAToken() = runBlocking<Unit> {
        val store = Store(SavedState(session)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async { RefreshingBearerTokens(store, session) { entered.complete(Unit); release.await(); FreshBearer("first", "rotated") }.token() }
        entered.await()
        val second = async { RefreshingBearerTokens(store, session) { raw -> assertEquals("rotated", raw); FreshBearer("second", raw) }.token() }
        release.complete(Unit); assertEquals("first", first.await()); assertEquals("second", second.await())
        val broken = object : StateStore {
            override fun read() = SavedState(session)
            override fun write(state: SavedState) { throw IOException("disk unavailable") }
        }
        try { RefreshingBearerTokens(broken, session) { FreshBearer("must not escape", "rotated") }.token(); fail() } catch (_: IOException) {}
    }

}

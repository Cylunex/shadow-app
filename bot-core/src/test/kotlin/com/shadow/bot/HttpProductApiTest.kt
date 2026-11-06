package com.shadow.bot

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HttpProductApiTest {
    @Test fun mountsHeadersAndCommandPayloadMatchProductProtocol() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"accepted":true,"sourceId":"source-one"}"""))
            val api = HttpProductApi.fixture(server.url("/dsh/"))
            assertEquals("source-one", api.send(PendingCommand("command-one", "doll", "中文正文", 123), "host-one"))
            val request = server.takeRequest()
            assertEquals("/dsh/losna/agent/api/commands", request.path)
            assertEquals("1", request.getHeader("X-Losna-Request"))
            val body = org.json.JSONObject(request.body.readUtf8())
            assertEquals("doll", body.getString("entryId")); assertEquals("command-one", body.getString("id"))
            assertEquals("host-one", body.getString("generation")); assertEquals("中文正文", body.getString("text"))
        }
    }
    @Test fun loginPersistsOnlyCookieAndRedirectsAreNeverFollowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example.com/")
                .setHeader("Set-Cookie", "dsh-auth-test=signed; Path=/dsh/; HttpOnly; Max-Age=3600"))
            val api = HttpProductApi.fixture(server.url("/dsh/"))
            api.login("synthetic-token")
            assertEquals(1, server.requestCount)
            assertFalse(StateCodec.encode(SavedState(api.session())).contains("synthetic-token"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"protocolVersion":1,"generation":"host","capabilities":{"chat":true},"conversations":[]}"""))
            api.handshake(); server.takeRequest()
            assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("dsh-auth-test=signed"))
        }
    }
    @Test fun rejectsInsecureCredentialBearingAndUnsupportedEndpoints() = runBlocking {
        for (url in listOf("http://example.com", "https://me:secret@example.com", "https://example.com/?token=x", "https://example.com/#x")) {
            assertThrows(IllegalArgumentException::class.java) { HttpProductApi.normalizeBase(url) }
        }
        assertEquals("https://example.com/dsh/", HttpProductApi.normalizeBase("https://example.com/dsh"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private response"))
            try { HttpProductApi.fixture(server.url("/")).handshake(); fail("must reject") }
            catch (error: ApiFailure) { assertEquals(401, error.status); assertFalse(error.message!!.contains("private response")) }
        }
    }
}

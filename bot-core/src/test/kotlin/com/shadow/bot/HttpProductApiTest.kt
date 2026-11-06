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
    @Test fun attachmentUploadAndDownloadValidateBytesAndSendOnlyReferences() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val bytes = "original document".toByteArray()
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val value = Attachment("upload-001", "文档.pdf", "application/pdf", bytes.size.toLong(), sha, "assetv_document")
            val json = attachmentJson(value).toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))
            val api = HttpProductApi.fixture(server.url("/dsh/"))
            assertEquals(value, api.uploadAttachment(value.id, "doll", value.name, value.mediaType, bytes))
            val upload = server.takeRequest()
            assertEquals("1", upload.getHeader("X-Losna-Request"))
            assertEquals("application/pdf", upload.getHeader("Content-Type"))
            assertEquals("doll", upload.requestUrl!!.queryParameter("entryId"))
            assertArrayEquals(bytes, upload.body.readByteArray())
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            assertArrayEquals(bytes, api.attachmentContent(value, "doll")); server.takeRequest()
            server.enqueue(MockResponse().setBody("corrupt"))
            try { api.attachmentContent(value, "doll"); fail("must reject corrupted content") } catch (_: IllegalArgumentException) { }
            server.takeRequest()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"accepted":true,"sourceId":"source"}"""))
            api.send(PendingCommand("message-file", "doll", "", 1, attachments = listOf(value)), "host")
            val command = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(value.id, command.getJSONArray("attachmentIds").getString(0))
            assertFalse(command.has("attachments")); assertFalse(command.toString().contains("assetv_document"))
        }
    }

    @Test fun artifactDownloadChecksSizeDigestAndRejectsWithdrawal() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val api = HttpProductApi.fixture(server.url("/dsh/"))
            val empty = Artifact("empty-file", "empty.txt", 0, "text/plain", true,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", true)
            server.enqueue(MockResponse().setBody("")); assertArrayEquals(byteArrayOf(), api.artifactContent(empty, "losna"))
            assertEquals("/dsh/losna/agent/api/artifact-content/empty-file?entryId=losna", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("unexpected"))
            try { api.artifactContent(empty, "losna"); fail("oversized content") } catch (_: IllegalArgumentException) { }
            server.takeRequest(); server.enqueue(MockResponse().setResponseCode(404))
            try { api.artifactContent(empty, "losna"); fail("withdrawn") } catch (error: ApiFailure) { assertEquals(404, error.status) }
        }
    }
    @Test fun contextEncodesAnchorAndDirectionWithoutChangingMessageIdentity() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"generation":"host","entryId":"doll","epoch":7,"anchor":{"id":"a+/中文","role":"user"},"before":null,"after":"next","items":[{"id":"a+/中文","role":"user","text":"上下文","at":3}]}"""))
            val page = HttpProductApi.fixture(server.url("/dsh/")).messageContext("doll", MessageRef("a+/中文", "user"), 7, before = "opaque+/=", limit = 5)
            val request = server.takeRequest(); val url = request.requestUrl!!
            assertEquals("/dsh/losna/agent/api/message-context", url.encodedPath)
            assertEquals("a+/中文", url.queryParameter("id")); assertEquals("opaque+/=", url.queryParameter("before"))
            assertNull(url.queryParameter("after")); assertEquals("7", url.queryParameter("epoch")); assertEquals("5", url.queryParameter("limit"))
            assertEquals(page.anchor.id, page.items.single().id); assertEquals("next", page.after); assertNull(page.before)
        }
    }

}

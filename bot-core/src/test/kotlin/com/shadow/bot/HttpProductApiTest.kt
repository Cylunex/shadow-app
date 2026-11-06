package com.shadow.bot

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HttpProductApiTest {
    @Test fun commitmentsUseScopedConditionalReadAndOriginalCommandTarget() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpProductApi.fixture(server.url("/proxy/"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"entryId":"losna","generation":"host","epoch":2,"revision":"rev","hasMore":true,"items":[{"id":"c","entryId":"losna","goal":"阅读目标","state":"waiting","kind":"commitment","completion":"user_judgment","version":7,"result":"第一篇完成","resultTruncated":true,"waitKind":"user_input","waitReason":"等待材料","nextCheckAt":1234,"workId":"w","allowedActions":["commitment.pause","commitment.complete"]}]}"""))
            val page = api.commitments("losna", "old")
            assertEquals("/proxy/losna/agent/api/commitments?entryId=losna&ifRevision=old", server.takeRequest().path)
            assertTrue(page.hasMore); assertEquals(1234L, page.items.single().nextCheckAt); assertTrue(page.items.single().resultTruncated)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"accepted":true,"sourceId":"s"}"""))
            api.send(PendingCommand("command-one", "losna", "", 1, action = "commitment.pause", commitmentId = "c", expectedVersion = 7), "host")
            val body = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("c", body.getString("commitmentId")); assertEquals(7L, body.getLong("expectedVersion")); assertFalse(body.has("workId"))
        }
    }
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

    @Test fun workPagePreservesLiteralSearchCursorAndProxyPrefix() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"generation":"host","entryId":"doll","epoch":7,"query":"报告 + 100% _","status":"ended","revision":"r1","items":[{"id":"old","entryId":"doll","goal":"old report","state":"completed","match":"报告 + 100% _","version":4}],"next":null,"total":1}"""))
            val page = HttpProductApi.fixture(server.url("/dsh/")).workPage("doll", query = "报告 + 100% _", status = "ended", before = "opaque+/=", limit = 17)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/dsh/losna/agent/api/work-page", url.encodedPath)
            assertEquals("报告 + 100% _", url.queryParameter("query")); assertEquals("ended", url.queryParameter("status"))
            assertEquals("opaque+/=", url.queryParameter("before")); assertEquals("17", url.queryParameter("limit"))
            assertEquals(1, page.total); assertEquals(4L, page.items.single().version); assertEquals(page.query, page.items.single().match)
        }
    }

    @Test fun growthDecodesConditionalMetadataAndLegacyProjectionWithoutGuessingMaturity() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val body = """{"entryId":"doll","persona":{"id":"warm","name":"合成人格"},"habits":[{"text":"简短回复","quote":"今天短点","scope":"day"}],"views":[{"topic":"阅读","text":"合成看法","maturity":"developing","evidenceQuote":"合成摘录","sourceTitle":"材料"}]}"""
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            val api = HttpProductApi.fixture(server.url("/dsh/")); val page = api.growth("doll")
            assertEquals("/dsh/losna/agent/api/growth?entryId=doll", server.takeRequest().path)
            assertNull(page.revision); assertNull(page.epoch); assertEquals("day", page.habits.single().scope)
            assertEquals("developing", page.views.single().maturity)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"entryId":"doll","persona":{"id":"warm","name":"合成人格"},"habits":[],"views":[],"generation":"host","epoch":9,"revision":"r1","unchanged":true}"""))
            val unchanged = api.growth("doll", "r1")
            assertEquals("r1", server.takeRequest().requestUrl!!.queryParameter("ifRevision"))
            assertEquals(9L, unchanged.epoch); assertTrue(unchanged.unchanged)
        }
    }

    @Test fun nativeBearerAppliesToJsonAndFilesWithoutRedirectOrWriteReplay() = runBlocking<Unit> {
        MockWebServer().use { server ->
            var calls = 0
            val api = HttpProductApi.fixture(server.url("/dsh/"), BearerTokens { calls++; "synthetic-access" })
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setHeader("Set-Cookie", "must-not-mix=one; Path=/").setBody("""{"issuer":"https://auth.example.com","sub":"owner"}"""))
            assertEquals("owner", api.nativeIdentity().getString("sub"))
            val first = server.takeRequest(); assertEquals("Bearer synthetic-access", first.getHeader("Authorization")); assertNull(first.getHeader("Cookie"))
            server.enqueue(MockResponse().setResponseCode(401))
            try { api.send(PendingCommand("stable-id", "losna", "hello", 1), "host"); fail() } catch (error: ApiFailure) { assertEquals(401, error.status) }
            val write = server.takeRequest(); assertEquals("Bearer synthetic-access", write.getHeader("Authorization")); assertNull(write.getHeader("Cookie"))
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example.com/"))
            val artifact = Artifact("empty", "empty.txt", 0, "text/plain", true, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", true)
            try { api.artifactContent(artifact, "losna"); fail() } catch (error: ApiFailure) { assertEquals(302, error.status) }
            assertEquals("Bearer synthetic-access", server.takeRequest().getHeader("Authorization")); assertEquals(3, server.requestCount); assertEquals(3, calls)
        }
    }

}

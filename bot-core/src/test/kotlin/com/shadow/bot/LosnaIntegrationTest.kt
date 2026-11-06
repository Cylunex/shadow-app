package com.shadow.bot

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exercises Kotlin DTOs against the actual Losna Product/Store/Conversation services. */
class LosnaIntegrationTest {
    @Test fun createsCustomContactEditsPersonaChatsAndRecoversThroughRealProductHttp() = runBlocking {
        val process = ProcessBuilder("node", System.getProperty("fixtureScript"), System.getProperty("losnaRepo"))
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val address = process.inputStream.bufferedReader().readLine()
            assertNotNull("fixture must start", address)
            val api = HttpProductApi.fixture(address.toHttpUrl())
            val hello = api.handshake()
            assertEquals("main:owner:losna", hello.roles.single().conversationId)
            val id = "bot-" + UUID.randomUUID().toString()
            assertEquals(id, api.createRole(id, "合成伙伴"))
            assertEquals(id, api.createRole(id, "合成伙伴"))
            val persona = api.personas(id)
            api.savePersona(persona.copy(cards = persona.cards.map { if (it.id == persona.active) it.copy(displayName = "合成小舟", instructions = "合成资料", inheritIdentity = false) else it }))
            assertEquals("合成小舟", api.handshake().roles.first { it.id == id }.name)
            val beforeImport = api.personas(id)
            val imported = PersonaFiles.decode("""{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"合成导入伙伴","description":"合成性格"}}""").card
            val withImport = api.savePersona(PersonaFiles.add(beforeImport, imported))
            assertEquals("合成导入伙伴", api.handshake().roles.first { it.id == id }.name)
            assertEquals("main:owner:$id", api.handshake().roles.first { it.id == id }.conversationId)
            try { api.savePersona(beforeImport); fail("stale card edits must not overwrite imported cards") }
            catch (error: ApiFailure) { assertEquals(409, error.status) }
            assertEquals(withImport, api.personas(id))
            api.savePersona(PersonaFiles.remove(withImport, withImport.active))
            assertEquals("合成小舟", api.handshake().roles.first { it.id == id }.name)

            val message = PendingCommand(UUID.randomUUID().toString(), id, "synthetic conversation", 1)
            val receipt = api.send(message, hello.generation)
            assertEquals(receipt, api.send(message, hello.generation))
            var snapshot = api.snapshot(id)
            repeat(60) { if (snapshot.messages.none { it.role == "assistant" }) { delay(100); snapshot = api.snapshot(id) } }
            assertEquals(2, snapshot.messages.size)
            assertEquals("synthetic Losna reply", snapshot.messages.last().text)
            assertEquals("合成小舟", snapshot.messages.last().personaName)
            assertTrue(api.snapshot("losna").messages.none { it.text == "synthetic conversation" })
            assertTrue(api.events(0).changed)
            assertTrue(api.memories().isEmpty())
            val results = api.history(id, query = "synthetic conversation")
            assertEquals(receipt, results.items.single().id)
            assertTrue("messageContext" in api.handshake().capabilities)
            val context = api.messageContext(id, MessageRef(receipt, "user"), results.epoch)
            assertEquals(id, context.entryId)
            assertTrue(context.items.any { it.id == receipt && it.role == "user" })
            assertTrue(context.items.any { it.role == "assistant" })
            try { api.messageContext("losna", MessageRef(receipt, "user"), results.epoch); fail("cross-contact context") }
            catch (error: ApiFailure) { assertEquals(404, error.status) }
            val role = api.handshake().roles.first { it.id == id }
            api.saveContact(role, true)
            assertTrue(api.handshake().roles.first { it.id == id }.archived)
            api.saveContact(api.handshake().roles.first { it.id == id }, false)
            val detail = api.work("fixture-work", "losna")
            assertEquals("waiting", detail.work.state)
            assertEquals("write report", detail.plan.single().content)
            assertTrue(detail.allowedActions.contains("work.resume"))
            val resume = PendingCommand(UUID.randomUUID().toString(), "losna", "continue report", 2, "work.resume", detail.work.id, detail.work.version)
            val accepted = api.send(resume, hello.generation)
            assertEquals(accepted, api.send(resume, hello.generation))
            var finished = api.work("fixture-work", "losna")
            repeat(60) { if (finished.work.state != "completed") { delay(100); finished = api.work("fixture-work", "losna") } }
            assertEquals("completed", finished.work.state)
            assertTrue(finished.runs.isNotEmpty())
            val artifact = finished.artifacts.single()
            assertTrue(artifact.textPreview)
            assertTrue(artifact.downloadable)
            assertEquals("synthetic artifact content", String(api.artifactContent(artifact, "losna")))
            assertEquals("synthetic artifact content", api.artifact(artifact.id, "losna").text)
            assertTrue(api.inbox().items.any { it.entryId == id && it.text == "synthetic Losna reply" })
            try { api.work("fixture-work", id); fail("cross-entry access must fail") }
            catch (error: ApiFailure) { assertEquals(409, error.status) }
            val reconnected = HttpProductApi.fixture(address.toHttpUrl())
            assertEquals(snapshot.messages, reconnected.snapshot(id).messages)
        } finally {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
    @Test fun browsesOldWorksAndReopensOriginalTaskThroughActualHttp() = runBlocking<Unit> {
        val process = ProcessBuilder("node", System.getProperty("fixtureScript"), System.getProperty("losnaRepo"), "", "works")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val address = process.inputStream.bufferedReader().readLine()
            val api = HttpProductApi.fixture(address.toHttpUrl())
            val hello = api.handshake(); assertTrue("workBrowse" in hello.capabilities)
            val legacy = org.json.JSONObject(java.net.URL(address + "losna/agent/api/works?entryId=losna").readText())
            assertEquals(100, legacy.getJSONArray("items").length()); assertFalse(legacy.has("revision"))
            assertEquals(100, api.snapshot("losna").works.size)
            val first = api.workPage("losna", query = "100% _ +", status = "ended")
            val second = api.workPage("losna", query = first.query, status = first.status, before = first.next)
            val third = api.workPage("losna", query = first.query, status = first.status, before = second.next)
            assertEquals(123, first.total); assertEquals(123, (first.items + second.items + third.items).map { it.id }.toSet().size)
            assertNull(third.next); assertTrue(first.items.first().match.contains("100% _ +"))
            assertTrue(api.workPage("losna", query = first.query, status = first.status, ifRevision = first.revision).unchanged)
            val old = api.workPage("losna", query = "write report").items.single()
            assertEquals("fixture-work", old.id); assertEquals("waiting", api.work(old.id, "losna").work.state)
            try { api.workPage("losna", query = "changed", before = first.next); fail("cursor scope") }
            catch (error: ApiFailure) { assertEquals(400, error.status) }
            val other = api.createRole("bot-" + UUID.randomUUID(), "合成伙伴")
            assertEquals(0, api.workPage(other).total)
            val memory = api.memories().single()
            api.send(PendingCommand(UUID.randomUUID().toString(), "losna", "", 1, action = "memory.forget", memoryId = memory.id, expectedVersion = memory.version), hello.generation)
            try { api.workPage("losna", query = first.query, status = first.status, before = first.next); fail("withdrawn cursor") }
            catch (error: ApiFailure) { assertEquals(409, error.status) }
            assertEquals(0, api.workPage("losna").total)
        } finally {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
    @Test fun commitmentsManageOriginalWorkAndWithdrawAfterForgetThroughHttp() = runBlocking<Unit> {
        val process = ProcessBuilder("node", System.getProperty("fixtureScript"), System.getProperty("losnaRepo"), "", "commitments")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val api = HttpProductApi.fixture(process.inputStream.bufferedReader().readLine().toHttpUrl())
            val hello = api.handshake(); assertTrue("commitmentDetails" in hello.capabilities)
            val page = api.commitments("losna"); var item = page.items.single()
            assertEquals("fixture-work", item.workId); assertTrue("commitment.complete" in item.allowedActions)
            assertTrue(api.commitments("losna", page.revision).unchanged)
            val pause = PendingCommand(UUID.randomUUID().toString(), "losna", "", 1, action = "commitment.pause", commitmentId = item.id, expectedVersion = item.version)
            val receipt = api.send(pause, hello.generation); assertEquals(receipt, api.send(pause, hello.generation))
            item = api.commitments("losna").items.single(); assertEquals("paused", item.state)
            val resume = PendingCommand(UUID.randomUUID().toString(), "losna", "新的完整阅读目标", 2, action = "commitment.resume", commitmentId = item.id, expectedVersion = item.version)
            api.send(resume, hello.generation); item = api.commitments("losna").items.single()
            assertEquals("active", item.state); assertEquals(resume.text, item.goal); assertEquals("fixture-work", item.workId)
            api.send(PendingCommand(UUID.randomUUID().toString(), "losna", "", 3, action = "commitment.complete", commitmentId = item.id, expectedVersion = item.version), hello.generation)
            assertEquals("completed", api.commitments("losna").items.single().state)
            val memory = api.memories().single()
            api.send(PendingCommand(UUID.randomUUID().toString(), "losna", "", 4, action = "memory.forget", memoryId = memory.id, expectedVersion = memory.version), hello.generation)
            assertTrue(api.commitments("losna").items.isEmpty())
        } finally {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    @Test fun growthUsesRealPersonaAndEvidenceAuthoritiesThroughHttp() = runBlocking<Unit> {
        val process = ProcessBuilder("node", System.getProperty("fixtureScript"), System.getProperty("losnaRepo"), "", "growth")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val api = HttpProductApi.fixture(process.inputStream.bufferedReader().readLine().toHttpUrl())
            val hello = api.handshake(); assertTrue("growthSnapshots" in hello.capabilities)
            val page = api.growth("losna")
            assertEquals(hello.roles.single().personaId, page.personaId)
            assertEquals("以后回复简短一点。", page.habits.single().quote)
            assertEquals("合成现场录音", page.views.single().sourceTitle)
            val unchanged = api.growth("losna", page.revision); assertTrue(unchanged.unchanged); assertTrue(unchanged.habits.isEmpty())
            val persona = api.personas("losna")
            val independent = api.savePersona(persona.copy(active = "warm", cards = persona.cards.map { if (it.id == "warm") it.copy(inheritIdentity = false) else it }))
            val switched = api.growth("losna", page.revision); assertFalse(switched.unchanged); assertTrue(switched.views.isEmpty()); assertTrue(switched.habits.isEmpty())
            api.savePersona(independent.copy(active = "default"))
            val restored = api.growth("losna"); assertEquals(page.views, restored.views)
            val memory = api.memories().single()
            api.send(PendingCommand(UUID.randomUUID().toString(), "losna", "", 1, action = "memory.forget", memoryId = memory.id, expectedVersion = memory.version), hello.generation)
            val forgotten = api.growth("losna", restored.revision)
            assertFalse(forgotten.unchanged); assertTrue(forgotten.views.isEmpty()); assertEquals(1, forgotten.habits.size)
        } finally {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    @Test fun browsesAndCorrectsSharedMemoriesThroughActualProductHttp() = runBlocking<Unit> {
        val process = ProcessBuilder("node", System.getProperty("fixtureScript"), System.getProperty("losnaRepo"), "", "memories")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val api = HttpProductApi.fixture(process.inputStream.bufferedReader().readLine().toHttpUrl())
            val hello = api.handshake(); assertTrue("memoryBrowse" in hello.capabilities)
            val first = api.memoryPage(query = "茶饮", kind = "preference", limit = 50)
            assertEquals(50, first.items.size); assertNotNull(first.next)
            val second = api.memoryPage(query = "茶饮", kind = "preference", before = first.next, limit = 50)
            val third = api.memoryPage(query = "茶饮", kind = "preference", before = second.next, limit = 50)
            assertEquals(123, (first.items + second.items + third.items).map { it.id }.toSet().size)
            assertNull(third.next)
            assertTrue(api.memoryPage(query = "茶饮", kind = "preference", ifRevision = first.revision).unchanged)
            val target = first.items.first(); val detail = api.memory(target.id)
            assertEquals(target, detail.item); assertTrue(detail.sources.single().text.startsWith("synthetic memory evidence"))
            api.send(PendingCommand(UUID.randomUUID().toString(), "losna", "改为合成咖啡偏好", 1,
                action = "memory.correct", memoryId = target.id, expectedVersion = target.version), hello.generation)
            try { api.memoryPage(query = "茶饮", kind = "preference", before = first.next); fail("stale memory cursor") }
            catch (error: ApiFailure) { assertEquals(409, error.status) }
            val corrected = api.memoryPage(query = "咖啡", kind = "preference").items.single()
            assertNotEquals(target.id, corrected.id)
            api.send(PendingCommand(UUID.randomUUID().toString(), "losna", "", 2,
                action = "memory.forget", memoryId = corrected.id, expectedVersion = corrected.version), hello.generation)
            assertTrue(api.memoryPage(query = "咖啡").items.isEmpty())
            assertTrue(api.memoryPage(query = "茶饮", ifRevision = first.revision).items.none { it.id == target.id })
        } finally {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
    @Test fun attachmentsTravelThroughLifeSkillAndActualProductHttp() = runBlocking<Unit> {
        val lifeRepo = System.getProperty("lifeRepo")
        org.junit.Assume.assumeNotNull(lifeRepo)
        val process = ProcessBuilder("node", System.getProperty("fixtureScript"), System.getProperty("losnaRepo"), lifeRepo)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val address = process.inputStream.bufferedReader().readLine()
            assertNotNull(address)
            val api = HttpProductApi.fixture(address.toHttpUrl())
            val hello = api.handshake(); assertTrue("attachments" in hello.capabilities)
            val document = "synthetic attachment original".toByteArray()
            val id = UUID.randomUUID().toString()
            val first = api.uploadAttachment(id, "losna", "原始资料.pdf", "application/pdf", document)
            assertEquals(first, api.uploadAttachment(id, "losna", "原始资料.pdf", "application/pdf", document))
            assertArrayEquals(document, api.attachmentContent(first, "losna"))
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=")
            val image = api.uploadAttachment(UUID.randomUUID().toString(), "losna", "合成图片.png", "image/png", png)
            val command = PendingCommand(UUID.randomUUID().toString(), "losna", "", 1, attachments = listOf(first, image))
            val receipt = api.send(command, hello.generation)
            assertEquals(receipt, api.send(command, hello.generation))
            val snapshot = api.snapshot("losna")
            assertEquals(listOf(first, image), snapshot.messages.first { it.id == receipt }.attachments)
            val other = api.createRole("bot-" + UUID.randomUUID(), "另一个伙伴")
            try { api.attachmentContent(first, other); fail("must reject cross-entry content") } catch (error: ApiFailure) { assertEquals(404, error.status) }
        } finally {
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

}

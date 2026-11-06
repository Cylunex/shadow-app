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
}

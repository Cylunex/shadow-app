package com.shadow.bot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BotRepositoryTest {
    private class Store : StateStore {
        var saved = SavedState(Session("https://example.com/", emptyList()))
        var fail = false
        override fun read() = saved
        override fun write(state: SavedState) { if (fail) throw IOException("disk unavailable"); saved = state }
    }
    private class Api : ProductApi {
        var generation = "one"
        var cursor = 0L
        var rows = mutableListOf<Message>()
        val commands = mutableMapOf<String, PendingMessage>()
        var loseResponse = false
        var loginExpired = false
        var reset = false
        var reads = 0
        override suspend fun createRole(id: String, name: String) = id
        override suspend fun handshake(): Handshake {
            if (loginExpired) throw ApiFailure(401, "expired")
            return Handshake(generation, listOf(BotRole("losna", "main:owner:losna", "Losna"), BotRole("doll", "main:owner:doll", "Doll")), emptySet())
        }
        override suspend fun snapshot(entryId: String): Snapshot {
            reads++
            return Snapshot(generation, "main:owner:$entryId", if (entryId == "losna") rows.toList() else emptyList(), emptyList(), cursor)
        }
        override suspend fun events(after: Long) = Events(generation, cursor, after < cursor, reset, false)
        override suspend fun send(message: PendingMessage, generation: String): String {
            check(this.generation == generation)
            if (!commands.containsKey(message.id)) {
                commands[message.id] = message
                rows.add(Message(message.id, "user", message.text, 1)); cursor++
            } else check(commands[message.id] == message)
            if (loseResponse) { loseResponse = false; throw IOException("response lost after accept") }
            return message.id
        }
        override suspend fun personas(entryId: String): PersonaSettings = error("unused")
        override suspend fun savePersona(settings: PersonaSettings) = settings
        override suspend fun memories() = emptyList<MemoryItem>()
    }
    @Test fun lostReceiptSurvivesRestartAndRetriesOriginalCommandAcrossGeneration() = runBlocking {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store)
        repo.refresh(); repo.select("losna"); api.loseResponse = true; repo.send("hello")
        val original = store.saved.pending.single()
        assertEquals(1, api.commands.size)
        api.generation = "two"
        val restored = BotRepository(api, store)
        restored.refresh(); restored.select("losna")
        assertEquals(original, restored.state.value.pending.single())
        restored.retry(original.id)
        assertEquals(1, api.commands.size)
        assertTrue(store.saved.pending.isEmpty())
        assertEquals(1, restored.state.value.messages.size)
    }
    @Test fun forgettingAndCursorResetReplaceRatherThanMergeOldMessages() = runBlocking {
        val api = Api(); val repo = BotRepository(api, Store())
        repo.refresh(); repo.select("losna"); repo.send("forget this")
        assertEquals(1, repo.state.value.messages.size)
        api.rows.clear(); api.reset = true
        repo.poll(); assertTrue(repo.state.value.messages.isEmpty())
        api.generation = "restarted"; api.reset = false; repo.poll()
        assertNull(repo.state.value.error)
    }
    @Test fun switchingContactsCannotLeakAnotherConversationOrItsCursor() = runBlocking {
        val api = Api(); val repo = BotRepository(api, Store())
        repo.refresh(); repo.select("losna"); repo.send("only here")
        repo.select("doll"); assertTrue(repo.state.value.messages.isEmpty())
        repo.select("losna"); assertEquals("only here", repo.state.value.messages.single().text)
    }
    @Test fun persistenceFailurePreventsNetworkWriteAndAuthenticationFailurePreservesPending() = runBlocking {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store)
        repo.refresh(); repo.select("losna"); store.fail = true; assertFalse(repo.send("never send"))
        assertTrue(api.commands.isEmpty())
        store.fail = false; api.loginExpired = true; assertTrue(repo.send("keep for reconnect"))
        assertEquals(1, store.saved.pending.size); assertTrue(repo.state.value.needsLogin)
        assertTrue(api.commands.isEmpty())
    }
    @Test fun encryptedStorePayloadRoundTripsUnicodeAndStableIdentifiers() {
        val saved = SavedState(Session("https://example.com/dsh/", listOf("dsh-auth-test=opaque; path=/")), listOf(PendingMessage("fixed-id", "doll", "你好\n新的意图", 123)))
        assertEquals(saved, StateCodec.decode(StateCodec.encode(saved)))
    }
}

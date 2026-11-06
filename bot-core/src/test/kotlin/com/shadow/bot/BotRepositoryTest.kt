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
        val commands = mutableMapOf<String, PendingCommand>()
        var loseResponse = false
        var loginExpired = false
        var reset = false
        var reads = 0
        var detail = WorkDetail(Work("work-one", "losna", "goal", "waiting", "result", "next", 1), emptyList(), emptyList(), emptyList(), setOf("work.resume", "work.cancel"), false, false)
        var failWork = false
        override suspend fun inbox() = Inbox(generation, listOf(InboxItem("losna", "last exchange", 12, 2)))
        override suspend fun work(id: String, entryId: String): WorkDetail {
            if (failWork) throw ApiFailure(404, "withdrawn")
            return detail
        }
        override suspend fun artifact(id: String, entryId: String) = ArtifactText(id, detail.work.id, "report.txt", "report", false)
        override suspend fun createRole(id: String, name: String) = id
        override suspend fun handshake(): Handshake {
            if (loginExpired) throw ApiFailure(401, "expired")
            return Handshake(generation, listOf(BotRole("losna", "main:owner:losna", "Losna"), BotRole("doll", "main:owner:doll", "Doll")), setOf("inbox", "workDetails", "artifactText"))
        }
        override suspend fun snapshot(entryId: String): Snapshot {
            reads++
            return Snapshot(generation, "main:owner:$entryId", if (entryId == "losna") rows.toList() else emptyList(), emptyList(), cursor)
        }
        override suspend fun events(after: Long) = Events(generation, cursor, after < cursor, reset, false)
        override suspend fun send(message: PendingCommand, generation: String): String {
            check(this.generation == generation)
            if (!commands.containsKey(message.id)) {
                if (message.action != "message" && message.expectedVersion != detail.work.version) throw ApiFailure(409, "stale version")
                if (message.action == "work.cancel") detail = detail.copy(work = detail.work.copy(state = "cancelled", version = detail.work.version + 1), allowedActions = emptySet())
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
    @Test fun lostReceiptSurvivesRestartAndRetriesOriginalCommandAcrossGeneration() = runBlocking<Unit> {
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
    @Test fun forgettingAndCursorResetReplaceRatherThanMergeOldMessages() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store())
        repo.refresh(); repo.select("losna"); repo.send("forget this")
        assertEquals(1, repo.state.value.messages.size)
        api.rows.clear(); api.reset = true
        repo.poll(); assertTrue(repo.state.value.messages.isEmpty())
        api.generation = "restarted"; api.reset = false; repo.poll()
        assertNull(repo.state.value.error)
    }
    @Test fun switchingContactsCannotLeakAnotherConversationOrItsCursor() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store())
        repo.refresh(); repo.select("losna"); repo.send("only here")
        repo.select("doll"); assertTrue(repo.state.value.messages.isEmpty())
        repo.select("losna"); assertEquals("only here", repo.state.value.messages.single().text)
    }
    @Test fun persistenceFailurePreventsNetworkWriteAndAuthenticationFailurePreservesPending() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store)
        repo.refresh(); repo.select("losna"); store.fail = true; assertFalse(repo.send("never send"))
        assertTrue(api.commands.isEmpty())
        store.fail = false; api.loginExpired = true; assertTrue(repo.send("keep for reconnect"))
        assertEquals(1, store.saved.pending.size); assertTrue(repo.state.value.needsLogin)
        assertTrue(api.commands.isEmpty())
    }
    @Test fun encryptedStorePayloadRoundTripsUnicodeAndStableIdentifiers() {
        val saved = SavedState(Session("https://example.com/dsh/", listOf("dsh-auth-test=opaque; path=/")), listOf(PendingCommand("fixed-id", "doll", "你好\n新的意图", 123)))
        assertEquals(saved, StateCodec.decode(StateCodec.encode(saved)))
    }
    @Test fun workReceiptLossRetainsOriginalVersionAndRecoversAfterProcessRestart() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store)
        repo.refresh(); repo.select("losna"); repo.openWork("work-one")
        api.loseResponse = true
        assertTrue(repo.submitWork("work.cancel", ""))
        val command = store.saved.pending.single()
        assertEquals(1L, command.expectedVersion)
        assertEquals("work.cancel", command.action)
        assertEquals("cancelled", api.detail.work.state)
        val restored = BotRepository(api, store)
        restored.refresh(); restored.select("losna"); restored.openWork("work-one"); restored.retry(command.id)
        assertTrue(store.saved.pending.isEmpty()); assertEquals(1, api.commands.size)
    }
    @Test fun staleWorkControlsNeverUpgradeVersionOrSilentlyReplayIntent() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store)
        repo.refresh(); repo.select("losna"); repo.openWork("work-one")
        api.detail = api.detail.copy(work = api.detail.work.copy(version = 9))
        repo.submitWork("work.resume", "new requirement")
        assertTrue(api.commands.isEmpty()); assertEquals(1L, store.saved.pending.single().expectedVersion)
        repo.poll(); assertNotNull(repo.state.value.error)
        repo.retry(store.saved.pending.single().id)
        assertTrue(api.commands.isEmpty()); assertEquals(1L, store.saved.pending.single().expectedVersion)
    }
    @Test fun withdrawnWorkClearsPreviouslyVisibleDetailsAndPreview() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store())
        api.detail = api.detail.copy(artifacts = listOf(Artifact("artifact", "report.txt", 6, "text/plain", true)))
        repo.refresh(); repo.select("losna"); repo.openWork("work-one"); repo.previewArtifact("artifact")
        assertEquals("report", repo.state.value.artifactText?.text)
        api.failWork = true; api.cursor++
        repo.poll(); assertNull(repo.state.value.workDetail); assertNull(repo.state.value.artifactText)
    }
    @Test fun legacyPendingMessagesUpgradeWithoutChangingIdsOrInventingWorkTargets() {
        val legacy = """{"version":1,"pending":[{"id":"old-id","entryId":"doll","text":"hello","createdAt":1}]}"""
        val decoded = StateCodec.decode(legacy)
        assertEquals("old-id", decoded.pending.single().id); assertEquals("message", decoded.pending.single().action)
        assertNull(decoded.pending.single().expectedVersion)
        assertEquals(decoded, StateCodec.decode(StateCodec.encode(decoded)))
    }
    @Test fun inboxSummariesAreServerFactsAndSharingOnlyProducesAnEditableDraft() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh()
        assertEquals("last exchange", repo.state.value.roles.first().preview)
        assertEquals(2, repo.state.value.roles.first().activeWorks)
        val shared = SharedText.read("android.intent.action.SEND", "text/plain", "  reference text  ")!!
        assertEquals("draft\n\nreference text", SharedText.append("draft", shared))
        assertTrue(api.commands.isEmpty())
        assertNull(SharedText.read("android.intent.action.VIEW", "text/plain", "ignore"))
        assertNull(SharedText.read("android.intent.action.SEND", "image/png", "ignore"))
        assertThrows(IllegalArgumentException::class.java) { SharedText.read("android.intent.action.SEND", "text/plain", "x".repeat(4001)) }
        assertThrows(IllegalArgumentException::class.java) { SharedText.append("x".repeat(4000), "more") }
    }

}

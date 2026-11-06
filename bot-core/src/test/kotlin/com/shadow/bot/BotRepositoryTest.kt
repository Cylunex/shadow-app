package com.shadow.bot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BotRepositoryTest {
    private class Store : StateStore {
        var saved = SavedState(Session("https://example.com/", emptyList()))
        var cache: OfflineCache? = null
        override fun readCache() = cache
        override fun writeCache(cache: OfflineCache) { this.cache = cache }
        override fun clearCache() { cache = null }
        var fail = false
        override fun read() = saved
        override fun write(state: SavedState) { if (fail) throw IOException("disk unavailable"); saved = state }
    }
    private class Api : ProductApi {
        var generation = "one"
        var handshakeError: Exception? = null
        var cursor = 0L
        var epoch = 0L
        var historyCursor: String? = null
        val pages = java.util.ArrayDeque<MessagePage>()
        override suspend fun history(entryId: String, before: String?, query: String): MessagePage = pages.removeFirst()
        var rows = mutableListOf<Message>()
        val commands = mutableMapOf<String, PendingCommand>()
        var loseResponse = false
        var loginExpired = false
        var reset = false
        var reads = 0
        var detail = WorkDetail(Work("work-one", "losna", "goal", "waiting", "result", "next", 1), emptyList(), emptyList(), emptyList(), setOf("work.resume", "work.cancel"), false, false)
        var failWork = false
        var inboxError: Exception? = null
        var artifactError: Exception? = null
        var beforeInbox: () -> Unit = {}
        var beforeArtifact: () -> Unit = {}
        override suspend fun inbox(): Inbox {
            beforeInbox(); inboxError?.let { throw it }
            return Inbox(generation, listOf(InboxItem("losna", "last exchange", 12, 2)))
        }
        override suspend fun work(id: String, entryId: String): WorkDetail {
            if (failWork) throw ApiFailure(404, "withdrawn")
            return detail
        }
        override suspend fun artifact(id: String, entryId: String): ArtifactText {
            beforeArtifact(); artifactError?.let { throw it }
            return ArtifactText(id, detail.work.id, "report.txt", "report", false)
        }
        override suspend fun createRole(id: String, name: String) = id
        override suspend fun handshake(): Handshake {
            handshakeError?.let { throw it }
            if (loginExpired) throw ApiFailure(401, "expired")
            return Handshake(generation, listOf(BotRole("losna", "main:owner:losna", "Losna"), BotRole("doll", "main:owner:doll", "Doll")), setOf("inbox", "workDetails", "artifactText", "attachments"), epoch)
        }
        override suspend fun snapshot(entryId: String): Snapshot {
            reads++
            return Snapshot(generation, "main:owner:$entryId", if (entryId == "losna") rows.toList() else emptyList(), emptyList(), cursor, historyCursor, epoch)
        }
        override suspend fun events(after: Long) = Events(generation, cursor, after < cursor, reset, false)
        override suspend fun send(message: PendingCommand, generation: String): String {
            check(this.generation == generation)
            if (!commands.containsKey(message.id)) {
                if (message.action != "message" && message.expectedVersion != detail.work.version) throw ApiFailure(409, "stale version")
                if (message.action == "work.cancel") detail = detail.copy(work = detail.work.copy(state = "cancelled", version = detail.work.version + 1), allowedActions = emptySet())
                commands[message.id] = message
                rows.add(Message(message.id, "user", message.text, 1, attachments = message.attachments)); cursor++
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

    @Test fun attachmentOnlyMessageKeepsReferencesAcrossLostReceiptAndRestart() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store)
        val attachment = Attachment("upload-original", "原件.pdf", "application/pdf", 12, "a".repeat(64), "assetv_original")
        repo.refresh(); repo.select("losna"); api.loseResponse = true
        assertTrue(repo.send("", listOf(attachment)))
        val original = store.saved.pending.single()
        store.saved = StateCodec.decode(StateCodec.encode(store.saved))
        assertEquals(listOf(attachment), store.saved.pending.single().attachments)
        val restored = BotRepository(api, store)
        restored.refresh(); restored.select("losna"); restored.retry(original.id)
        assertTrue(store.saved.pending.isEmpty()); assertEquals(1, api.commands.size)
        assertEquals(listOf(attachment), restored.state.value.messages.single().attachments)
    }
    @Test fun attachmentReaderBoundsUnknownLengthAndRejectsEmptyFiles() {
        assertThrows(IllegalArgumentException::class.java) { readAttachmentBytes(byteArrayOf().inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { readAttachmentBytes(ByteArray(20 * 1024 * 1024 + 1).inputStream()) }
        assertArrayEquals("original".toByteArray(), readAttachmentBytes("original".byteInputStream()))
    }

    @Test fun pollingNeverPublishesEmptyInboxSummariesOrDisablesInput() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh()
        val before = repo.state.value
        api.beforeInbox = {
            assertEquals(before.roles, repo.state.value.roles)
            assertFalse(repo.state.value.busy)
        }
        repeat(3) { repo.poll(); assertEquals(before, repo.state.value) }
        repo.refresh(silent = true); assertEquals(before, repo.state.value)
        api.inboxError = IOException("offline")
        repo.poll()
        assertEquals(before.roles, repo.state.value.roles)
        assertNull(repo.state.value.error); assertNotNull(repo.state.value.syncIssue)
        api.inboxError = null; repo.poll(); assertEquals(before, repo.state.value)
        api.loginExpired = true; repo.poll(); assertTrue(repo.state.value.needsLogin)
    }

    @Test fun previewStaysVisibleDuringRefreshAndRetriesTransientFailures() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store())
        api.detail = api.detail.copy(artifacts = listOf(Artifact("artifact", "report.txt", 6, "text/plain", true)))
        repo.refresh(); repo.select("losna"); repo.openWork("work-one"); repo.previewArtifact("artifact")
        val before = repo.state.value
        api.beforeArtifact = {
            assertEquals(before.artifactText, repo.state.value.artifactText)
            assertFalse(repo.state.value.busy)
        }
        api.cursor++; repo.poll(); assertEquals(before, repo.state.value)
        api.artifactError = IOException("offline"); api.cursor++; repo.poll()
        assertEquals(before.artifactText, repo.state.value.artifactText)
        assertEquals(before.workDetail, repo.state.value.workDetail)
        assertNotNull(repo.state.value.syncIssue)
        api.artifactError = null; repo.poll(); assertEquals(before, repo.state.value)
        api.artifactError = ApiFailure(404, "withdrawn"); api.cursor++; repo.poll()
        assertNull(repo.state.value.artifactText); assertEquals(before.workDetail, repo.state.value.workDetail)
    }

    @Test fun draftConsumptionIsAtomicWithPendingQueueAndDoesNotConsumeOtherContacts() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store)
        store.saved = store.saved.copy(drafts = mapOf("losna" to "hello", "doll" to "keep"))
        repo.refresh(); repo.select("losna"); api.loseResponse = true
        assertTrue(repo.send("hello"))
        assertFalse(store.saved.drafts.containsKey("losna")); assertEquals("keep", store.saved.drafts["doll"])
        val restored = StateCodec.decode(StateCodec.encode(store.saved))
        assertEquals(1, restored.pending.size); assertEquals(store.saved.drafts, restored.drafts)
        store.saved = store.saved.copy(drafts = mapOf("losna" to "unsaved")); store.fail = true
        assertFalse(repo.send("unsaved")); assertEquals("unsaved", store.saved.drafts["losna"])
    }
    @Test fun historyKeepsTheBoundaryWhenNewMessagesArriveAndClearsOnForget() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store)
        api.rows.addAll(listOf(Message("2", "user", "second", 2), Message("3", "assistant", "third", 3)))
        api.historyCursor = "before-two"
        repo.refresh(); repo.select("losna")
        api.pages.add(MessagePage("one", "losna", 0, "", listOf(Message("1", "user", "first", 1)), null))
        repo.loadHistory(); assertEquals(listOf("1", "2", "3"), repo.state.value.messages.map { it.id })
        api.rows.removeAt(0); api.rows.add(Message("4", "assistant", "fourth", 4)); repo.refresh()
        assertEquals(listOf("1", "2", "3", "4"), repo.state.value.messages.map { it.id })
        api.epoch++; api.rows.clear(); api.historyCursor = null; repo.refresh()
        assertTrue(repo.state.value.messages.isEmpty()); assertNull(repo.state.value.historyCursor)
    }
    @Test fun attachmentDraftRestoreNeverPersistsUriGrantsOrAutomaticallyResumesUpload() {
        val draft = AttachmentDraft("a".repeat(36), "content://private/provider", "原件.pdf", "uploading", mediaType = "application/pdf", localReady = true)
        val original = SavedState(drafts = mapOf("losna" to "草稿"), workDrafts = mapOf("work" to "补充"), attachmentDrafts = mapOf("losna" to listOf(draft)))
        val encoded = StateCodec.encode(original); assertFalse(encoded.contains("content://"))
        val restored = StateCodec.decode(encoded)
        assertEquals(original.drafts, restored.drafts); assertEquals(original.workDrafts, restored.workDrafts)
        val attachment = restored.attachmentDrafts.getValue("losna").single()
        assertTrue(attachment.localReady); assertEquals("failed", attachment.status); assertTrue(attachment.uri.isEmpty())
        val pending = PendingCommand("memory-operation", "losna", "new fact", 1, "memory.correct", expectedVersion = 3, memoryId = "memory")
        assertEquals(pending, StateCodec.decode(StateCodec.encode(SavedState(pending = listOf(pending)))).pending.single())
    }
    @Test fun offlineCacheIsBoundedAndCannotBypassReconnectRevocation() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val original = BotRepository(api, store)
        api.rows.add(Message("private", "user", "previously visible", 1))
        original.refresh(); original.select("losna")
        assertEquals("previously visible", store.cache?.messages?.single()?.text)
        val decoded = OfflineCacheCodec.decode(OfflineCacheCodec.encode(requireNotNull(store.cache)))
        assertEquals(store.cache, decoded)
        val restart = BotRepository(api, store); api.handshakeError = IOException("offline")
        restart.refresh(); restart.select("losna")
        assertTrue(restart.state.value.offline); assertEquals("private", restart.state.value.messages.single().id)
        api.handshakeError = null; api.epoch++; api.rows.clear(); restart.refresh()
        assertFalse(restart.state.value.offline); assertTrue(restart.state.value.messages.isEmpty())
        assertTrue(store.cache?.messages.orEmpty().isEmpty())
        api.loginExpired = true; restart.refresh(); assertNull(store.cache); assertTrue(restart.state.value.needsLogin)
    }
}

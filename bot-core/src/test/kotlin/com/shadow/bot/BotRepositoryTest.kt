package com.shadow.bot

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
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
        var memoryBrowse = true
        var growthEnabled = true
        var growthSnapshots = true
        var personaId = ""
        val growthPages = java.util.ArrayDeque<GrowthPage>()
        val growthRequests = mutableListOf<String?>()
        var growthError: Exception? = null
        var beforeGrowth: suspend () -> Unit = {}
        override suspend fun growth(entryId: String, ifRevision: String?): GrowthPage {
            growthRequests.add(ifRevision); beforeGrowth(); growthError?.let { throw it }; return growthPages.removeFirst()
        }
        var workBrowse = true
        val workPages = java.util.ArrayDeque<WorkPage>()
        var workError: Exception? = null
        var beforeWorks: suspend () -> Unit = {}
        val workRequests = mutableListOf<Pair<String?, String?>>()
        override suspend fun workPage(entryId: String, query: String, status: String, before: String?, ifRevision: String?, limit: Int): WorkPage {
            workRequests.add(before to ifRevision); beforeWorks(); workError?.let { throw it }; return workPages.removeFirst()
        }
        val memoryPages = java.util.ArrayDeque<MemoryPage>()
        var memoryError: Exception? = null
        var beforeMemories: suspend () -> Unit = {}
        val memoryRequests = mutableListOf<Pair<String?, String?>>()
        var memoryDetail = MemoryDetail(MemoryItem("a", "Tea", "preference", 1), emptyList())
        override suspend fun memory(id: String): MemoryDetail { beforeMemories(); memoryError?.let { throw it }; return memoryDetail }
        override suspend fun memoryPage(query: String, kind: String, before: String?, ifRevision: String?, limit: Int): MemoryPage {
            memoryRequests.add(before to ifRevision); beforeMemories(); memoryError?.let { throw it }; return memoryPages.removeFirst()
        }
        var handshakeError: Exception? = null
        var cursor = 0L
        var epoch = 0L
        var historyCursor: String? = null
        val pages = java.util.ArrayDeque<MessagePage>()
        var historyError: Exception? = null
        val contexts = java.util.ArrayDeque<MessageContextPage>()
        var contextError: Exception? = null
        var beforeContext: suspend () -> Unit = {}
        val contextPositions = mutableListOf<Pair<String?, String?>>()
        override suspend fun messageContext(entryId: String, anchor: MessageRef, epoch: Long, before: String?, after: String?, limit: Int): MessageContextPage {
            contextPositions.add(before to after); beforeContext()
            contextError?.let { throw it }
            return contexts.removeFirst()
        }
        override suspend fun history(entryId: String, before: String?, query: String): MessagePage { historyError?.let { throw it }; return pages.removeFirst() }
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
            return Handshake(generation, listOf(BotRole("losna", "main:owner:losna", "Losna", personaId = personaId), BotRole("doll", "main:owner:doll", "Doll")), setOf("inbox", "workDetails", "artifactText", "attachments", "messageContext", "memories", "memoryActions") + (if (memoryBrowse) setOf("memoryBrowse") else emptySet()) + (if (workBrowse) setOf("workBrowse") else emptySet()) + (if (growthEnabled) setOf("growth") else emptySet()) + (if (growthSnapshots) setOf("growthSnapshots") else emptySet()), epoch)
        }
        var beforeSnapshot: () -> Unit = {}
        override suspend fun snapshot(entryId: String): Snapshot {
            beforeSnapshot(); reads++
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
    private fun context(vararg ids: String, before: String? = "older", after: String? = "newer") =
        MessageContextPage("one", "losna", 0, MessageRef("anchor", "user"), ids.map { Message(it, "user", it, 1) }, before, after)

    @Test fun contextMergesBothDirectionsAndNeverEntersOfflineCache() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store)
        repo.refresh(); repo.select("losna")
        api.contexts.add(context("anchor")); repo.openMessageContext(MessageRef("anchor", "user"))
        api.contexts.add(context("old", "anchor", before = null, after = null)); repo.moreMessageContext(true)
        assertEquals("newer", repo.state.value.messageContext?.after)
        api.contexts.add(context("new", before = null, after = null)); repo.moreMessageContext(false)
        assertEquals(listOf("old", "anchor", "new"), repo.state.value.messageContext?.items?.map { it.id })
        assertEquals(listOf(null to null, "older" to null, null to "newer"), api.contextPositions)
        repo.refresh()
        assertTrue(store.cache!!.messages.isEmpty())
        repo.select("doll"); assertNull(repo.state.value.messageContext)
    }

    @Test fun contextIsWithdrawnOnEpochGenerationAuthenticationAndAnchorChanges() = runBlocking<Unit> {
        for (change in listOf("epoch", "generation", "authentication", "anchor")) {
            val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna")
            api.contexts.add(context("anchor")); repo.openMessageContext(MessageRef("anchor", "user"))
            when (change) {
                "epoch" -> { api.epoch++; repo.refresh() }
                "generation" -> { api.generation = "two"; repo.refresh() }
                "authentication" -> { api.contextError = ApiFailure(401, "expired"); repo.moreMessageContext(true) }
                else -> { api.contextError = ApiFailure(404, "withdrawn"); repo.moreMessageContext(true) }
            }
            assertNull(change, repo.state.value.messageContext)
            assertFalse(repo.state.value.contextBusy)
            if (change == "authentication") assertTrue(repo.state.value.needsLogin)
            if (change == "anchor") assertNotNull(repo.state.value.contextError)
        }
    }

    @Test fun leavingContextDiscardsAnInflightResponse() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforeContext = { entered.complete(Unit); release.await() }
        api.contexts.add(context("anchor"))
        val request = async { repo.openMessageContext(MessageRef("anchor", "user")) }
        entered.await(); repo.clearMessageContext(); release.complete(Unit); request.await()
        assertNull(repo.state.value.messageContext); assertFalse(repo.state.value.contextBusy)
    }

    @Test fun expiredSearchCursorClearsPreviouslyVisibleResults() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna")
        api.pages.add(MessagePage("one", "losna", 0, "anchor", listOf(Message("anchor", "user", "anchor", 1)), "more"))
        repo.searchMessages("anchor"); assertEquals(1, repo.state.value.searchResults.size)
        api.historyError = ApiFailure(409, "withdrawn"); repo.searchMessages("anchor", more = true)
        assertTrue(repo.state.value.searchResults.isEmpty()); assertNull(repo.state.value.searchCursor)
        assertFalse(repo.state.value.searchDone)
    }

    @Test fun anOldLoginCannotOverwriteTheNewSessionCacheOrReuseItsOfflineMessages() = runBlocking<Unit> {
        val store = Store(); val api = Api()
        store.saved = store.saved.copy(session = store.saved.session!!.copy(id = "login-one"))
        val first = BotRepository(api, store); first.refresh(); first.select("losna"); first.send("old account message")
        assertEquals("login-one", store.cache!!.sessionId)
        api.beforeSnapshot = { store.saved = store.saved.copy(session = store.saved.session!!.copy(id = "login-two")) }
        val oldCache = store.cache; first.refresh(); assertEquals(oldCache, store.cache)
        api.handshakeError = IOException("offline")
        val second = BotRepository(api, store); second.refresh()
        assertTrue(second.state.value.messages.isEmpty()); assertTrue(second.state.value.roles.isEmpty())
    }

    private fun memoryPage(ids: List<String> = listOf("a"), next: String? = "next", revision: String = "r1", query: String = "", kind: String = "") =
        MemoryPage("one", 0, query, kind, revision, ids.map { MemoryItem(it, "Tea $it", "preference", 1) }, next)

    @Test fun memoryPaginationKeepsAllPagesAndDetailDuringUnchangedRefresh() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna")
        repo.beginMemories(); api.memoryPages.add(memoryPage()); repo.loadMemories()
        api.memoryPages.add(memoryPage(listOf("b"), null)); repo.loadMemories(more = true)
        repo.inspectMemory("a")
        api.memoryPages.add(memoryPage(emptyList(), null).copy(unchanged = true)); repo.loadMemories(silent = true)
        assertEquals(listOf("a", "b"), repo.state.value.memoryBrowser.items.map { it.id })
        assertEquals("a", repo.state.value.memoryBrowser.detail?.item?.id)
        assertEquals(listOf(null to null, "next" to null, null to "r1"), api.memoryRequests)
        api.memoryPages.add(memoryPage(listOf("c"), null, "r2")); repo.loadMemories(silent = true)
        assertEquals(listOf("c"), repo.state.value.memoryBrowser.items.map { it.id })
        assertNull(repo.state.value.memoryBrowser.detail)
    }
    @Test fun memoryCursorConflictAndAuthorityChangeClearPreviouslyVisibleContent() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna")
        repo.beginMemories(); api.memoryPages.add(memoryPage()); repo.loadMemories(); repo.inspectMemory("a")
        api.memoryError = ApiFailure(409, "expired cursor"); repo.loadMemories(more = true)
        assertTrue(repo.state.value.memoryBrowser.items.isEmpty()); assertNull(repo.state.value.memoryBrowser.detail)
        assertNull(repo.state.value.memoryBrowser.next)
        api.memoryError = null; api.memoryPages.add(memoryPage()); repo.loadMemories()
        api.epoch++; repo.refresh()
        assertTrue(repo.state.value.memoryBrowser.items.isEmpty())
        api.memoryPages.add(memoryPage().copy(epoch = 1)); repo.loadMemories()
        api.generation = "two"; repo.refresh()
        assertTrue(repo.state.value.memoryBrowser.items.isEmpty())
    }
    @Test fun memoryLatePageCannotReviveClosedBrowserOrSupersededSearch() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna")
        repo.beginMemories("Tea", "preference")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforeMemories = { started.complete(Unit); release.await() }
        api.memoryPages.add(memoryPage(query = "Tea", kind = "preference"))
        val pending = async { repo.loadMemories() }; started.await()
        repo.beginMemories("coffee", "episode"); release.complete(Unit); pending.await()
        assertEquals("coffee", repo.state.value.memoryBrowser.query)
        assertTrue(repo.state.value.memoryBrowser.items.isEmpty())
        api.beforeMemories = { repo.clearMemories(close = true) }
        api.memoryPages.add(memoryPage(query = "coffee", kind = "episode")); repo.loadMemories()
        assertFalse(repo.state.value.memoryBrowser.active); assertTrue(repo.state.value.memoryBrowser.items.isEmpty())
    }
    @Test fun memoryLateDetailAndChangedLoginAreDiscarded() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store); repo.refresh(); repo.select("losna"); repo.beginMemories()
        api.memoryPages.add(memoryPage()); repo.loadMemories()
        api.beforeMemories = { repo.closeMemoryDetail() }; repo.inspectMemory("a")
        assertNull(repo.state.value.memoryBrowser.detail)
        api.beforeMemories = { store.saved = store.saved.copy(session = Session("https://other.example.com/", emptyList())) }
        api.memoryPages.add(memoryPage(listOf("private-new"))); repo.loadMemories()
        assertEquals(listOf("a"), repo.state.value.memoryBrowser.items.map { it.id })
    }
    @Test fun memoryNetworkRetryPreservesPositionButForbiddenClearsAllContent() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginMemories()
        api.memoryPages.add(memoryPage()); repo.loadMemories(); repo.inspectMemory("a")
        api.memoryError = IOException("offline"); repo.loadMemories(more = true)
        assertEquals("a", repo.state.value.memoryBrowser.items.single().id)
        assertEquals("next", repo.state.value.memoryBrowser.next); assertNotNull(repo.state.value.memoryBrowser.error)
        api.memoryError = ApiFailure(403, "forbidden"); repo.loadMemories(silent = true)
        assertTrue(repo.state.value.memoryBrowser.items.isEmpty()); assertNull(repo.state.value.memoryBrowser.detail)
    }
    @Test fun oldMemoryServiceUsesLegacyListWithoutCallingBrowseProtocol() = runBlocking<Unit> {
        val api = Api(); api.memoryBrowse = false
        val repo = BotRepository(api, Store()); repo.refresh(); repo.beginMemories(); repo.loadMemories()
        assertTrue(repo.state.value.memoryBrowser.loaded); assertTrue(api.memoryRequests.isEmpty())
    }

    @Test fun memoryPaginationCannotReopenDismissedDetailAndBackgroundClearsRequests() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginMemories()
        api.memoryPages.add(memoryPage()); repo.loadMemories(); repo.inspectMemory("a")
        api.beforeMemories = { repo.closeMemoryDetail() }
        api.memoryPages.add(memoryPage(listOf("b"), null)); repo.loadMemories(more = true)
        assertNull(repo.state.value.memoryBrowser.detail)
        assertEquals(listOf("a", "b"), repo.state.value.memoryBrowser.items.map { it.id })
        api.beforeMemories = { repo.clearMemories(close = true) }
        api.memoryPages.add(memoryPage(listOf("c"))); repo.loadMemories(silent = true)
        assertFalse(repo.state.value.memoryBrowser.active); assertTrue(repo.state.value.memoryBrowser.items.isEmpty())
        assertFalse(repo.state.value.memoryBrowser.loading)
    }
    @Test fun memoryDetailNetworkFailureIsVisibleAndMissingMemoryClearsList() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginMemories()
        api.memoryPages.add(memoryPage()); repo.loadMemories()
        api.memoryError = IOException("offline"); repo.inspectMemory("a")
        assertNotNull(repo.state.value.memoryBrowser.error); assertFalse(repo.state.value.memoryBrowser.loading)
        assertEquals("a", repo.state.value.memoryBrowser.items.single().id)
        api.memoryError = ApiFailure(404, "forgotten"); repo.inspectMemory("a")
        assertTrue(repo.state.value.memoryBrowser.items.isEmpty()); assertNull(repo.state.value.memoryBrowser.detail)
        assertNotNull(repo.state.value.memoryBrowser.error)
    }

    private fun workPage(ids: List<String> = listOf("a"), next: String? = "next", revision: String = "r1", query: String = "", status: String = "") =
        WorkPage("one", 0, "losna", query, status, revision, ids.map { Work(it, "losna", "Report $it", "waiting", "result", "next", 1) }, next, 2)

    @Test fun workPaginationRetainsPagesDuringConditionalRefreshAndReplacesChangedRevision() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginWorks()
        api.workPages.add(workPage()); repo.loadWorks()
        api.workPages.add(workPage(listOf("b"), null)); repo.loadWorks(more = true)
        api.workPages.add(workPage(emptyList(), null).copy(unchanged = true)); repo.loadWorks(silent = true)
        assertEquals(listOf("a", "b"), repo.state.value.workBrowser.items.map { it.id })
        assertEquals(listOf(null to null, "next" to null, null to "r1"), api.workRequests)
        api.workPages.add(workPage(listOf("c"), null, "r2")); repo.loadWorks(silent = true)
        assertEquals(listOf("c"), repo.state.value.workBrowser.items.map { it.id })
        assertEquals(2, repo.state.value.workBrowser.total)
    }
    @Test fun workCursorConflictRestartAndMemoryEpochWithdrawOldPages() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginWorks()
        api.workPages.add(workPage()); repo.loadWorks()
        api.workError = ApiFailure(409, "stale"); repo.loadWorks(more = true)
        assertTrue(repo.state.value.workBrowser.items.isEmpty()); assertNull(repo.state.value.workBrowser.next)
        api.workError = null; api.workPages.add(workPage()); repo.loadWorks()
        api.epoch++; repo.refresh(); assertTrue(repo.state.value.workBrowser.items.isEmpty())
        api.workPages.add(workPage().copy(epoch = 1)); repo.loadWorks()
        api.generation = "two"; repo.refresh(); assertTrue(repo.state.value.workBrowser.items.isEmpty())
    }
    @Test fun lateWorkPagesCannotReviveChangedSearchClosedScreenOrChangedLogin() = runBlocking<Unit> {
        val api = Api(); val store = Store(); val repo = BotRepository(api, store); repo.refresh(); repo.select("losna"); repo.beginWorks("Report")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforeWorks = { started.complete(Unit); release.await() }; api.workPages.add(workPage(query = "Report"))
        val pending = async { repo.loadWorks() }; started.await()
        repo.beginWorks("new", "ended"); release.complete(Unit); pending.await()
        assertEquals("new", repo.state.value.workBrowser.query); assertTrue(repo.state.value.workBrowser.items.isEmpty())
        api.beforeWorks = { repo.clearWorks(close = true) }; api.workPages.add(workPage(query = "new", status = "ended")); repo.loadWorks()
        assertFalse(repo.state.value.workBrowser.active); assertFalse(repo.state.value.workBrowser.loading)
        repo.beginWorks(); api.beforeWorks = { store.saved = store.saved.copy(session = Session("https://other.example.com/", emptyList())) }
        api.workPages.add(workPage()); repo.loadWorks(); assertTrue(repo.state.value.workBrowser.items.isEmpty())
    }
    @Test fun workNetworkFailureRetainsLoadedContentButAuthorizationAndContactChangesClearIt() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginWorks()
        api.workPages.add(workPage()); repo.loadWorks()
        api.workError = IOException("offline"); repo.loadWorks(more = true)
        assertEquals("a", repo.state.value.workBrowser.items.single().id); assertEquals("next", repo.state.value.workBrowser.next)
        assertNotNull(repo.state.value.workBrowser.error)
        api.workError = ApiFailure(403, "forbidden"); repo.loadWorks(silent = true)
        assertTrue(repo.state.value.workBrowser.items.isEmpty())
        api.workError = null; repo.refresh(); api.workPages.add(workPage()); repo.loadWorks()
        repo.beginWorks("private search", "ended")
        repo.select("doll"); assertTrue(repo.state.value.workBrowser.items.isEmpty()); assertFalse(repo.state.value.workBrowser.active)
        assertEquals("", repo.state.value.workBrowser.query); assertEquals("", repo.state.value.workBrowser.status)
    }
    @Test fun workCapabilityLossAndPageLimitDoNotIssueUnsupportedRequests() = runBlocking<Unit> {
        val api = Api(); val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginWorks()
        repeat(12) { page ->
            api.workPages.add(workPage((1..50).map { (page * 50 + it).toString() }))
            repo.loadWorks(more = page > 0)
        }
        assertEquals(600, repo.state.value.workBrowser.items.size)
        repo.loadWorks(more = true); assertEquals(12, api.workRequests.size)
        api.workBrowse = false; repo.refresh(); repo.loadWorks(); assertEquals(12, api.workRequests.size)
        assertTrue(repo.state.value.workBrowser.items.isEmpty())
    }

    private fun growthPage(revision: String = "r1") = GrowthPage("losna", "losna", "Losna",
        listOf(GrowthHabit("简短回复", "以后回复简短一点", "persistent")),
        listOf(GrowthView("阅读", "合成看法", "developing", "合成摘录", "合成资料")), "one", 0, revision)

    @Test fun growthUnchangedRefreshKeepsContentAndChangedProjectionReplacesIt() = runBlocking<Unit> {
        val api = Api().apply { personaId = "losna" }; val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginGrowth()
        val first = growthPage(); api.growthPages.add(first); repo.loadGrowth()
        api.growthPages.add(first.copy(habits = emptyList(), views = emptyList(), unchanged = true)); repo.loadGrowth(silent = true)
        assertEquals(first, repo.state.value.growth.page); assertEquals(listOf(null, "r1"), api.growthRequests)
        api.growthPages.add(growthPage("r2").copy(habits = emptyList())); repo.loadGrowth(silent = true)
        assertTrue(repo.state.value.growth.page!!.habits.isEmpty()); assertFalse(repo.state.value.growth.loading)
    }
    @Test fun growthPersonaEpochRestartAndCapabilityChangesWithdrawOldContent() = runBlocking<Unit> {
        val api = Api().apply { personaId = "losna" }; val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginGrowth()
        api.growthPages.add(growthPage()); repo.loadGrowth()
        api.personaId = "warm"; repo.refresh(); assertNull(repo.state.value.growth.page)
        api.growthPages.add(growthPage()); repo.loadGrowth(); assertNull(repo.state.value.growth.page)
        api.growthPages.add(growthPage().copy(personaId = "warm")); repo.loadGrowth()
        api.epoch++; repo.refresh(); assertNull(repo.state.value.growth.page)
        api.growthPages.add(growthPage().copy(personaId = "warm", epoch = 1)); repo.loadGrowth()
        api.generation = "two"; repo.refresh(); assertNull(repo.state.value.growth.page)
        api.growthEnabled = false; repo.refresh(); val requests = api.growthRequests.size; repo.loadGrowth()
        assertEquals(requests, api.growthRequests.size)
    }
    @Test fun lateGrowthCannotReviveDismissedScreenOrChangedSession() = runBlocking<Unit> {
        val api = Api().apply { personaId = "losna" }; val store = Store(); val repo = BotRepository(api, store); repo.refresh(); repo.select("losna"); repo.beginGrowth()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforeGrowth = { started.complete(Unit); release.await() }; api.growthPages.add(growthPage())
        val pending = async { repo.loadGrowth() }; started.await(); repo.clearGrowth(close = true); release.complete(Unit); pending.await()
        assertNull(repo.state.value.growth.page); assertFalse(repo.state.value.growth.active); assertFalse(repo.state.value.growth.loading)
        repo.beginGrowth(); api.beforeGrowth = { store.saved = store.saved.copy(session = Session("https://other.example.com/", emptyList())) }
        api.growthPages.add(growthPage()); repo.loadGrowth(); assertNull(repo.state.value.growth.page)
    }
    @Test fun growthFailureWithdrawsContentAndRequiresLoginAfter401() = runBlocking<Unit> {
        val api = Api().apply { personaId = "losna" }; val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginGrowth()
        api.growthPages.add(growthPage()); repo.loadGrowth()
        api.growthError = IOException("offline"); repo.loadGrowth(silent = true)
        assertNull(repo.state.value.growth.page); assertNotNull(repo.state.value.growth.error)
        api.growthError = ApiFailure(401, "expired"); repo.loadGrowth()
        assertNull(repo.state.value.growth.page); assertTrue(repo.state.value.needsLogin)
    }
    @Test fun legacyGrowthReadsFullProjectionWithoutConditionalProtocolAndEntrySwitchClearsIt() = runBlocking<Unit> {
        val api = Api().apply { personaId = "losna" }; api.growthSnapshots = false
        val repo = BotRepository(api, Store()); repo.refresh(); repo.select("losna"); repo.beginGrowth()
        val legacy = growthPage().copy(generation = null, epoch = null, revision = null)
        api.growthPages.add(legacy); repo.loadGrowth(); api.growthPages.add(legacy); repo.loadGrowth(silent = true)
        assertEquals(legacy, repo.state.value.growth.page); assertEquals(listOf(null, null), api.growthRequests)
        repo.select("doll"); assertNull(repo.state.value.growth.page); assertFalse(repo.state.value.growth.active)
    }

}

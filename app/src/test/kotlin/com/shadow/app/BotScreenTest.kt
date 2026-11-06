package com.shadow.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shadow.bot.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = System.currentTimeMillis()
    private val roles = listOf(
        BotRole("assistant", "c1", "小影 · 日常助手", preview = "周末的安排整理好了，随时可以接着聊。", lastMessageAt = now, activeWorks = 2),
        BotRole("reading", "c2", "阅读伙伴", preview = "这本书的三条线索，已经替你整理好了。", lastMessageAt = now - 90_000),
        BotRole("research", "c3", "研究与探索", preview = "有了新的资料，我会接着核对。", lastMessageAt = now - 86_400_000),
        BotRole("creative", "c4", "灵感搭子", preview = "从你刚才的想法继续。", lastMessageAt = now - 2 * 86_400_000),
    )
    private val bot = BotState(connected = true, roles = roles, capabilities = setOf("createRole", "personas", "memories", "workDetails", "artifactText", "attachments"))
    private fun model() = BotViewModel(compose.activity.application)
    private fun screenshot(name: String) {
        System.getProperty("botScreenshots")?.let { directory ->
            val bitmap = compose.runOnIdle {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun contactsSupportSearchRecoveryAndDrafts() {
        val vm = model()
        compose.setContent { BotTheme { BotScreen(ScreenState(bot = bot, drafts = mapOf("creative" to "想试试一种新写法")), vm) } }
        compose.onNodeWithText("草稿 · 想试试一种新写法").assertIsDisplayed()
        screenshot("contacts-393")
        compose.onNodeWithText("进行中 1").performClick()
        compose.onNodeWithText("阅读伙伴").assertDoesNotExist()
        compose.onNodeWithText("全部 4").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("不存在的伙伴")
        compose.onNodeWithText("没有找到相关伙伴").assertIsDisplayed()
        compose.onNodeWithText("查看全部伙伴").performClick()
        compose.onNodeWithText("阅读伙伴").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun narrowChatKeepsComposerAndMenuReachable() {
        val vm = model()
        val state = ScreenState(bot = bot.copy(selected = "assistant", messages = listOf(
            Message("1", "user", "帮我整理一下这个周末想做的事。", now - 120_000),
            Message("2", "assistant", "已经整理成一份清单。先把最想做的两件事留出来，其余可以慢慢安排。", now - 60_000, "小影"),
            Message("3", "user", "好，先从阅读开始。", now),
        )), destination = Destination.Chat)
        compose.setContent { BotTheme { BotScreen(state, vm) } }
        compose.onNodeWithContentDescription("消息输入框").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送消息").assertIsDisplayed().assertIsNotEnabled()
        screenshot("chat-320")
        compose.onNodeWithContentDescription("聊天选项").performClick()
        compose.onNodeWithText("角色与人设").assertIsDisplayed()
        compose.onNodeWithText("共享记忆").assertIsDisplayed()
    }

    @Test fun incomingMessagesDoNotInterruptHistoryAndJumpReturnsToLatest() {
        val vm = model()
        val messages = (1..40).map { Message("$it", if (it % 2 == 0) "assistant" else "user", "历史消息 $it：一起把这件事情慢慢做好。", now + it, "小影") }
        val state = mutableStateOf(ScreenState(bot = bot.copy(selected = "assistant", messages = messages), destination = Destination.Chat))
        compose.setContent { BotTheme { BotScreen(state.value, vm) } }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performTouchInput { swipeDown(durationMillis = 500) }
        list.performTouchInput { swipeDown(durationMillis = 500) }
        compose.onNodeWithText("回到最新").assertIsDisplayed()
        val visibleBefore = compose.onAllNodes(hasText("历史消息", substring = true)).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
        compose.runOnIdle { state.value = state.value.copy(bot = state.value.bot.copy(messages = messages + Message("new", "assistant", "刚到的新消息", now + 100))) }
        compose.onNodeWithText("回到最新").assertIsDisplayed()
        compose.onNodeWithText("刚到的新消息").assertDoesNotExist()
        val visibleAfter = compose.onAllNodes(hasText("历史消息", substring = true)).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
        assertTrue(visibleBefore.any { it in visibleAfter })
        compose.onNodeWithText("回到最新").performClick()
        compose.onNodeWithText("刚到的新消息").assertIsDisplayed()
    }

    @Test fun workFiltersAndResultsRemainReadable() {
        val vm = model()
        val running = Work("w1", "assistant", "整理一份周末阅读计划", "running", "已选出两篇适合周末阅读的文章。", "正在补充阅读顺序", version = 1)
        val stopped = running.copy(id = "w2", goal = "上一轮计划", state = "superseded")
        val state = mutableStateOf(ScreenState(bot = bot.copy(selected = "assistant", works = listOf(running, stopped)), destination = Destination.Progress))
        compose.setContent { BotTheme { BotScreen(state.value, vm) } }
        compose.onNodeWithText("进行中 1").performClick()
        compose.onNodeWithText("上一轮计划").assertDoesNotExist()
        compose.onNodeWithText("已结束 1").performClick()
        compose.onNodeWithText("上一轮计划").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(destination = Destination.WorkDetail, viewedWorkId = "w1", bot = state.value.bot.copy(workDetail = WorkDetail(
            running, listOf(PlanStep("收集适合周末读的文章", "completed"), PlanStep("安排阅读顺序", "in_progress")),
            listOf(WorkRun("r", "running", now, 0, "正在核对文章", false)),
            listOf(Artifact("a", "周末阅读清单.txt", 1536, "text/plain", true)), setOf("work.resume", "work.cancel"), false, false))) }
        compose.onNodeWithText("周末阅读清单.txt").assertIsDisplayed()
        screenshot("work-393")
    }
    @Test fun attachmentDraftsBlockIncompleteSendsAndOfferBothPickers() {
        val vm = model()
        val original = Attachment("upload-file-001", "阅读材料.pdf", "application/pdf", 18400, "a".repeat(64), "assetv_original")
        val ready = AttachmentDraft(original.id, "content://synthetic/file", original.name, "ready", uploaded = original)
        val failed = AttachmentDraft("upload-file-002", "content://synthetic/image", "周末照片.png", "failed", "网络中断，请重试。")
        val state = mutableStateOf(ScreenState(bot = bot.copy(selected = "assistant", messages = listOf(
            Message("attachment-message", "user", "这份材料可以一起参考。", now, attachments = listOf(original))
        )), destination = Destination.Chat, attachmentDrafts = mapOf("assistant" to listOf(ready, failed))))
        compose.setContent { BotTheme { BotScreen(state.value, vm) } }
        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
        compose.onNodeWithContentDescription("重试上传 周末照片.png").assertIsDisplayed()
        screenshot("attachments-393")
        compose.runOnIdle { state.value = state.value.copy(attachmentDrafts = mapOf("assistant" to listOf(ready))) }
        compose.onNodeWithContentDescription("发送消息").assertIsEnabled()
        compose.onNodeWithContentDescription("添加图片或文件").performClick()
        compose.onNodeWithText("选择图片").assertIsDisplayed()
        compose.onNodeWithText("选择文件").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun memoryActionsStayInlineAndShowOriginalSources() {
        val vm = model()
        val item = MemoryItem("memory", "周末喜欢读历史书。", "preference", 2)
        val state = ScreenState(bot = bot.copy(selected = "assistant", capabilities = bot.capabilities + "memoryActions",
            memoryBrowser = MemoryBrowserState(items = listOf(item), detail = MemoryDetail(item, listOf(MemorySource("source", "周末我想读一点历史。", "2026-11-06", false))))), destination = Destination.Memories)
        compose.setContent { BotTheme { BotScreen(state, vm) } }
        compose.onNodeWithText("更正").performClick()
        compose.onNodeWithText("保存更正").assertIsDisplayed()
        screenshot("memory-edit-320")
        compose.onNodeWithText("遗忘").performClick()
        compose.onNodeWithText("遗忘相关内容").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun memorySearchAndTypeFilterFitNarrowScreens() {
        val vm = model()
        val state = ScreenState(bot = bot.copy(selected = "assistant", capabilities = bot.capabilities + setOf("memoryActions", "memoryBrowse"),
            memoryBrowser = MemoryBrowserState(active = true, query = "茶", kind = "preference", loaded = true, revision = "r1", next = "next",
                items = listOf(MemoryItem("tea", "周末读书时喜欢喝红茶。", "preference", 1)))), destination = Destination.Memories)
        compose.setContent { BotTheme { BotScreen(state, vm) } }
        compose.onNodeWithText("搜索全部有效记忆").assertIsDisplayed()
        compose.onNodeWithText("周末读书时喜欢喝红茶。").assertIsDisplayed()
        compose.onNodeWithText("加载更多记忆").performScrollTo().assertIsDisplayed()
        screenshot("memory-search-320")
        compose.onAllNodesWithText("偏好")[0].performClick()
        compose.onNodeWithText("虚构情境").assertIsDisplayed()
    }
    @Test fun memoryEmptyAndLegacyStatesAreExplicit() {
        val vm = model()
        val state = mutableStateOf(ScreenState(bot = bot.copy(capabilities = bot.capabilities + "memoryBrowse",
            memoryBrowser = MemoryBrowserState(active = true, query = "不存在", loaded = true)), destination = Destination.Memories))
        compose.setContent { BotTheme { BotScreen(state.value, vm) } }
        compose.onNodeWithText("没有找到相关记忆，试试其他关键词或类型。").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(bot = bot.copy(memoryBrowser = MemoryBrowserState(active = true, loaded = true))) }
        compose.onNodeWithText("当前服务显示最近 100 条有效记忆；升级服务后可搜索和翻页。").assertIsDisplayed()
        compose.onNodeWithText("搜索全部有效记忆").assertDoesNotExist()
    }
    @Test fun archivedContactsDoNotDisappearAndSearchResultsShowFullText() {
        val vm = model()
        val state = mutableStateOf(ScreenState(bot = bot.copy(roles = roles.map { if (it.id == "reading") it.copy(archived = true) else it }, capabilities = bot.capabilities + "contactArchive")))
        compose.setContent { BotTheme { BotScreen(state.value, vm) } }
        compose.onNodeWithText("阅读伙伴").assertDoesNotExist()
        compose.onNodeWithText("已归档").performClick()
        compose.onNodeWithText("阅读伙伴").assertIsDisplayed()
        screenshot("archive-393")
        compose.runOnIdle { state.value = state.value.copy(destination = Destination.Search, bot = bot.copy(selected = "assistant", searchQuery = "历史", searchDone = true,
            searchResults = listOf(Message("old", "assistant", "这条历史消息可以直接阅读和复制。", now, "小影")), searchCursor = "more")) }
        compose.onNodeWithText("这条历史消息可以直接阅读和复制。").assertIsDisplayed()
        compose.onNodeWithText("更多结果").assertIsDisplayed()
        screenshot("history-search-393")
    }
    @Test fun fileResultsOfferSaveAndShareAlongsidePreview() {
        val vm = model()
        val work = Work("report", "assistant", "整理阅读报告", "completed", "报告已完成。", "", 1)
        val state = ScreenState(bot = bot.copy(selected = "assistant", capabilities = bot.capabilities + "artifactDownload",
            workDetail = WorkDetail(work, emptyList(), emptyList(), listOf(Artifact("pdf", "阅读报告.pdf", 1024, "application/pdf", false, "a".repeat(64), true)), emptySet(), false, false)),
            destination = Destination.WorkDetail, viewedWorkId = work.id)
        compose.setContent { BotTheme { BotScreen(state, vm) } }
        compose.onNodeWithText("保存").assertIsDisplayed(); compose.onNodeWithText("分享").assertIsDisplayed()
        screenshot("artifact-export-393")
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun personaCardsDuplicateRemoveAndKeepSaveReachable() {
        val original = PersonaSettings("assistant", 1, "default", true,
            listOf(PersonaCard("default", "默认", "小影", "自然交流", true)))
        val draft = mutableStateOf(original)
        var saved: PersonaSettings? = null
        compose.setContent { BotTheme { androidx.compose.material3.Surface { PersonaForm(draft.value, false, { draft.value = it }, { saved = it }, {}, {}, {}) } } }
        compose.onNodeWithText("导入人设").assertIsDisplayed()
        compose.onNodeWithText("移除").assertIsNotEnabled()
        compose.onNodeWithText("复制当前卡").performClick()
        compose.onNode(hasText("默认 (2)") and !hasSetTextAction()).assertIsDisplayed()
        screenshot("persona-cards-320")
        compose.onNodeWithText("移除").performClick()
        assertEquals(original, draft.value)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保存人设"))
        compose.onNodeWithText("保存人设").assertIsEnabled().performClick()
        assertEquals(original, saved)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun contextLocatesAnchorAndPrependingPreservesReadingPosition() {
        val rows = (1..40).map { Message("$it", "user", "历史消息 $it：周末一起阅读。", now + it) }
        val page = mutableStateOf(MessageContextPage("host", "assistant", 0, MessageRef("20", "user"), rows, "older", "newer"))
        compose.setContent { BotTheme { androidx.compose.material3.Surface {
            MessageContextView(page.value, "阅读", false, false, {}, {})
        } } }
        compose.onNodeWithText(rows[19].text).assertIsDisplayed()
        screenshot("history-context-320")
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(25)
        compose.onNodeWithText(rows[24].text).assertIsDisplayed()
        compose.runOnIdle { page.value = page.value.copy(items = (1..10).map { Message("old$it", "user", "更早消息 $it", now - it) } + rows) }
        compose.onNodeWithText(rows[24].text).assertIsDisplayed()
        compose.onNodeWithText("定位消息", substring = false).performClick()
        compose.onNodeWithText(rows[19].text).assertIsDisplayed()
    }

    @Test fun returningFromContextPreservesSearchScrollPosition() {
        val rows = (1..40).map { Message("$it", "user", "搜索结果 $it：周末阅读。", now + it) }
        val vm = model()
        val state = mutableStateOf(ScreenState(bot = bot.copy(selected = "assistant", searchQuery = "阅读", searchDone = true,
            searchResults = rows, capabilities = bot.capabilities + "messageContext"), destination = Destination.Search))
        compose.setContent { BotTheme { BotScreen(state.value, vm) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.onNodeWithText(rows[20].text).assertIsDisplayed()
        screenshot("history-search-context-393")
        compose.runOnIdle { state.value = state.value.copy(destination = Destination.MessageContext, bot = state.value.bot.copy(
            messageContext = MessageContextPage("host", "assistant", 0, MessageRef("21", "user"), rows, null, null))) }
        compose.onNodeWithText(rows[20].text).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(destination = Destination.Search, bot = state.value.bot.copy(messageContext = null)) }
        compose.onNodeWithText(rows[20].text).assertIsDisplayed()
        compose.onNodeWithText(rows[0].text).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun unifiedLoginIsPrimaryAndLegacySecretsStayCollapsed() {
        val vm = model()
        compose.setContent { BotTheme { BotScreen(ScreenState(destination = Destination.Settings, baseUrl = "https://example.com/dsh/"), vm) } }
        compose.onNodeWithText("使用统一账号登录").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("当前启动口令").assertDoesNotExist()
        screenshot("oidc-login-320")
        compose.onNodeWithText("使用启动口令连接").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("当前启动口令"))
        compose.onNodeWithText("当前启动口令").assertIsDisplayed()
    }

}

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

}

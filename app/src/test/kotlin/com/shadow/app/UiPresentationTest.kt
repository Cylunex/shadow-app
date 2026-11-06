package com.shadow.app

import com.shadow.bot.Work
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UiPresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun contactTimesUseLocalCalendarDay() {
        val now = at("2026-11-06T00:00:00Z")
        assertEquals("07:59", contactTime(at("2026-11-05T23:59:00Z"), now, zone))
        assertEquals("昨天", contactTime(at("2026-11-05T15:59:00Z"), now, zone))
        assertEquals("11月4日", contactTime(at("2026-11-04T00:00:00Z"), now, zone))
        assertEquals("2025年11月4日", contactTime(at("2025-11-04T00:00:00Z"), now, zone))
        assertEquals("", contactTime(0, now, zone))
    }

    @Test fun yesterdaySurvivesYearBoundaryAndDaylightSavingTime() {
        assertEquals("昨天", dayLabel(at("2025-12-31T15:59:00Z"), at("2025-12-31T16:00:00Z"), zone))
        val ny = ZoneId.of("America/New_York")
        assertEquals("昨天", dayLabel(at("2026-11-01T04:30:00Z"), at("2026-11-02T05:30:00Z"), ny))
    }

    @Test fun stoppedAndReplacedWorksAreNotOngoing() {
        fun work(state: String) = Work("w", "entry", "goal", state, "", "")
        listOf("completed", "cancelled", "failed", "excluded", "interrupted", "superseded").forEach { assertFalse(it, work(it).isOngoing()) }
        listOf("queued", "running", "waiting").forEach { assertTrue(it, work(it).isOngoing()) }
        assertTrue(work("waiting").copy(paused = true).isOngoing())
    }
    @Test fun withdrawnAttachmentPreviewDoesNotSurviveAReplacementSnapshot() {
        val attachment = com.shadow.bot.Attachment("upload-original", "note.txt", "text/plain", 1, "a".repeat(64), "assetv_original")
        val state = ScreenState(bot = com.shadow.bot.BotState(selected = "one", messages = listOf(com.shadow.bot.Message("m", "user", "", 1, attachments = listOf(attachment)))))
        assertTrue(visibleAttachment(state, attachment.id, "one"))
        assertFalse(visibleAttachment(state, attachment.id, "two"))
        assertFalse(visibleAttachment(state.copy(bot = state.bot.copy(messages = emptyList())), attachment.id, "one"))
    }

    @Test fun speechChunkingKeepsOriginalTextAndUnicodeBoundaries() {
        val text = "一起读书。".repeat(1000) + "🙂".repeat(1000)
        val chunks = speechChunks(text, 121)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 121 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
    @Test fun backgroundChecksDoNotNotifyReadRepliesOwnMessagesOrOldWork() {
        val item = com.shadow.bot.InboxItem("entry", "private text", 1, 0, "new-reply", "assistant", 20)
        assertEquals("message", notificationKind(item, "old", 10, "old", 10))
        assertNull(notificationKind(item, "old", 10, "new-reply", 20))
        assertNull(notificationKind(item.copy(messageRole = "user", workSequence = 10), "old", 10, "old", 10))
        assertEquals("work", notificationKind(item.copy(messageRole = "user"), "old", 10, "old", 10))
        assertNull(notificationKind(item, "new-reply", 20, "old", 10))
    }
    @Test fun searchHighlightIsLiteralCaseInsensitiveAndBounded() {
        val style = androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        val text = "A.* a.* 中文🙂"
        val result = highlightedMessage(text, "a.*", style)
        assertEquals(text, result.text)
        assertEquals(listOf(0 to 3, 4 to 7), result.spanStyles.map { it.start to it.end })
        assertTrue(highlightedMessage(text, "", style).spanStyles.isEmpty())
        assertEquals(500, highlightedMessage("a".repeat(1000), "a", style).spanStyles.size)
    }

}

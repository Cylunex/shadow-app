package com.shadow.bot

import org.junit.Assert.*
import org.junit.Test

class GrowthTest {
    private val page = GrowthPage("losna", "losna", "合成伙伴",
        listOf(GrowthHabit("回复简短", "以后短点", "persistent")),
        listOf(GrowthView("留白", "我喜欢保留留白", "developing", "材料中的安静片段", "合成材料")))

    @Test fun discussionAppendsEditableTextWithoutReplacingExistingDraft() {
        val existing = "还没说完的另一件事"
        val habit = SharedText.append(existing, page.discussion("habit", 0))
        assertTrue(habit.startsWith(existing + "\n\n")); assertTrue(habit.contains("依据原话：以后短点"))
        val view = SharedText.append(existing, page.discussion("view", 0))
        assertTrue(view.contains("你的看法：我喜欢保留留白")); assertTrue(view.contains("材料摘录：材料中的安静片段"))
        assertFalse(view.contains("已发送")); assertFalse(view.contains("已修改"))
    }
    @Test fun missingItemAndOversizedMergeCannotSilentlyDiscardDraftText() {
        for ((kind, index) in listOf("habit" to 1, "view" to -1, "unknown" to 0)) {
            try { page.discussion(kind, index); fail("missing item") } catch (_: IllegalArgumentException) { }
        }
        var draft = "x".repeat(4000)
        try { draft = SharedText.append(draft, page.discussion("habit", 0)); fail("oversized merge") } catch (_: IllegalArgumentException) { }
        assertEquals("x".repeat(4000), draft)
    }
}

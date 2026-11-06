package com.shadow.bot

data class GrowthHabit(val text: String, val quote: String, val scope: String)
data class GrowthView(val topic: String, val text: String, val maturity: String, val evidenceQuote: String, val sourceTitle: String)
data class GrowthPage(val entryId: String, val personaId: String, val personaName: String,
    val habits: List<GrowthHabit>, val views: List<GrowthView>, val generation: String? = null,
    val epoch: Long? = null, val revision: String? = null, val unchanged: Boolean = false)
data class GrowthState(val active: Boolean = false, val page: GrowthPage? = null, val loading: Boolean = false, val error: String? = null)

/** A user-selected discussion seed, never an automatic instruction to change memory. */
fun GrowthPage.discussion(kind: String, index: Int): String = when (kind) {
    "habit" -> habits.getOrNull(index)?.let { "想聊聊这条相处习惯：${it.text}\n依据原话：${it.quote}" }
    "view" -> views.getOrNull(index)?.let { "想继续聊聊「${it.topic}」。\n你的看法：${it.text}\n材料摘录：${it.evidenceQuote}" }
    else -> null
} ?: throw IllegalArgumentException("这条手记已变化，请重新查看。")

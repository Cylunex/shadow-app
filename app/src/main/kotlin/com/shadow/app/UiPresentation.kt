package com.shadow.app

import com.shadow.bot.Work
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun Work.isOngoing() = state !in setOf("completed", "cancelled", "failed", "excluded", "interrupted", "superseded")

internal fun dayLabel(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (at <= 0) return ""
    val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "M月d日" else "yyyy年M月d日"))
    }
}

internal fun contactTime(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val day = dayLabel(at, now, zone)
    return if (day == "今天") Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) else day
}

internal fun visibleAttachment(state: ScreenState, id: String, entry: String?): Boolean = entry != null && (
    state.attachmentDrafts[entry].orEmpty().any { it.uploaded?.id == id } ||
    state.bot.pending.any { it.entryId == entry && it.attachments.any { attachment -> attachment.id == id } } ||
    state.bot.selected == entry && state.bot.messages.any { it.attachments.any { attachment -> attachment.id == id } }
)

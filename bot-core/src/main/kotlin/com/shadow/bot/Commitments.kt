package com.shadow.bot

data class Commitment(val id: String, val entryId: String, val goal: String, val state: String, val kind: String,
    val completion: String, val version: Long, val result: String, val resultTruncated: Boolean,
    val waitKind: String, val waitReason: String, val nextCheckAt: Long?, val workId: String?, val allowedActions: Set<String>)
data class CommitmentPage(val entryId: String, val generation: String, val epoch: Long, val revision: String,
    val items: List<Commitment>, val hasMore: Boolean, val unchanged: Boolean = false)
data class CommitmentState(val active: Boolean = false, val page: CommitmentPage? = null, val loading: Boolean = false, val error: String? = null)
val commitmentActions = setOf("commitment.pause", "commitment.resume", "commitment.update", "commitment.cancel", "commitment.complete")
fun commitmentNeedsText(action: String) = action in setOf("commitment.resume", "commitment.update")
fun commitmentActionLabel(action: String) = when (action) {
    "commitment.pause" -> "暂停跟进"
    "commitment.resume" -> "继续跟进"
    "commitment.update" -> "更新目标并继续"
    "commitment.cancel" -> "停止跟进"
    "commitment.complete" -> "确认目标已完成"
    else -> "持续事项"
}

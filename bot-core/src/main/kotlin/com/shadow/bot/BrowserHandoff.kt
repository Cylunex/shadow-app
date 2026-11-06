package com.shadow.bot

// Website content and entered values are transient. Never add them to SavedState,
// OfflineCache or the ordinary chat/work retry queue.
data class BrowserSession(val id: String, val workId: String, val entryId: String, val version: Long, val goal: String)
data class BrowserSessions(val entryId: String, val generation: String, val epoch: Long, val items: List<BrowserSession>)
data class BrowserControl(val ref: String, val label: String, val tag: String, val type: String, val fillable: Boolean, val disabled: Boolean)
data class BrowserView(val sessionId: String, val workId: String, val entryId: String, val generation: String, val epoch: Long,
    val version: Long?, val state: String, val title: String = "", val text: String = "", val screenshot: String = "", val controls: List<BrowserControl> = emptyList())
data class BrowserHandoffState(val active: Boolean = false, val sessions: List<BrowserSession> = emptyList(),
    val view: BrowserView? = null, val loading: Boolean = false, val loaded: Boolean = false, val error: String? = null, val notice: String? = null, val followupWorkId: String? = null)

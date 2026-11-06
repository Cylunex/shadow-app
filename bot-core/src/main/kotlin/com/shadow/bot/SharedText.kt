package com.shadow.bot

/** An external share only becomes an editable draft after choosing its recipient. */
object SharedText {
    fun read(action: String?, type: String?, text: CharSequence?): String? {
        if (action !in setOf("android.intent.action.SEND", "android.intent.action.PROCESS_TEXT") || type != "text/plain") return null
        val value = text?.toString()?.trim().orEmpty()
        require(value.length <= 4000) { "分享内容超过 4000 字，请选择较短的片段。" }
        return value.takeIf(String::isNotBlank)
    }
    fun append(draft: String, shared: String): String {
        val combined = if (draft.isBlank()) shared else "$draft\n\n$shared"
        require(combined.length <= 4000) { "已有草稿加分享内容超过 4000 字，请先整理草稿。" }
        return combined
    }
}

package com.shadow.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/** Explicit foreground reading. Audio focus loss, navigation and typing stop playback. */
internal class DeviceSpeech(context: Context, private val status: (Boolean, String?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) stop() }.build()
    private var ready = false
    private var pending: String? = null
    private var session: String? = null
    private var closed = false
    private val engine = TextToSpeech(context) { result -> handler.post {
        if (!closed) {
            ready = result == TextToSpeech.SUCCESS
            if (ready) {
                engineReady()
                val text = pending; pending = null
                if (text != null) speak(text)
            } else { pending = null; status(false, "手机语音引擎暂不可用，请在系统设置中安装语音服务。") }
        }
    } }
    private fun engineReady() {
        engine.setAudioAttributes(attributes)
        engine.language = Locale.getDefault()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { handler.post { if (utteranceId == session) stop() } }
            @Deprecated("Platform callback") override fun onError(utteranceId: String?) { handler.post { if (session != null && utteranceId?.startsWith(session!!) == true) { stop(); status(false, "朗读未完成，请检查系统语音服务。") } } }
        })
    }
    fun speak(text: String) {
        if (closed) return
        if (!ready) { pending = text; status(true, null); return }
        stop()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status(false, "当前无法播放语音，请稍后重试。"); return }
        val token = UUID.randomUUID().toString(); session = token
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3000))
        status(true, null)
        chunks.forEachIndexed { index, chunk ->
            val id = if (index == chunks.lastIndex) token else "$token:$index"
            if (engine.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                stop(); status(false, "朗读未完成，请检查系统语音服务。"); return
            }
        }
    }
    fun stop() { pending = null; session = null; if (ready) engine.stop(); audio.abandonAudioFocusRequest(focus); status(false, null) }
    fun close() { stop(); closed = true; engine.shutdown() }
}

internal fun speechChunks(text: String, max: Int = 3000): List<String> {
    require(max >= 2)
    val chunks = mutableListOf<String>(); var start = 0
    while (start < text.length) {
        var end = (start + max).coerceAtMost(text.length)
        if (end < text.length) {
            val boundary = text.substring(start, end).indexOfLast { it in "。！？.!?\n" }
            if (boundary >= max / 2) end = start + boundary + 1
            if (text[end - 1].isHighSurrogate()) end--
        }
        chunks += text.substring(start, end); start = end
    }
    return chunks
}

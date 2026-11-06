package com.shadow.app

import android.content.Context
import android.media.*
import com.shadow.bot.*
import kotlinx.coroutines.*

/** Foreground-only playback. No audio file, URL credential, or automatic replay is retained. */
internal class CompanionPlayback(context: Context, private val api: ProductApi, private val grant: DeviceGrant,
    private val scope: CoroutineScope, private val status: (Boolean, String?) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN && (playback != null || job?.isActive == true)) stop() }.build()
    private var player: MediaPlayer? = null
    private var bytes: MemoryAudio? = null
    private var playback: Playback? = null
    private var job: Job? = null
    private var attempt = 0
    fun play(entryId: String, messageId: String) {
        stop(); val ticket = ++attempt
        status(true, null)
        job = scope.launch {
            try {
                val previous = api.playback(entryId)
                val claim = api.playbackCommand(previous, "play", messageId)
                ensureActive(); if (ticket != attempt || !claim.ownedBy(grant)) return@launch
                playback = claim
                val audio = api.playbackAudio(claim)
                ensureActive(); if (ticket != attempt) return@launch
                // Recheck ownership after download, before sound is possible.
                val checked = api.playback(entryId)
                if (!samePlayback(claim, checked, grant.id)) throw ApiFailure(409, "语音已转到另一设备。")
                if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw IllegalStateException("当前无法播放语音。")
                val source = MemoryAudio(audio); bytes = source
                val media = MediaPlayer(); player = media
                media.setAudioAttributes(attributes); media.setDataSource(source)
                media.setOnPreparedListener {
                    if (ticket != attempt || player !== media) return@setOnPreparedListener
                    if (claim.positionMs > 0) {
                        media.setOnSeekCompleteListener { if (ticket == attempt && player === media) media.start() }
                        media.seekTo(claim.positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                    } else media.start()
                }
                media.setOnCompletionListener { if (ticket == attempt) stop("finish") }
                media.setOnErrorListener { _, _, _ -> if (ticket == attempt) { stop(); status(false, "音频未能播放，请重试或使用手机朗读。") }; true }
                media.prepareAsync()
                while (isActive && ticket == attempt) {
                    delay(1500)
                    val current = playback ?: break
                    val position = maxOf(current.positionMs, runCatching { media.currentPosition }.getOrDefault(current.positionMs))
                    val receipt = api.playbackCommand(current, "progress", positionMs = position)
                    if (!samePlayback(current, receipt, grant.id)) throw ApiFailure(409, "语音已转到另一设备。")
                    playback = receipt
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (ticket == attempt) { stop(); status(false, if (error is ApiFailure) error.message else "伙伴音色暂不可用，请重试或使用手机朗读。") }
            }
        }
    }
    fun stop(action: String = "pause") {
        ++attempt
        val current = playback
        val position = maxOf(current?.positionMs ?: 0, runCatching { player?.currentPosition ?: 0 }.getOrDefault(0))
        playback = null; job?.cancel(); job = null
        player?.release(); player = null; bytes?.close(); bytes = null
        manager.abandonAudioFocusRequest(focus); status(false, null)
        if (current != null) scope.launch { try { api.playbackCommand(current, action, positionMs = position) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Lease expires; never resend an uncertain write. */ } }
    }
}

internal fun samePlayback(expected: Playback, actual: Playback, deviceId: String) =
    actual.state == "playing" && actual.deviceId == deviceId && actual.audioId == expected.audioId && actual.revision == expected.revision && actual.generation == expected.generation && actual.epoch == expected.epoch

internal class MemoryAudio(private var bytes: ByteArray) : MediaDataSource() {
    @Synchronized override fun getSize() = bytes.size.toLong()
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0 || position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt()); bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count); return count
    }
    @Synchronized override fun close() { bytes.fill(0); bytes = ByteArray(0) }
}

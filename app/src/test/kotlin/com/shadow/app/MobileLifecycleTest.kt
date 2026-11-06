package com.shadow.app

import android.app.Application
import com.shadow.bot.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MobileLifecycleTest {
    @Test fun audioIsBoundedRandomAccessAndErasedOnRelease() {
        val original = byteArrayOf(1, 2, 3, 4)
        val audio = MemoryAudio(original); val output = ByteArray(5)
        assertEquals(2, audio.readAt(2, output, 1, 4)); assertArrayEquals(byteArrayOf(0, 3, 4, 0, 0), output)
        assertEquals(-1, audio.readAt(-1, output, 0, 1)); assertEquals(-1, audio.readAt(4, output, 0, 1))
        audio.close(); assertEquals(0L, audio.size); assertArrayEquals(ByteArray(4), original)
        assertEquals(-1, audio.readAt(0, output, 0, 1))
    }
    @Test fun everyPlaybackIdentityChangeWithdrawsThePreviousPlayer() {
        val playing = Playback("losna", "host", 3, "playing", "reply", "phone", "audio", epoch = 7)
        assertTrue(samePlayback(playing, playing.copy(positionMs = 1000), "phone"))
        for (other in listOf(playing.copy(generation = "new"), playing.copy(deviceId = "other"), playing.copy(audioId = "other"), playing.copy(revision = 4), playing.copy(state = "paused"), playing.copy(epoch = 8)))
            assertFalse(samePlayback(playing, other, "phone"))
    }
    @Test fun oldDistributorCallbacksCannotReplaceAnotherAccountsSubscription() {
        val row = JSONObject().put("enabled", true).put("session", "new-session").put("instance", "new-session").put("status", "ready").put("revision", 5)
        assertTrue(RealtimePush.accepts(row, "new-session", "new-session"))
        assertFalse(RealtimePush.accepts(row, "new-session", "old-session"))
        assertFalse(RealtimePush.accepts(row, "changed-account", "new-session"))
        assertFalse(RealtimePush.accepts(row, null, "new-session"))
        row.put("enabled", false)
        assertFalse(RealtimePush.accepts(row, "new-session", "new-session"))
    }
}

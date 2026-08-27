package com.nuvio.tv.playback.mediasession

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.ui.PlaybackSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CleanMediaSessionOwnerTest {
    @Test
    fun `owner releases the clean session exactly once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var sessionReleaseCount = 0
        val controller = PlaybackSessionController(
            snapshot = MutableStateFlow(PlaybackSnapshot()),
            dispatchCommand = {},
            releaseSession = { sessionReleaseCount++ },
        )
        val owner = CleanMediaSessionOwner.create(
            context = RuntimeEnvironment.getApplication(),
            applicationLooper = Looper.getMainLooper(),
            parentScope = scope,
            controller = controller,
            metadata = CleanMediaSessionMetadata.fromIngress("ab12ab12ab12ab12", "Live TV"),
        )

        owner.release()
        owner.release()

        assertEquals(1, sessionReleaseCount)
        scope.cancel()
    }

    @Test
    fun `platform facade release failure is nonfatal after controller barrier succeeds`() = runBlocking {
        var platformReleaseCount = 0
        var sessionReleaseCount = 0
        val controller = controller {
            sessionReleaseCount += 1
        }
        val owner = CleanMediaSessionOwner.createForTest(
            applicationLooper = Looper.getMainLooper(),
            controller = controller,
            platformReleaser = {
                platformReleaseCount += 1
                error("synthetic platform failure")
            },
        )

        owner.release()
        owner.release()

        assertEquals(1, platformReleaseCount)
        assertEquals(1, sessionReleaseCount)
    }

    @Test
    fun `controller barrier failure remains retryable without repeating platform release`() = runBlocking {
        var platformReleaseCount = 0
        var sessionReleaseCount = 0
        val controller = controller {
            sessionReleaseCount += 1
            if (sessionReleaseCount == 1) error("synthetic barrier failure")
        }
        val owner = CleanMediaSessionOwner.createForTest(
            applicationLooper = Looper.getMainLooper(),
            controller = controller,
            platformReleaser = { platformReleaseCount += 1 },
        )

        val firstFailure = runCatching { owner.release() }.exceptionOrNull()
        owner.release()
        owner.release()

        assertTrue(firstFailure is IllegalStateException)
        assertEquals(1, platformReleaseCount)
        assertEquals(2, sessionReleaseCount)
    }

    private fun controller(release: suspend () -> Unit) = PlaybackSessionController(
        snapshot = MutableStateFlow(PlaybackSnapshot()),
        dispatchCommand = {},
        releaseSession = release,
    )
}

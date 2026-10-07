package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.contracts.OwnSourcePolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wave 3 / J4 privacy gate: a media server's own items (`ms:` ids) never reach Trakt / Simkl / MDBList (owner decision
 * 2026-10-06, v1). Not as a scrobble, and not by title either - a reference built from such an item is not resolvable
 * by any writer. A TMDB title merely PLAYED from a server is a different content id and is unaffected.
 */
class ExcludedSourceTrackingTest {
    private val msId = "ms:jellyfin:6f3c1a9e:0f1e2d3c:movie:abc"

    @Before
    fun wire() {
        OwnSourcePolicy.resetForTest()
        OwnSourcePolicy.registerScrobbleExclusion("mediaserver") { it.startsWith("ms:") }
    }

    @After
    fun unwire() = OwnSourcePolicy.resetForTest()

    private fun reference(parent: String, video: String? = null) =
        buildTrackingMediaReference(contentType = "movie", parentMetaId = parent, videoId = video, title = "Heat", releaseInfo = "1995")

    @Test
    fun aServerItemIsNotResolvableEvenByTitle() {
        val ref = reference(msId)
        assertTrue(ref.isFromExcludedSource)
        assertFalse("not by title either", ref.hasResolvableIdentity)
        // an episode's own id is excluded too, whichever of the two ids carries the namespace
        assertFalse(reference("ms:emby:m:u:series:s1", "ms:emby:m:u:episode:e1").hasResolvableIdentity)
        assertFalse(reference("tt0113277", "ms:jellyfin:m:u:episode:e1").hasResolvableIdentity)
    }

    @Test
    fun everyOtherItemKeepsItsIdentity() {
        assertTrue(reference("tt0113277").hasResolvableIdentity)
        assertTrue(reference("tmdb:949").hasResolvableIdentity)
        assertFalse(reference("tt0113277").isFromExcludedSource)
    }

    @Test
    fun noScrobblerIsEverCalledForAServerItem() = runBlocking {
        var calls = 0
        val scrobbler = object : TrackingScrobbler {
            override val providerId = TrackingProviderId.TRAKT
            override val seekScrobblePolicy = TrackingSeekScrobblePolicy.NONE
            override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) { calls++ }
        }
        val serverEvent = TrackingScrobbleEvent(reference(msId), 42.0)
        assertTrue(dispatchTrackingScrobble(listOf(scrobbler), TrackingScrobbleAction.START, serverEvent).isEmpty())
        assertTrue(dispatchTrackingSeekScrobble(listOf(scrobbler), TrackingScrobbleAction.STOP, serverEvent).isEmpty())
        assertEquals("a server item never scrobbles", 0, calls)
        dispatchTrackingScrobble(listOf(scrobbler), TrackingScrobbleAction.START, TrackingScrobbleEvent(reference("tt0113277"), 10.0))
        assertEquals("an ordinary item still does", 1, calls)
    }
}

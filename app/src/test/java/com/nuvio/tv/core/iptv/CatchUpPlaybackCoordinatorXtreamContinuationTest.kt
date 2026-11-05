package com.nuvio.tv.core.iptv

import androidx.media3.common.PlaybackException
import com.nuvio.tv.core.contracts.CatchUpContinuationPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Xtream replay used to ask the panel for exactly the programme, so it stopped at the credits.
 * M3U replays already ask for the show plus what follows (CatchUpContinuationPolicy) and the player
 * follows the playhead into the next shows; these pin the same for Xtream, through the same walk.
 */
class CatchUpPlaybackCoordinatorXtreamContinuationTest {

    // 2026-10-03 19:00:00 UTC; the programme finished an hour before "now".
    private val start = 1_791_054_000_000L
    private val end = start + 60 * 60_000L
    private val now = end + 60 * 60_000L
    private val programme = CatchUpPlaybackCoordinator.Programme("Show", start, end)
    private val liveUrl = "http://panel.invalid/live/user/pass/7.ts"
    private val account = XtreamAccount(id = "acc", name = "P", baseUrl = "http://panel.invalid", username = "user", password = "pass")

    private class Memory : CatchUpWinnerStore.Persistence {
        var stored: Map<String, String> = emptyMap()
        override fun load(): Map<String, String> = stored
        override fun save(entries: Map<String, String>) { stored = entries.toMap() }
    }

    private fun coordinator() = CatchUpPlaybackCoordinator(winners = CatchUpWinnerStore(Memory()))

    private fun urlFor(startMs: Long, endMs: Long, index: Int = 0) = XtreamCatchUp.candidateUrls(
        baseUrl = account.baseUrl, username = account.username, password = account.password, streamId = 7,
        startMs = startMs, endMs = endMs, containerExtension = "ts",
    )[index]

    private suspend fun CatchUpPlaybackCoordinator.beginXtream(p: CatchUpPlaybackCoordinator.Programme = programme, at: Long = now) = begin(
        account = account, channelContentId = "xtream:acc:live:7", channelName = "BBC", streamId = 7,
        programme = p, nowMs = at, liveUrl = liveUrl,
    )

    @Test
    fun `the first xtream request asks for the show plus what follows`() = runTest {
        val session = coordinator().beginXtream()!!

        val windowEnd = CatchUpContinuationPolicy.replayWindowEndMs(CatchUpContinuationPolicy.Programme(start, end), now)
        assertTrue("the window really extends past the programme", windowEnd > end)
        assertEquals("the session reports the window it asked for", windowEnd, session.windowEndMs)
        assertEquals("and the link asks the panel for that length", urlFor(start, windowEnd), session.url)
    }

    @Test
    fun `a programme still airing is asked for unclamped as before`() = runTest {
        val airingNow = start + 20 * 60_000L
        val session = coordinator().beginXtream(at = airingNow)!!

        assertEquals(end, session.windowEndMs)
        assertEquals(urlFor(start, end), session.url)
    }

    @Test
    fun `an xtream replay is followed past its programme`() = runTest {
        val c = coordinator()
        val s = c.beginXtream()!!

        val ctx = c.replayContextFor(s.contentId)
        assertNotNull("the player follows the playhead of a replay with a window", ctx)
        assertEquals(s.windowEndMs, ctx!!.windowEndMs)
        assertEquals(end, ctx.programmeEndMs)
        assertEquals(liveUrl, ctx.liveUrl)
        assertEquals("Show", ctx.programmeTitle)
    }

    @Test
    fun `restarting asks for a fresh window from the playhead`() = runTest {
        val c = coordinator()
        val s = c.beginXtream()!!
        c.onPlayed(s.contentId)
        val atMs = end + 30 * 60_000L

        val restart = c.restartReplay(s.contentId, atMs, now)

        assertNotNull(restart)
        val windowEnd = CatchUpContinuationPolicy.replayWindowEndMs(CatchUpContinuationPolicy.Programme(atMs, atMs + 1L), now)
        assertEquals(atMs, restart!!.startMs)
        assertEquals(windowEnd, restart.windowEndMs)
        assertEquals(urlFor(atMs, windowEnd), restart.url)
        assertEquals("the context follows the new window", atMs, c.replayContextFor(s.contentId)!!.startMs)
        assertEquals(windowEnd, c.replayContextFor(s.contentId)!!.windowEndMs)
    }

    @Test
    fun `a restart leads with the shape that already played`() = runTest {
        val c = coordinator()
        val s = c.beginXtream()!!
        // The first shape fails, the second plays: that is the panel's dialect.
        val second = c.onFailed(s.contentId, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)!!
        c.onPlayed(second.contentId)
        val atMs = end + 30 * 60_000L

        val restart = c.restartReplay(s.contentId, atMs, now)!!

        val windowEnd = CatchUpContinuationPolicy.replayWindowEndMs(CatchUpContinuationPolicy.Programme(atMs, atMs + 1L), now)
        assertEquals("the proven swapped form, not the default one", urlFor(atMs, windowEnd, index = 1), restart.url)
        assertNotEquals(urlFor(atMs, windowEnd, index = 0), restart.url)
    }

    @Test
    fun `a restart does not leave a walk behind that supersedes the next replay`() = runTest {
        val c = coordinator()
        val s = c.beginXtream()!!
        c.onPlayed(s.contentId)
        c.restartReplay(s.contentId, end + 30 * 60_000L, now)!!

        // A different programme on the same account must still get its own fresh walk and tokens.
        val other = c.beginXtream(CatchUpPlaybackCoordinator.Programme("Other", start - 3_600_000L, start))!!
        assertNotEquals(s.contentId, other.contentId)
        val retry = c.onFailed(other.contentId, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertNotNull("the new replay's ladder is alive", retry)
    }

    @Test
    fun `restarting an unknown or released replay answers nothing`() = runTest {
        val c = coordinator()
        assertNull(c.restartReplay("nope", end, now))
        val s = c.beginXtream()!!
        c.release(s.contentId)
        assertNull(c.restartReplay(s.contentId, end, now))
    }
}

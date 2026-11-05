package com.nuvio.tv.core.iptv

import androidx.media3.common.PlaybackException
import com.nuvio.tv.core.contracts.CatchUpContinuationPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M3U catch-up through the same coordinator the Xtream replays use. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatchUpPlaybackCoordinatorM3uTest {

    // 2026-10-03 19:00:00 UTC; the programme finished an hour before "now".
    private val start = 1_791_054_000_000L
    private val end = start + 60 * 60_000L
    private val now = end + 60 * 60_000L
    private val programme = CatchUpPlaybackCoordinator.Programme("Show", start, end)

    private val liveUrl = "http://panel.invalid/live/user/pass/7.ts"
    private val xtreamStyle = M3uCatchUpInfo(M3uCatchUpType.XTREAM, null, 3)
    private val shiftStyle = M3uCatchUpInfo(M3uCatchUpType.SHIFT, "?utc={utc}", 3)

    private class Memory : CatchUpWinnerStore.Persistence {
        var stored: Map<String, String> = emptyMap()
        override fun load(): Map<String, String> = stored
        override fun save(entries: Map<String, String>) { stored = entries.toMap() }
    }

    private fun coordinator(memory: Memory = Memory(), panelClock: PanelClockSource = PanelClockSource.NONE) =
        CatchUpPlaybackCoordinator(winners = CatchUpWinnerStore(memory), panelClock = panelClock)

    private fun m3u(id: String = "url:list-a", correction: Int = 0, source: String = XtreamAccount.SOURCE_URL) = XtreamAccount(
        id = id, name = "List", baseUrl = "", username = "", password = "",
        sourceType = source, catchUpCorrectionMinutes = correction,
    )

    private fun xtream() = XtreamAccount(id = "acc", name = "P", baseUrl = "http://panel.invalid", username = "user", password = "pass")

    private suspend fun CatchUpPlaybackCoordinator.beginM3u(
        account: XtreamAccount = m3u(),
        info: M3uCatchUpInfo = xtreamStyle,
        url: String = liveUrl,
    ) = begin(
        account = account, channelContentId = "m3u:list-a:live:7", channelName = "BBC", streamId = 7,
        programme = programme, nowMs = now, liveUrl = url, m3uCatchUp = info,
    )

    // --- supports ---------------------------------------------------------------------------

    @Test
    fun `an M3U playlist supports catch-up only for a channel that has it`() {
        val c = coordinator()
        assertTrue("URL playlist + catch-up", c.supports(m3u(), xtreamStyle))
        assertTrue("file playlist + catch-up", c.supports(m3u(source = XtreamAccount.SOURCE_FILE), xtreamStyle))
        assertFalse("M3U without catch-up", c.supports(m3u(), null))
    }

    @Test
    fun `xtream keeps its credentials rule and stalker is still unsupported`() {
        val c = coordinator()
        assertTrue("xtream with credentials", c.supports(xtream()))
        assertFalse("xtream without credentials", c.supports(xtream().copy(password = "")))
        assertFalse("stalker", c.supports(xtream().copy(sourceType = XtreamAccount.SOURCE_STALKER)))
        assertFalse("bare M3U account has no per-channel info", c.supports(m3u()))
    }

    // --- begin ------------------------------------------------------------------------------

    @Test
    fun `begin builds the M3U primary link and ignores the xtream dialect ladder`() = runTest {
        val session = coordinator().beginM3u()
        assertNotNull("buildable", session)
        val minutes = (session!!.windowEndMs - start) / 60_000L
        assertEquals(
            "the playlist's own style, UTC clock, asking for the continuation window",
            "http://panel.invalid/timeshift/user/pass/$minutes/2026-10-03:19-00/7.ts",
            session.url,
        )
        assertFalse("an aired programme is not start-over", session.isStartOver)
    }

    @Test
    fun `begin returns null when the style cannot be built for the url`() = runTest {
        assertNull("flussonic on a non-http url", coordinator().beginM3u(
            info = M3uCatchUpInfo(M3uCatchUpType.FLUSSONIC, null, 1), url = "not a url",
        ))
    }

    @Test
    fun `the playlist's correction minutes become the wall-clock offset`() = runTest {
        val session = coordinator().beginM3u(account = m3u(correction = 60))
        assertTrue("start written 1 h ahead of UTC: ${session!!.url}", session.url.contains("/2026-10-03:20-00/"))
    }

    @Test
    fun `an aired programme asks for the continuation window and an airing one for its own end`() = runTest {
        val aired = coordinator().beginM3u(info = shiftStyle)!!
        assertEquals("window end is recorded on the session", CatchUpContinuationPolicy.replayWindowEndMs(
            CatchUpContinuationPolicy.Programme(start, end), now,
        ), aired.windowEndMs)
        assertTrue("the window really extends past the show", aired.windowEndMs > end)

        val airing = coordinator().begin(
            account = m3u(), channelContentId = "m3u:list-a:live:7", channelName = "BBC", streamId = 7,
            programme = programme, nowMs = start + 20 * 60_000L, liveUrl = liveUrl, m3uCatchUp = shiftStyle,
        )!!
        assertEquals("never past aired", end, airing.windowEndMs)
        assertTrue("start-over", airing.isStartOver)
        assertTrue(
            "the Start over link is built from the programme's START (shift: utc=<start seconds>): ${airing.url}",
            airing.url.contains("utc=${start / 1000}"),
        )
    }

    @Test
    fun `a failed link walks to the next candidate and runs out with null`() = runTest {
        val c = coordinator()
        val first = c.beginM3u()!!
        val urls = mutableListOf(first.url)
        var next = c.onFailed(first.contentId, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        while (next != null) {
            urls += next.url
            next = c.onFailed(next.contentId, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        }
        assertTrue("alternatives exist: $urls", urls.size > 1)
        assertEquals("no duplicates", urls.size, urls.toSet().size)
        assertTrue("the TS fallback of an HLS-style link or php form is on the ladder", urls.any { it.contains("timeshift.php") })
    }

    /**
     * The player reports a replay link that never answered (CatchUpStartupPolicy) as this code. The
     * ladder must treat it as transport-shaped — advance on both the M3U and the Xtream walk — and
     * still end with null so the player can stop on a terminal error instead of waiting forever.
     */
    @Test
    fun `a startup timeout advances the M3U ladder and ends with null`() = runTest {
        val c = coordinator()
        val first = c.beginM3u()!!
        val urls = mutableListOf(first.url)
        var next = c.onFailed(first.contentId, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        while (next != null) {
            urls += next.url
            next = c.onFailed(next.contentId, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        }
        assertTrue("moved on at least once: $urls", urls.size > 1)
        assertNull("exhausted ladder is terminal", c.sessionFor(first.contentId))
    }

    @Test
    fun `a startup timeout advances the Xtream walk and ends with null`() = runTest {
        val c = coordinator()
        val first = c.begin(
            account = xtream(), channelContentId = "xtream:acc:live:7", channelName = "BBC", streamId = 7,
            programme = programme, nowMs = now,
        )!!
        val urls = mutableListOf(first.url)
        var next = c.onFailed(first.contentId, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        while (next != null) {
            urls += next.url
            next = c.onFailed(next.contentId, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        }
        assertTrue("moved on at least once: $urls", urls.size > 1)
        assertNull("exhausted walk is terminal", c.sessionFor(first.contentId))
    }

    @Test
    fun `a decode failure does not walk on`() = runTest {
        val c = coordinator()
        val first = c.beginM3u()!!
        assertNull(c.onFailed(first.contentId, PlaybackException.ERROR_CODE_DECODING_FAILED))
    }

    // --- winner memory ----------------------------------------------------------------------

    @Test
    fun `the form that played is remembered per playlist and leads the next replay`() = runTest {
        val memory = Memory()
        val c = coordinator(memory)
        val first = c.beginM3u()!!
        var session = first
        // Fail until a timeshift.php link comes up, then let it play.
        while (!session.url.contains("timeshift.php")) {
            session = c.onFailed(session.contentId, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)!!
        }
        c.onPlayed(session.contentId)

        val again = coordinator(memory).beginM3u()!!
        assertTrue("php leads after it played: ${again.url}", again.url.contains("timeshift.php"))

        val other = coordinator(memory).beginM3u(account = m3u(id = "url:list-b"))!!
        assertEquals("another playlist is not affected", first.url, other.url)
    }

    @Test
    fun `a primary that plays is remembered without disturbing the xtream winner of the same id`() = runTest {
        val memory = Memory()
        val c = coordinator(memory)
        val s = c.beginM3u()!!
        c.onPlayed(s.contentId)
        assertTrue("stored under an M3U key", memory.stored.keys.any { it.startsWith("m3u:") })
        assertTrue("and not as an xtream dialect record", memory.stored.values.none { it.split('|').size == 3 })
    }

    @Test
    fun `forgetting a playlist drops its M3U form too`() = runTest {
        val memory = Memory()
        val store = CatchUpWinnerStore(memory)
        store.rememberM3uForm("url:list-a", "php")
        assertEquals("php", store.recallM3uForm("url:list-a"))
        store.forget("url:list-a")
        assertNull(store.recallM3uForm("url:list-a"))
    }

    // --- the bounded open -------------------------------------------------------------------

    @After
    fun resetOverrides() {
        BoundedLoad.stallOverrideMsForTests = null
    }

    @Test
    fun `an open that never answers ends as a timed-out failure at the deadline`() = runTest {
        val hung = PanelClockSource { awaitCancellation() }
        val outcome = coordinator(panelClock = hung).open(
            account = xtream(), channelContentId = "xtream:acc:live:7", channelName = "BBC", streamId = 7,
            programme = programme, nowMs = now,
        )
        assertEquals("Failed, timed out", CatchUpPlaybackCoordinator.OpenOutcome.Failed(timedOut = true), outcome)
        assertEquals("the live-resolve 20 s deadline", LoadSurface.LIVE_RESOLVE.stallMs, currentTime)
    }

    @Test
    fun `an open that finds no link is unavailable and one that builds is opened`() = runTest {
        val none = coordinator().open(
            account = xtream().copy(password = ""), channelContentId = "x", channelName = "BBC", streamId = 7,
            programme = programme, nowMs = now,
        )
        assertEquals(CatchUpPlaybackCoordinator.OpenOutcome.Unavailable, none)
        val ok = coordinator().open(
            account = m3u(), channelContentId = "m3u:list-a:live:7", channelName = "BBC", streamId = 7,
            programme = programme, nowMs = now, liveUrl = liveUrl, m3uCatchUp = xtreamStyle,
        )
        assertTrue("opened: $ok", ok is CatchUpPlaybackCoordinator.OpenOutcome.Opened)
        assertNotEquals(CatchUpPlaybackCoordinator.OpenOutcome.Unavailable, ok)
    }

    // --- shared state, stale results, ownership -------------------------------------------------

    @Test
    fun `several coroutines can open look up fail and release replays at once`() = runBlocking(Dispatchers.Default) {
        val c = coordinator()
        val jobs = (0 until 8).map { w ->
            launch {
                repeat(300) { i ->
                    val id = w * 1000 + i + 1
                    val s = c.begin(
                        account = m3u(), channelContentId = "m3u:list-a:live:$id", channelName = "C$id", streamId = id,
                        programme = programme, nowMs = now, liveUrl = "http://panel.invalid/live/user/pass/$id.ts",
                        m3uCatchUp = xtreamStyle,
                    )!!
                    assertNotNull(c.replayContextFor(s.contentId))
                    c.onFailed(s.contentId, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                    c.restartReplay(s.contentId, start + 5 * 60_000L, now)
                    if (i % 2 == 0) c.onPlayed(s.contentId)
                    c.release(s.contentId)
                    assertNull(c.sessionFor(s.contentId))
                }
            }
        }
        jobs.forEach { it.join() }
    }

    @Test
    fun `a begin that finishes after its open timed out publishes nothing`() = runTest {
        val gate = CompletableDeferred<Long?>()
        // A call that ignores cancellation, the case BoundedLoad exists for.
        val slow = PanelClockSource { withContext(NonCancellable) { gate.await() } }
        val c = coordinator(panelClock = slow)
        val outcome = c.open(
            account = xtream(), channelContentId = "xtream:acc:live:7", channelName = "BBC", streamId = 7,
            programme = programme, nowMs = now,
        )
        assertEquals(CatchUpPlaybackCoordinator.OpenOutcome.Failed(timedOut = true), outcome)

        gate.complete(null)
        testScheduler.advanceUntilIdle()

        assertNull("the viewer gave up on it: no session", c.sessionFor(c.contentIdFor("xtream:acc:live:7", start)))
    }

    @Test
    fun `holding a replay's context and disposing it drops the context`() = runTest {
        val c = coordinator()
        val s = c.beginM3u()!!
        val hold = c.hold(s.contentId)
        assertNotNull("held while the player is up", c.replayContextFor(s.contentId))
        hold.dispose()
        assertNull("gone with the player", c.replayContextFor(s.contentId))
        hold.dispose() // at most once, harmlessly
    }
}

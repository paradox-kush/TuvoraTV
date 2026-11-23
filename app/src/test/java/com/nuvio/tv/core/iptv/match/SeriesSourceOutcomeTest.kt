package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.HttpStatusException
import com.nuvio.tv.core.iptv.IptvLoadTimeoutException
import com.nuvio.tv.core.iptv.LoadOutcome
import com.nuvio.tv.core.iptv.LoadSurface
import com.nuvio.tv.core.iptv.XtreamEpisode
import com.nuvio.tv.core.iptv.XtreamParseException
import com.nuvio.tv.core.iptv.XtreamSeriesDetail
import com.nuvio.tv.core.iptv.parseXtreamSeriesInfoRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B134: a failed get_series_info is InfoFailed, never an empty list. Red before the fix: the old code read
 * `getOrNull() ?: emptyList()`, so a 403 and a garbled body both came out as "no streams".
 * JUnit argument order: assertEquals(message, expected, actual).
 */
class SeriesSourceOutcomeTest {

    private val show = IndexedItem(sid = 7, name = "Severance", year = null, tmdb = null, ext = null)

    private fun ep(season: Int, num: Int) =
        XtreamEpisode(episodeId = "e$season-$num", season = season, episodeNum = num, title = "EP", plot = null, still = null, streamUrl = "http://x/e$season-$num")

    private fun detail(vararg eps: XtreamEpisode) =
        XtreamSeriesDetail(tmdbId = null, plot = null, backdrop = null, releaseDate = null, episodes = eps.toList())

    @Test
    fun `a forbidden series info is InfoFailed not an empty result`() = runBlocking {
        val outcome = SeriesSourcePolicy.resolve(listOf(show), 1, 1) { Result.failure(HttpStatusException(403, "HTTP 403")) }
        assertEquals("403 must not read as no streams", SeriesSourceOutcome.InfoFailed(SeriesInfoFailureKind.HTTP_4XX), outcome)
    }

    @Test
    fun `a server error and a timeout are their own kinds`() {
        assertEquals("5xx", SeriesInfoFailureKind.HTTP_5XX, SeriesSourcePolicy.classify(HttpStatusException(502, "x")))
        assertEquals("stall deadline", SeriesInfoFailureKind.TIMEOUT, SeriesSourcePolicy.classify(IptvLoadTimeoutException(LoadSurface.SERIES_INFO, 20_000L)))
        assertEquals("okhttp call timeout", SeriesInfoFailureKind.TIMEOUT, SeriesSourcePolicy.classify(java.net.SocketTimeoutException("timeout")))
        assertEquals("anything else", SeriesInfoFailureKind.OTHER, SeriesSourcePolicy.classify(IllegalStateException("boom")))
    }

    @Test
    fun `an unparseable body is InfoFailed parse`() = runBlocking {
        val thrown = try {
            parseXtreamSeriesInfoRoot("<html>blocked</html>")
            fail("expected XtreamParseException")
            return@runBlocking
        } catch (e: XtreamParseException) {
            e
        }
        val outcome = SeriesSourcePolicy.resolve(listOf(show), 1, 1) { Result.failure(thrown) }
        assertEquals("garbled body is a parse failure", SeriesSourceOutcome.InfoFailed(SeriesInfoFailureKind.PARSE), outcome)
    }

    @Test
    fun `a panel saying no such series is not a failure`() {
        assertEquals("empty array", 0, parseXtreamSeriesInfoRoot("[]").size())
        assertEquals("blank body", 0, parseXtreamSeriesInfoRoot("  ").size())
        assertTrue("an object parses", parseXtreamSeriesInfoRoot("{\"episodes\":{}}").has("episodes"))
        for (bad in listOf("\"nope\"", "{\"episodes\":", "[1]")) {
            try {
                parseXtreamSeriesInfoRoot(bad)
                fail("expected a parse failure for $bad")
            } catch (_: XtreamParseException) {
            }
        }
    }

    @Test
    fun `success with no matching season and episode is NoEpisodeForSE`() = runBlocking {
        val outcome = SeriesSourcePolicy.resolve(listOf(show), 3, 9) { Result.success(detail(ep(1, 1), ep(3, 1))) }
        assertEquals("info loaded but S3E9 absent", SeriesSourceOutcome.NoEpisodeForSE, outcome)
    }

    @Test
    fun `success with the episode is Found`() = runBlocking {
        val outcome = SeriesSourcePolicy.resolve(listOf(show), 3, 1) { Result.success(detail(ep(1, 1), ep(3, 1))) }
        assertTrue("found", outcome is SeriesSourceOutcome.Found)
        assertEquals("the matching episode", listOf("e3-1"), (outcome as SeriesSourceOutcome.Found).picks.map { it.episode.episodeId })
    }

    @Test
    fun `no editions is NoMatch`() = runBlocking {
        val outcome = SeriesSourcePolicy.resolve(emptyList(), 1, 1) { error("never fetched") }
        assertEquals("no entries", SeriesSourceOutcome.NoMatch, outcome)
    }

    @Test
    fun `a hit beats a failed sibling and a failure beats no episode`() = runBlocking {
        val other = IndexedItem(sid = 8, name = "Severance (Hindi)", year = null, tmdb = null, ext = null)
        val hitWins = SeriesSourcePolicy.resolve(listOf(show, other), 1, 1) { ed ->
            if (ed.sid == 7) Result.failure(HttpStatusException(500, "x")) else Result.success(detail(ep(1, 1)))
        }
        assertTrue("a failed edition must not hide a hit", hitWins is SeriesSourceOutcome.Found)
        val failureWins = SeriesSourcePolicy.resolve(listOf(show, other), 1, 1) { ed ->
            if (ed.sid == 7) Result.failure(HttpStatusException(500, "x")) else Result.success(detail(ep(2, 2)))
        }
        assertEquals("cannot claim absent when an edition failed", SeriesSourceOutcome.InfoFailed(SeriesInfoFailureKind.HTTP_5XX), failureWins)
    }

    @Test
    fun `a failed load outcome keeps its error as a failure result`() {
        val error = HttpStatusException(403, "x")
        assertEquals("the error survives", error, LoadOutcome.Failed(error, timedOut = false).toResult().exceptionOrNull())
    }

    @Test
    fun `the failure message names no host and no body`() {
        val message = XtreamSeriesInfoException(SeriesInfoFailureKind.HTTP_4XX).message.orEmpty()
        assertFalse("no URL in the message", message.contains("://"))
    }
}

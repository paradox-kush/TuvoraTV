package com.nuvio.tv.core.recommendations

import com.nuvio.tv.core.recommendations.WatchNextTypePolicy.WatchNextType
import com.nuvio.tv.ui.screens.home.ResumeRankPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** JUnit argument order: assertEquals(message, expected, actual). */
class WatchNextTypePolicyTest {

    @Test
    fun `a resume with a position and duration is continue`() {
        assertEquals("continue", WatchNextType.CONTINUE, WatchNextTypePolicy.typeFor(Kind.Resume, 600_000L, 3_600_000L))
    }

    @Test
    fun `a resume without a duration is not published as continue`() {
        assertEquals("no duration", WatchNextType.NEXT, WatchNextTypePolicy.typeFor(Kind.Resume, 0L, 0L))
        assertEquals("position only", WatchNextType.NEXT, WatchNextTypePolicy.typeFor(Kind.Resume, 600_000L, 0L))
    }

    @Test
    fun `a resume at position zero is not published as continue`() {
        assertEquals("duration only", WatchNextType.NEXT, WatchNextTypePolicy.typeFor(Kind.Resume, 0L, 3_600_000L))
    }

    @Test
    fun `an aired next-up at zero of zero is next`() {
        assertEquals("next", WatchNextType.NEXT, WatchNextTypePolicy.typeFor(Kind.NextUp, 0L, 0L))
    }

    @Test
    fun `a release alert is new`() {
        assertEquals("new", WatchNextType.NEW, WatchNextTypePolicy.typeFor(Kind.NewEpisode, 0L, 0L))
    }

    @Test
    fun `nothing is ever published as watchlist`() {
        val watchlist = 3 // WATCH_NEXT_TYPE_WATCHLIST in tvprovider 1.0.0 and android.media.tv.TvContract
        for (kind in Kind.entries) {
            for (position in listOf(0L, 1L, 600_000L)) {
                for (duration in listOf(0L, 3_600_000L)) {
                    assertNotEquals(
                        "$kind $position/$duration",
                        watchlist,
                        WatchNextTypePolicy.typeFor(kind, position, duration).contractValue
                    )
                }
            }
        }
    }

    @Test
    fun `contract values match the verified TvContract constants`() {
        assertEquals("continue", 0, WatchNextType.CONTINUE.contractValue)
        assertEquals("next", 1, WatchNextType.NEXT.contractValue)
        assertEquals("new", 2, WatchNextType.NEW.contractValue)
    }

    @Test
    fun `position is derived from the percent only when the duration is real`() {
        assertEquals("percent with duration", 1_800_000L, WatchNextTypePolicy.resolvePositionMs(0L, 3_600_000L, 50f))
        assertEquals("stored position wins", 123L, WatchNextTypePolicy.resolvePositionMs(123L, 3_600_000L, 50f))
        assertEquals("percent without duration", 0L, WatchNextTypePolicy.resolvePositionMs(0L, 0L, 50f))
        assertEquals("nothing known", 0L, WatchNextTypePolicy.resolvePositionMs(0L, 3_600_000L, null))
    }

    @Test
    fun `a percent-only resume ends up as next not continue`() {
        val duration = 0L
        val position = WatchNextTypePolicy.resolvePositionMs(0L, duration, 40f)
        assertEquals("not continue", WatchNextType.NEXT, WatchNextTypePolicy.typeFor(Kind.Resume, position, duration))
    }
}

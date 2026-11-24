package com.nuvio.tv.core.radar.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAlertWatchTest {
    private val t0 = 1_000_000L
    private val ttl = WatchResultCache.DEFAULT_TTL_MS

    @Test
    fun `a found answer is cached for fifteen minutes and then expires`() {
        val c = WatchResultCache()
        c.putFound("e1", listOf("c1", "c2"), t0)
        assertEquals("fresh", WatchState.Channel("c1"), c.stateFor("e1", emptySet(), t0 + ttl - 1))
        assertEquals("expired", WatchState.Unknown, c.stateFor("e1", emptySet(), t0 + ttl))
        assertEquals("never asked", WatchState.Unknown, c.stateFor("e2", emptySet(), t0))
    }

    @Test
    fun `a negative answer is cached too, and expires`() {
        val c = WatchResultCache()
        c.putNone("e1", t0)
        assertEquals("no channel", WatchState.NoChannel, c.stateFor("e1", setOf("c9"), t0 + 1))
        assertEquals("expired", WatchState.Unknown, c.stateFor("e1", setOf("c9"), t0 + ttl))
    }

    @Test
    fun `the state prefers the matched channel that is playing`() {
        val c = WatchResultCache()
        c.putFound("e1", listOf("c1", "c2"), t0)
        assertEquals("playing c2", WatchState.Channel("c2"), c.stateFor("e1", setOf("c2"), t0))
    }

    @Test
    fun `the reverse lookup answers every game, not just the first`() {
        val c = WatchResultCache()
        c.putFound("e1", listOf("c1"), t0)
        c.putFound("e2", listOf("c1"), t0)
        c.putNone("e3", t0)
        val states = c.statesFor(setOf("c1"), t0 + 1)
        assertEquals("all three games", setOf("e1", "e2", "e3"), states.keys)
        assertEquals("e1", WatchState.Channel("c1"), states["e1"])
        assertEquals("e2", WatchState.Channel("c1"), states["e2"])
        assertEquals("e3", WatchState.NoChannel, states["e3"])
    }

    @Test
    fun `expired games drop out of the state map and clear empties it`() {
        val c = WatchResultCache()
        c.putFound("e1", listOf("c1"), t0)
        assertTrue("expired", c.statesFor(emptySet(), t0 + ttl).isEmpty())
        c.putNone("e2", t0)
        c.clear()
        assertTrue("cleared", c.statesFor(emptySet(), t0).isEmpty())
    }

    @Test
    fun `the holder clears only what was set and publishes every tile`() {
        val h = SportsAlertPlayingChannels()
        h.set("a"); h.set("b")
        assertEquals("two tiles", setOf("a", "b"), h.ids.value)
        h.clear("a")
        assertEquals("only a is gone", setOf("b"), h.ids.value)
        h.clear("zzz")
        assertEquals("another player's id is untouched by a stranger", setOf("b"), h.ids.value)
        h.clear("b")
        assertTrue("empty", h.ids.value.isEmpty())
    }

    @Test
    fun `only score and start banners look up a watch channel`() {
        fun b(kind: AlertKind?, event: String? = "e1") = AlertBanner("t", null, eventId = event, kind = kind)
        assertTrue("score", SportsAlertWatchPolicy.bannerLookupWanted(b(AlertKind.Score)))
        assertTrue("start", SportsAlertWatchPolicy.bannerLookupWanted(b(AlertKind.Start)))
        assertFalse("final", SportsAlertWatchPolicy.bannerLookupWanted(b(AlertKind.Final)))
        assertFalse("summary", SportsAlertWatchPolicy.bannerLookupWanted(b(null, event = null)))
    }
}

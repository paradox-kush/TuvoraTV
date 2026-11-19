package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin suggestion's rules, shared with the phone app so both behave alike: a channel qualifies with at least
 * 3 plays in the last 14 days; favourites (and pinned) are excluded; up to 4 are suggested and nothing when fewer
 * than 2 qualify; "Not now" snoozes 14 days; "Stop suggesting" (the switch off) is never.
 */
class PinSuggestionPolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day
    private val on = PinSuggestionPolicy.Prefs()

    private fun plays(vararg agesInDays: Long): List<Long> = agesInDays.map { now - it * day }

    private fun suggest(
        log: Map<String, List<Long>>,
        excluded: Set<String> = emptySet(),
        prefs: PinSuggestionPolicy.Prefs = on,
    ) = PinSuggestionPolicy.suggest(log, excluded, prefs, now)

    @Test
    fun `three plays in the window qualify and two do not`() {
        val log = mapOf("a" to plays(1, 2, 3), "b" to plays(1, 2), "c" to plays(0, 1, 2))
        assertEquals("only channels with 3 plays qualify", listOf("a", "c"), suggest(log).sorted())
    }

    @Test
    fun `plays older than 14 days do not count and the 14 day edge does`() {
        val log = mapOf(
            "edge" to plays(14, 13, 12),
            "stale" to plays(15, 1, 2),
            "ok" to plays(1, 2, 3),
        )
        assertEquals("a play exactly 14 days old still counts; 15 days does not", listOf("edge", "ok"), suggest(log).sorted())
    }

    @Test
    fun `a single qualifying channel suggests nothing`() {
        assertEquals("fewer than 2 qualify", emptyList<String>(), suggest(mapOf("a" to plays(1, 2, 3, 4))))
    }

    @Test
    fun `exactly two qualifying channels are suggested`() {
        assertEquals(2, suggest(mapOf("a" to plays(1, 2, 3), "b" to plays(1, 2, 3))).size)
    }

    @Test
    fun `no history suggests nothing`() {
        assertEquals(emptyList<String>(), suggest(emptyMap()))
    }

    @Test
    fun `at most four are suggested, the most played first`() {
        val log = (1..6).associate { n -> "c$n" to plays(*LongArray(2 + n) { 1L }) }
        // c1 has 3 plays ... c6 has 8.
        assertEquals("top four by plays", listOf("c6", "c5", "c4", "c3"), suggest(log))
    }

    @Test
    fun `equal play counts rank the more recent first then by id`() {
        val log = mapOf(
            "old" to plays(5, 6, 7),
            "new" to plays(0, 6, 7),
            "tieA" to plays(2, 6, 7),
            "tieB" to plays(2, 6, 7),
        )
        assertEquals(listOf("new", "tieA", "tieB", "old"), suggest(log))
    }

    @Test
    fun `favourites are excluded and the rest still suggest`() {
        val log = mapOf("a" to plays(1, 2, 3), "b" to plays(1, 2, 3), "c" to plays(1, 2, 3))
        assertEquals(listOf("b", "c"), suggest(log, excluded = setOf("a")).sorted())
    }

    @Test
    fun `excluding favourites can leave fewer than two and then nothing is suggested`() {
        val log = mapOf("a" to plays(1, 2, 3), "b" to plays(1, 2, 3))
        assertEquals(emptyList<String>(), suggest(log, excluded = setOf("a")))
    }

    @Test
    fun `plays in the future are not counted`() {
        val log = mapOf("a" to listOf(now + day, now + 2 * day, now + 3 * day), "b" to plays(1, 2, 3), "c" to plays(1, 2, 3))
        assertEquals(listOf("b", "c"), suggest(log).sorted())
    }

    @Test
    fun `the switch off never suggests`() {
        val log = mapOf("a" to plays(1, 2, 3), "b" to plays(1, 2, 3))
        assertEquals(emptyList<String>(), suggest(log, prefs = PinSuggestionPolicy.Prefs(enabled = false)))
    }

    @Test
    fun `a snooze hides the suggestion until its end and not after`() {
        val log = mapOf("a" to plays(1, 2, 3), "b" to plays(1, 2, 3))
        val snoozed = PinSuggestionPolicy.Prefs(snoozedUntilMs = now + 1)
        assertEquals("still snoozed", emptyList<String>(), suggest(log, prefs = snoozed))
        assertEquals("snooze over at its end", 2, suggest(log, prefs = PinSuggestionPolicy.Prefs(snoozedUntilMs = now)).size)
    }

    @Test
    fun `Not now snoozes for 14 days`() {
        assertEquals(now + 14 * day, PinSuggestionPolicy.snoozedUntil(now))
    }

    @Test
    fun `the suggested rows are listed first in order without duplicates`() {
        val rows = listOf("x", "a", "y", "b", "z")
        assertEquals(listOf("b", "a", "x", "y", "z"), PinSuggestionPolicy.listedFirst(rows, listOf("b", "a")) { it })
    }

    @Test
    fun `a suggested row beyond the list is still listed first`() {
        assertEquals(listOf("q", "x"), PinSuggestionPolicy.listedFirst(listOf("x"), listOf("q")) { it })
    }

    @Test
    fun `listing with no suggestion leaves the rows untouched`() {
        val rows = listOf("x", "y")
        assertTrue("same rows", PinSuggestionPolicy.listedFirst(rows, emptyList()) { it } == rows)
    }

    @Test
    fun `play log records, prunes the window and caps plays per channel`() {
        var log = emptyMap<String, List<Long>>()
        val t0 = now - 20 * day
        log = PlayCountLog.record(log, "a", t0)
        log = PlayCountLog.record(log, "b", now - day)
        assertEquals("the 20-day-old play is pruned with its channel", setOf("b"), log.keys)
        for (i in 0 until 15) log = PlayCountLog.record(log, "b", now + i * PlayCountLog.DEDUPE_MS)
        assertEquals("plays per channel are capped", PlayCountLog.MAX_PLAYS_PER_CHANNEL, log.getValue("b").size)
        assertEquals("the newest plays are kept", now + 14 * PlayCountLog.DEDUPE_MS, log.getValue("b").last())
    }

    @Test
    fun `plays 9 minutes apart count once and 11 minutes apart count twice`() {
        val min = 60_000L
        val nine = PlayCountLog.record(PlayCountLog.record(emptyMap(), "a", now), "a", now + 9 * min)
        assertEquals("9 minutes apart is one play", 1, nine.getValue("a").size)
        val eleven = PlayCountLog.record(PlayCountLog.record(emptyMap(), "a", now), "a", now + 11 * min)
        assertEquals("11 minutes apart is two plays", 2, eleven.getValue("a").size)
    }

    @Test
    fun `the channel count is capped by dropping the least recently played`() {
        var log = emptyMap<String, List<Long>>()
        for (i in 0 until PlayCountLog.MAX_CHANNELS + 5) log = PlayCountLog.record(log, "c$i", now - 10 * day + i * 1000L)
        assertEquals(PlayCountLog.MAX_CHANNELS, log.size)
        assertTrue("the oldest were dropped", "c0" !in log && "c4" !in log && "c5" in log)
    }

    @Test
    fun `a blank id records nothing`() {
        assertEquals(emptyMap<String, List<Long>>(), PlayCountLog.record(emptyMap(), " ", now))
    }

    @Test
    fun `Pin all pins every suggested channel with its playlist and skips rows without an identity`() {
        val batches = mutableListOf<List<PinSuggestionActions.Target>>()
        val count = PinSuggestionActions.pinAll(
            listOf(
                PinSuggestionActions.Target("e1", "pl"),
                PinSuggestionActions.Target("", "pl"),
                PinSuggestionActions.Target("e2", null),
            ),
        ) { batches += it }
        assertEquals("rows without an entity id cannot be pinned", 2, count)
        assertEquals("one batch, so one overlay write and one push", 1, batches.size)
        assertEquals(listOf(PinSuggestionActions.Target("e1", "pl"), PinSuggestionActions.Target("e2", null)), batches.single())
    }

    @Test
    fun `Pin all with nothing pins nothing`() {
        var calls = 0
        assertEquals(0, PinSuggestionActions.pinAll(emptyList()) { calls++ })
        assertEquals(0, calls)
    }
}

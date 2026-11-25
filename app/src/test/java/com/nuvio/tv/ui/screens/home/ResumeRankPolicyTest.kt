package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.screens.home.ResumeRankPolicy.Candidate
import com.nuvio.tv.ui.screens.home.ResumeRankPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JUnit argument order: assertEquals(message, expected, actual).
 * Case 11 (Most recent first is unchanged) lives in ResumeFirstSortModeTest.
 */
class ResumeRankPolicyTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun resume(key: String, ageMs: Long, fraction: Double, here: Boolean = false) = Candidate(
        key = key,
        kind = Kind.Resume,
        lastEngagedAtMs = now - ageMs,
        progressFraction = fraction,
        playedOnThisDevice = here
    )

    private fun nextUp(key: String, ageMs: Long) = Candidate(
        key = key,
        kind = Kind.NextUp,
        lastEngagedAtMs = now - ageMs
    )

    private fun rank(vararg c: Candidate) = ResumeRankPolicy.rank(c.toList(), now)

    @Test
    fun `half-watched film from yesterday outranks a next-up engaged two hours ago`() {
        val order = rank(nextUp("next", 2 * hour), resume("film", day, 0.5))
        assertEquals("resume first", listOf("film", "next"), order)
    }

    @Test
    fun `two resumes in the same band order by recency`() {
        val order = rank(resume("older", 3 * day, 0.4), resume("newer", day, 0.6))
        assertEquals("newer first", listOf("newer", "older"), order)
    }

    @Test
    fun `a 96 percent item sinks below a 40 percent item of the same age`() {
        val order = rank(resume("done", 2 * day, 0.96), resume("middle", 2 * day, 0.40))
        assertEquals("near-finished sinks", listOf("middle", "done"), order)
    }

    @Test
    fun `a 2 percent item from three days ago sinks below a week-old 50 percent item`() {
        val order = rank(resume("barely", 3 * day, 0.02), resume("half", 7 * day, 0.5))
        assertEquals("barely started sinks", listOf("half", "barely"), order)
    }

    @Test
    fun `a barely started item from today is not penalised`() {
        val order = rank(resume("fresh", 2 * hour, 0.02), resume("half", 3 * day, 0.5))
        // fresh: now-2h, no penalty. half: now-3d+48h = now-24h.
        assertEquals("fresh start keeps its recency", listOf("fresh", "half"), order)
    }

    @Test
    fun `a new episode aired today for a show last watched 20 days ago outranks a 10 day old resume`() {
        val newEpisode = Candidate(
            key = "new",
            kind = Kind.NewEpisode,
            lastEngagedAtMs = now - 20 * day,
            releasedAtMs = now - 3 * hour
        )
        val order = rank(resume("resume", 10 * day, 0.5), newEpisode)
        assertEquals("new episode first", listOf("new", "resume"), order)
    }

    @Test
    fun `a 31 day old item gets no boost`() {
        // Boosted, the resume (+48h) would beat the next-up. Stale items keep their raw time only.
        val staleResume = resume("resume", 31 * day, 0.5)
        val slightlyNewer = nextUp("next", 31 * day - 12 * hour)
        val order = rank(staleResume, slightlyNewer)
        assertEquals("no boost after 30 days", listOf("next", "resume"), order)
    }

    @Test
    fun `this-device boost breaks a near tie`() {
        val here = resume("here", 5 * hour, 0.5, here = true)
        val elsewhere = resume("elsewhere", 4 * hour, 0.5)
        assertEquals("device boost wins a near tie", listOf("here", "elsewhere"), rank(elsewhere, here))
    }

    @Test
    fun `this-device boost never overturns a 24 hour gap`() {
        val here = resume("here", 30 * hour, 0.5, here = true)
        val elsewhere = resume("elsewhere", 5 * hour, 0.5)
        assertEquals("24h gap holds", listOf("elsewhere", "here"), rank(here, elsewhere))
    }

    @Test
    fun `unaired next-ups come last nearest date first and unknown dates after known`() {
        fun unaired(key: String, releasedAt: Long?) = Candidate(
            key = key,
            kind = Kind.NextUp,
            lastEngagedAtMs = now - hour,
            releasedAtMs = releasedAt,
            aired = false
        )
        val order = rank(
            unaired("unknown", null),
            unaired("far", now + 20 * day),
            resume("old-resume", 25 * day, 0.5),
            unaired("near", now + 2 * day)
        )
        assertEquals(
            "aired first, then unaired by date, unknown last",
            listOf("old-resume", "near", "far", "unknown"),
            order
        )
    }

    @Test
    fun `same input twice gives the same order and equal scores order by key`() {
        val a = resume("a", day, 0.5)
        val b = resume("b", day, 0.5)
        val c = resume("c", day, 0.5)
        val first = ResumeRankPolicy.rank(listOf(c, a, b), now)
        val second = ResumeRankPolicy.rank(listOf(c, a, b), now)
        assertEquals("deterministic", first, second)
        assertEquals("ties by key", listOf("a", "b", "c"), first)
        assertEquals("input order irrelevant", listOf("a", "b", "c"), ResumeRankPolicy.rank(listOf(b, c, a), now))
    }

    @Test
    fun `empty input gives empty output`() {
        assertEquals("empty", emptyList<String>(), ResumeRankPolicy.rank(emptyList(), now))
    }
}

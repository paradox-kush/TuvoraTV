package com.nuvio.tv.core.radar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recordings section matched a provider VOD to a fixture by naming — and the ungated event-token
 * fallback let one team's two words accept an unrelated title, so "Arizona Cardinals v Dallas
 * Cowboys" pulled in "…the Untold Story of the Dallas Cowboys Cheerleaders" (device-confirmed).
 * These pin the gated rule: both teams for team fixtures, the event fallback only for event-only sports.
 *
 * JUnit arg order here is (message, condition) — the opposite of the KMP kotlin.test twin.
 */
class SportsRecordingMatchPolicyTest {

    /** Word-boundary membership, standing in for the matcher's hits(normalize(title), token). */
    private fun titleMatcher(title: String): (String) -> Boolean {
        val words = title.lowercase().split(Regex("[^a-z0-9]+")).filterNot { it.isEmpty() }.toSet()
        return { token -> token in words }
    }

    private val cardinals = listOf("arizona", "cardinals")
    private val cowboys = listOf("dallas", "cowboys")
    private val cardinalsVsCowboysEvent = cardinals + cowboys

    @Test
    fun `a team fixture rejects a title naming only one team even via the event string`() {
        val title = "Daughters of the Sexual Revolution: The Untold Story of the Dallas Cowboys Cheerleaders"
        assertFalse(
            "a Cowboys-only documentary must not match a Cardinals-v-Cowboys fixture",
            SportsRecordingMatchPolicy.accepts(cardinals, cowboys, cardinalsVsCowboysEvent, titleMatcher(title)),
        )
    }

    @Test
    fun `a team fixture accepts a title naming both teams`() {
        val title = "Arizona Cardinals vs Dallas Cowboys - Full Game"
        assertTrue(
            "a full-game recording naming both teams must match",
            SportsRecordingMatchPolicy.accepts(cardinals, cowboys, cardinalsVsCowboysEvent, titleMatcher(title)),
        )
    }

    @Test
    fun `an event-only fixture accepts a title carrying at least two event words`() {
        val event = listOf("monaco", "grand", "prix")
        val title = "Formula 1 Monaco Grand Prix 2024"
        assertTrue(
            "an event-only fixture keeps the two-event-word fallback (motorsport/golf)",
            SportsRecordingMatchPolicy.accepts(emptyList(), emptyList(), event, titleMatcher(title)),
        )
    }

    @Test
    fun `an event-only fixture rejects a title carrying only one event word`() {
        val event = listOf("monaco", "grand", "prix")
        val title = "Grand Designs"
        assertFalse(
            "one shared word is not enough for an event-only match",
            SportsRecordingMatchPolicy.accepts(emptyList(), emptyList(), event, titleMatcher(title)),
        )
    }
}

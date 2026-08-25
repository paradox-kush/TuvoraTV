package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The channel matcher listed wrong-sport channels for a game because a single shared team word scored
 * a match: the NFL "Arizona Cardinals v Dallas Cowboys" surfaced "US (MLB) St. Louis Cardinals" and
 * "US (MLB) Arizona Diamondbacks" (device-confirmed on this TV build). These pin the cross-sport guard.
 *
 * JUnit arg order is (message, expected, actual) — the opposite of the KMP kotlin.test twin.
 */
class SportsChannelMatchPolicyTest {

    private fun matcher(text: String): (String) -> Boolean {
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filterNot { it.isEmpty() }.toSet()
        return { token -> token in words }
    }

    private val home = listOf("arizona", "cardinals")
    private val away = listOf("dallas", "cowboys")
    private val keywords = listOf("nfl")

    private fun name(text: String, generic: Boolean = false) =
        SportsChannelMatchPolicy.nameScore(home, away, keywords, emptyList(), generic, matcher(text))

    @Test
    fun `a wrong-sport MLB channel sharing one team word does not surface`() {
        assertEquals("an MLB Cardinals channel must not match an NFL Cardinals game", 0, name("US (MLB) St. Louis Cardinals"))
        assertEquals("an MLB Arizona channel must not match an NFL Arizona game", 0, name("US (MLB) Arizona Diamondbacks"))
    }

    @Test
    fun `both teams still score highest`() {
        assertEquals("both teams present is the strongest name signal", 50, name("NFL Network Arizona Cardinals vs Dallas Cowboys"))
    }

    @Test
    fun `the league keyword still matches`() {
        assertEquals("a channel carrying the league keyword matches", 25, name("US: NFL RedZone HD"))
    }

    @Test
    fun `a same-sport single-team channel still matches`() {
        assertEquals("a one-team channel with no competing league still matches", 12, name("Dallas Cowboys TV"))
    }

    @Test
    fun `a generic sports channel keeps its weak score`() {
        assertEquals("a generic sports channel keeps the weak tier", 8, name("beIN Sports 1", generic = true))
    }

    @Test
    fun `programme scoring gates a wrong-sport single-team hit too`() {
        val prog = { text: String -> SportsChannelMatchPolicy.programmeScore(home, away, keywords, emptyList(), matcher(text)) }
        assertEquals("an MLB programme must not match an NFL game", 0, prog("MLB: St. Louis Cardinals at Philadelphia Phillies"))
        assertEquals("a same-sport one-team programme still matches weakly", 25, prog("Dallas Cowboys pre-game"))
        assertEquals("one team plus the league keyword is a strong programme hit", 70, prog("NFL coverage: Cardinals build-up"))
        assertEquals("both teams is the strongest programme hit", 100, prog("Arizona Cardinals vs Dallas Cowboys"))
    }
}

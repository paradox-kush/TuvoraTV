package com.nuvio.tv.core.sports.detail.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSubtitleTest {
    @Test fun roundZeroIsDropped() = assertEquals("NBA", gameSubtitle("NBA", "0"))
    @Test fun blankRoundIsDropped() = assertEquals("NBA", gameSubtitle("NBA", " "))
    @Test fun nullRoundIsDropped() = assertEquals("NBA", gameSubtitle("NBA", null))
    @Test fun realRoundIsKept() = assertEquals("FA Cup · Round 3", gameSubtitle("FA Cup", "3"))
    @Test fun namedRoundsUseTheirLabel() = assertEquals("World Cup · Final", gameSubtitle("World Cup", "200"))
    @Test fun nothingAtAllIsNull() = assertNull(gameSubtitle("", "0"))
}

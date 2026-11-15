package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** B12 / A7: jokes in empty and idle states only, never in loading or failure. JUnit: (message, expected, actual). */
class GuideVoicePolicyTest {

    @Test
    fun `loading and failures stay plain whatever the setting`() {
        listOf(GuideVoicePolicy.Moment.LOADING, GuideVoicePolicy.Moment.FAILED).forEach { moment ->
            assertEquals("$moment", false, GuideVoicePolicy.joking(moment, playful = true))
        }
    }

    @Test
    fun `empty and idle states carry a quip by default`() {
        assertEquals("A7 default", true, GuideVoicePolicy.PLAYFUL)
        listOf(
            GuideVoicePolicy.Moment.EMPTY_FAVORITES, GuideVoicePolicy.Moment.EMPTY_RECENT, GuideVoicePolicy.Moment.SEARCH_MISS,
            GuideVoicePolicy.Moment.EMPTY_GROUP, GuideVoicePolicy.Moment.EMPTY_PLAYLIST, GuideVoicePolicy.Moment.IDLE_PREVIEW,
            GuideVoicePolicy.Moment.NO_GUIDE,
        ).forEach { moment -> assertEquals("$moment", true, GuideVoicePolicy.joking(moment)) }
    }

    @Test
    fun `the owner's flip removes every quip`() {
        GuideVoicePolicy.Moment.entries.forEach { moment ->
            assertEquals("$moment", false, GuideVoicePolicy.joking(moment, playful = false))
        }
    }

    @Test
    fun `every empty state maps to a moment and an empty search prompt stays an instruction`() {
        assertEquals("prompt", false, GuideVoicePolicy.joking(GuideVoicePolicy.momentOf(GuideEmptyStatePolicy.Kind.SEARCH_PROMPT)))
        assertEquals("favourites", GuideVoicePolicy.Moment.EMPTY_FAVORITES, GuideVoicePolicy.momentOf(GuideEmptyStatePolicy.Kind.FAVORITES_HINT))
    }
}

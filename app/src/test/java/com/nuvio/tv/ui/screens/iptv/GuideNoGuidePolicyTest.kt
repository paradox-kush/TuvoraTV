package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX331 / UX337 / UX359: a playlist with no EPG repeated one sentence on every row with no way to the settings.
 * The group now says it once, with an action; rows show the channel name dim; a missing logo is a monogram.
 */
class GuideNoGuidePolicyTest {

    @Test
    fun `a group whose sampled rows all came back empty has no guide`() {
        val loaded = mapOf(1 to false, 2 to false, 3 to false, 4 to false, 5 to false)
        assertTrue(GuideNoGuidePolicy.groupHasNoGuide(channelCount = 40, loadedHasProgrammes = loaded))
    }

    @Test
    fun `one row with programmes means the playlist has a guide`() {
        val loaded = mapOf(1 to false, 2 to true, 3 to false, 4 to false, 5 to false)
        assertFalse(GuideNoGuidePolicy.groupHasNoGuide(channelCount = 40, loadedHasProgrammes = loaded))
    }

    @Test
    fun `too few rows loaded is not a verdict yet`() {
        assertFalse("still sampling", GuideNoGuidePolicy.groupHasNoGuide(channelCount = 40, loadedHasProgrammes = mapOf(1 to false, 2 to false)))
    }

    @Test
    fun `a short group can be judged on all its rows`() {
        assertTrue(GuideNoGuidePolicy.groupHasNoGuide(channelCount = 2, loadedHasProgrammes = mapOf(1 to false, 2 to false)))
    }

    @Test
    fun `an empty group is not a no-guide group`() {
        assertFalse(GuideNoGuidePolicy.groupHasNoGuide(channelCount = 0, loadedHasProgrammes = emptyMap()))
    }

    @Test
    fun `the monogram is the first letter or digit, upper case`() {
        assertEquals("B", GuideNoGuidePolicy.monogram("bbc one"))
        assertEquals("5", GuideNoGuidePolicy.monogram("  5 USA"))
        assertEquals("skips leading punctuation", "U", GuideNoGuidePolicy.monogram("| UK | Sky Sports"))
        assertEquals("#", GuideNoGuidePolicy.monogram("   "))
        assertEquals("#", GuideNoGuidePolicy.monogram("***"))
    }

    @Test
    fun `a blank logo gets the monogram, a real one does not`() {
        assertTrue(GuideNoGuidePolicy.needsMonogram(null))
        assertTrue(GuideNoGuidePolicy.needsMonogram("  "))
        assertFalse(GuideNoGuidePolicy.needsMonogram("http://x/logo.png"))
    }
}

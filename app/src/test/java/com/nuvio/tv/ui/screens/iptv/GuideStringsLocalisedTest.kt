package com.nuvio.tv.ui.screens.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX56 / UX325: the guide's channel-search texts were hardcoded English literals (no locale could translate them),
 * and `iptv_guide_hint` was a dead string describing old behaviour.
 */
class GuideStringsLocalisedTest {
    private fun main(path: String) = File("src/main/java/com/nuvio/tv/ui/screens/iptv/$path").readText()
    private val strings by lazy { File("src/main/res/values/strings.xml").readText() }

    private val banned = listOf(
        "\"Search channels\"",
        "\"No channels match",
        "\"Press OK on Search to type a channel name\"",
        "\"Search: \\u201C",
        "\"Type part of a channel name",
        "{ Text(\"Search\") }",
        "{ Text(\"Cancel\") }",
    )

    @Test
    fun `the guide screen holds no hardcoded English for channel search`() {
        val src = main("XtreamLiveGuideScreen.kt")
        for (literal in banned) assertFalse("hardcoded UI text left in the guide: $literal", src.contains(literal))
    }

    @Test
    fun `the dead hint string is gone and the live one stays`() {
        assertFalse("iptv_guide_hint is unused (UX325)", strings.contains("name=\"iptv_guide_hint\""))
        assertTrue(strings.contains("name=\"iptv_guide_remote_hint\""))
    }

    @Test
    fun `the new guide strings exist`() {
        for (key in listOf(
            "iptv_guide_search_title", "iptv_guide_search_empty_title", "iptv_guide_search_no_match", "iptv_guide_search_dialog_subtitle",
            "iptv_guide_search_label", "iptv_guide_no_guide_group", "iptv_guide_fix_guide", "iptv_guide_preview_playing",
            "iptv_guide_preview_reconnecting", "iptv_guide_preview_retry_hint", "iptv_guide_history_loading", "iptv_guide_history_failed",
        )) {
            assertEquals("string $key declared once", 1, Regex("name=\"$key\"").findAll(strings).count())
        }
    }
}

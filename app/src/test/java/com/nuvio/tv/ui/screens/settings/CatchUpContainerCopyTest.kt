package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F35: TV has had the catch-up container preference since the TV guide's catch-up shipped, but as
 * "Catch-up container: Prefer TS" — a reporter who uses phone/desktop's "Prefer m3u8 (enables
 * scrubbing)" could not recognise it and asked for it on TV. The row now uses the phone's words,
 * byte-for-byte (NuvioMobile XtreamContentSettingsPage.CatchUpSettings), so one name works on every
 * platform and in support replies.
 */
class CatchUpContainerCopyTest {

    @Test
    fun `the row is named as on phone and desktop`() {
        assertEquals("title", "Prefer m3u8 (enables scrubbing)", CatchUpContainerCopy.TITLE)
    }

    @Test
    fun `on says what it does and that TS is still the fallback`() {
        assertEquals("value", "On", CatchUpContainerCopy.value(preferM3u8 = true))
        assertEquals(
            "description",
            "Asks the panel for HLS first, so replays have a progress bar. Falls back to TS.",
            CatchUpContainerCopy.description(preferM3u8 = true),
        )
    }

    @Test
    fun `off says TS is the reliable default and replays usually cannot scrub`() {
        assertEquals("value", "Off", CatchUpContainerCopy.value(preferM3u8 = false))
        assertEquals(
            "description",
            "Asks the panel for TS first — the more reliable default. Replays usually can't scrub.",
            CatchUpContainerCopy.description(preferM3u8 = false),
        )
    }
}

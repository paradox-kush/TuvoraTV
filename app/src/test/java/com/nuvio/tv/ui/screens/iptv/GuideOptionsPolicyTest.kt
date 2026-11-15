package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.GuideLayoutPreset
import org.junit.Assert.assertEquals
import org.junit.Test

/** Redesign change 15 (A9, A4). JUnit: (message, expected, actual). */
class GuideOptionsPolicyTest {

    @Test
    fun `the panel lists the three sizes then the resume switch and opens on the size in use`() {
        assertEquals(
            "rows",
            listOf(
                GuideOptionsPolicy.Row.Size(GuideLayoutPreset.COMPACT),
                GuideOptionsPolicy.Row.Size(GuideLayoutPreset.STANDARD),
                GuideOptionsPolicy.Row.Size(GuideLayoutPreset.LARGE),
                GuideOptionsPolicy.Row.ResumeOnOpen,
            ),
            GuideOptionsPolicy.rows(),
        )
        assertEquals("entry", GuideOptionsPolicy.Row.Size(GuideLayoutPreset.LARGE), GuideOptionsPolicy.entryRow(GuideLayoutPreset.LARGE))
    }

    @Test
    fun `opening the guide never tunes unless the viewer turned resume on`() {
        assertEquals("A4 default off", false, GuideOptionsPolicy.RESUME_ON_OPEN_DEFAULT)
        assertEquals("off", false, GuideOptionsPolicy.resumeOnEntry(false, firstEntry = true, lastWatchedListed = true, somethingPlaying = false))
        assertEquals("on", true, GuideOptionsPolicy.resumeOnEntry(true, firstEntry = true, lastWatchedListed = true, somethingPlaying = false))
        assertEquals("not the first entry", false, GuideOptionsPolicy.resumeOnEntry(true, firstEntry = false, lastWatchedListed = true, somethingPlaying = false))
        assertEquals("nothing watched here", false, GuideOptionsPolicy.resumeOnEntry(true, true, lastWatchedListed = false, somethingPlaying = false))
        assertEquals("already playing", false, GuideOptionsPolicy.resumeOnEntry(true, true, true, somethingPlaying = true))
    }
}

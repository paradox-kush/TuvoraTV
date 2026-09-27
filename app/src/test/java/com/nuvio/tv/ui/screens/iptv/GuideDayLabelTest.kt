package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class GuideDayLabelTest {

    /** A local wall-clock time today (the label is calendar-based in the device's zone). */
    private fun at(hour: Int, minute: Int, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 27 + dayOffset, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `the live guide just after midnight is labelled Today`() {
        // Regression (UX, 2026-09-27 emulator pass): at 00:10 the live window opens one lookback
        // slot earlier, at 23:30 the day before, and the label read "Yesterday" while the viewer
        // was watching tonight's live schedule.
        val now = at(0, 10)
        val live = GuideTimeTravel.liveWindowStartMs(now)
        assertEquals("live window starts before midnight", 23, Calendar.getInstance().apply { timeInMillis = live }.get(Calendar.HOUR_OF_DAY))
        assertEquals("live window is today", "Today", guideDayLabel(live, now))
    }

    @Test
    fun `a window travelled wholly into yesterday is labelled Yesterday`() {
        val now = at(0, 10)
        assertEquals("travelled", "Yesterday", guideDayLabel(at(20, 0, dayOffset = -1), now))
    }

    @Test
    fun `the resting guide in the evening is Today`() {
        val now = at(21, 5)
        assertEquals("evening", "Today", guideDayLabel(GuideTimeTravel.liveWindowStartMs(now), now))
    }
}

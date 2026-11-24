package com.nuvio.tv.core.radar.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsAlertLabelsTest {
    @Test
    fun `chip texts`() {
        assertEquals("on", "Alerts on", SportsAlertLabels.hubChip(true))
        assertEquals("off", "Alerts off", SportsAlertLabels.hubChip(false))
        assertEquals("game chip", "Alerts: Default", SportsAlertLabels.gameChip(GameAlertChoice.Default))
        assertEquals("game chip all", "Alerts: All", SportsAlertLabels.gameChip(GameAlertChoice.All))
    }

    @Test
    fun `times wrap and step by the hour`() {
        assertEquals("22:00", "22:00", SportsAlertLabels.minuteOfDay(1320))
        assertEquals("08:05", "08:05", SportsAlertLabels.minuteOfDay(485))
        assertEquals("step", 1380, SportsAlertLabels.nextHour(1320))
        assertEquals("wrap", 0, SportsAlertLabels.nextHour(1380))
        assertEquals("off-hour minutes snap to the next hour", 540, SportsAlertLabels.nextHour(485))
    }

    @Test
    fun `value labels`() {
        assertEquals("no limit", "No limit", SportsAlertLabels.maxPerHour(0))
        assertEquals("cap", "6 an hour", SportsAlertLabels.maxPerHour(6))
        assertEquals("batch off", "Off", SportsAlertLabels.batch(0))
        assertEquals("batch 5", "5 minutes", SportsAlertLabels.batch(300))
        assertEquals("playback", "Live TV only", SportsAlertLabels.duringPlayback(DuringPlayback.LiveTvOnly))
    }
}

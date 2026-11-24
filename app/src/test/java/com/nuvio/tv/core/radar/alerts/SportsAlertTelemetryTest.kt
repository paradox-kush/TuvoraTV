package com.nuvio.tv.core.radar.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAlertTelemetryTest {
    private val allowed = setOf("outcome", "reason", "kind", "sport", "batch_size", "action")
    private val alert = SportsAlert("k", "evt-77", AlertKind.Score, "soccer", "L-9", "Premier League", "H-1", "A-2", "Arsenal", "Chelsea", 1, 0, "67'", false, 0L)
    private val banner = AlertBanner("Goal", null, eventId = "evt-77", kind = AlertKind.Score, sport = "soccer")

    @Test
    fun `only allow-listed properties and never a team, league, event id or score`() {
        val events = listOf(
            SportsAlertTelemetry.shown(banner),
            SportsAlertTelemetry.held(alert, HoldReason.Delay),
            SportsAlertTelemetry.dropped(alert, DropReason.QuietHours),
            SportsAlertTelemetry.action(banner, "watch"),
        )
        events.forEach { props ->
            assertTrue("keys ${props.keys}", props.keys.all { it in allowed })
            val text = props.values.joinToString("|")
            listOf("evt-77", "L-9", "H-1", "A-2", "Arsenal", "Chelsea", "Premier").forEach { secret ->
                assertTrue("leaks $secret", !text.contains(secret))
            }
        }
    }

    @Test
    fun `the outcome and reason are reported`() {
        assertEquals("dropped", mapOf("outcome" to "dropped", "reason" to "quiet_hours", "kind" to "score", "sport" to "soccer"), SportsAlertTelemetry.dropped(alert, DropReason.QuietHours))
        assertEquals("batch size", 1, SportsAlertTelemetry.shown(banner)["batch_size"])
    }
}

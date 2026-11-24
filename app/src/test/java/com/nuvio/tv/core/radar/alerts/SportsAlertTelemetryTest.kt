package com.nuvio.tv.core.radar.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAlertTelemetryTest {
    private val allowed = setOf("outcome", "reason", "kind", "sport", "batch", "action")
    private val banner = AlertBanner("Goal \u00B7 Arsenal 1\u20130 Chelsea", "Premier League", eventId = "evt-77", kind = AlertKind.Score, sport = "soccer")

    @Test
    fun `only allow-listed properties and never a team, league, event id or score`() {
        val events = listOf(
            SportsAlertTelemetry.shown(banner),
            SportsAlertTelemetry.action(banner, "watch"),
            SportsAlertTelemetry.action(banner, SportsAlertTelemetry.ACTION_DISMISSED),
        )
        events.forEach { props ->
            assertTrue("keys ${props.keys}", props.keys.all { it in allowed })
            val text = props.values.joinToString("|")
            listOf("evt-77", "Arsenal", "Chelsea", "Premier", "1\u20130").forEach { secret ->
                assertTrue("leaks $secret", !text.contains(secret))
            }
        }
    }

    @Test
    fun `the shared schema`() {
        assertEquals("shown", mapOf("outcome" to "shown", "kind" to "score", "sport" to "soccer", "batch" to 1), SportsAlertTelemetry.shown(banner))
        assertEquals(
            "action", mapOf("outcome" to "action", "action" to "open_game", "kind" to "score", "sport" to "soccer", "batch" to 1),
            SportsAlertTelemetry.action(banner, "open_game"),
        )
        assertEquals("dismissed", "dismissed", SportsAlertTelemetry.ACTION_DISMISSED)
    }

    @Test
    fun `count-only and never-emit reasons are disjoint and complete`() {
        assertEquals(
            "count only",
            setOf("watching_this_game", "quiet_hours", "during_playback", "scores_hidden", "hourly_cap", "superseded", "watch_unknown"),
            SportsAlertTelemetry.COUNT_ONLY_REASONS,
        )
        assertEquals(
            "never emit",
            setOf("not_followed", "duplicate", "stale", "disabled", "muted", "kind_off"),
            SportsAlertTelemetry.NEVER_EMIT_REASONS,
        )
        assertTrue("disjoint", SportsAlertTelemetry.COUNT_ONLY_REASONS.intersect(SportsAlertTelemetry.NEVER_EMIT_REASONS).isEmpty())
        val allReasons = DropReason.entries.map { it.wire }.toSet()
        assertTrue("every drop reason is classified", allReasons.all { it in SportsAlertTelemetry.COUNT_ONLY_REASONS || it in SportsAlertTelemetry.NEVER_EMIT_REASONS })
    }
}

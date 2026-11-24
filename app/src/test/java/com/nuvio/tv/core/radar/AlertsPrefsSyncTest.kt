package com.nuvio.tv.core.radar

import com.nuvio.tv.core.radar.alerts.AlertLevel
import com.nuvio.tv.core.radar.alerts.SportsAlertSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `alerts` object rides the radar prefs exactly like `scores_shown`: dirty flag, null keeps local, pull-then-push. */
class AlertsPrefsSyncTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val custom = SportsAlertSettings(teamLevel = AlertLevel.FinalOnly, maxPerHour = 12)
    private val remote = SportsAlertSettings(enabled = false, batchSeconds = 300)
    private val clean = RadarPrefs(alerts = custom)
    private val dirty = clean.copy(alertsDirty = true)

    private fun merge(local: RadarPrefs, alerts: SportsAlertSettings?) =
        RadarPrefsMerge.merge(local, "ev", RadarOptIn.ACCEPTED, true, null, null, alerts)

    @Test
    fun `alerts are pushed only while dirty`() {
        assertFalse("clean device sends no alerts key", RadarPrefs(alerts = custom).toSyncJson().containsKey("alerts"))
        assertTrue("dirty device sends it", RadarPrefs(alerts = custom, alertsDirty = true).toSyncJson().containsKey("alerts"))
    }

    @Test
    fun `the sync payload carries the alerts object and never the dirty flag`() {
        val payload = RadarPrefs(alerts = custom, alertsDirty = true).toSyncJson()
        val alerts = payload["alerts"] as JsonObject
        assertEquals("team level on the wire", JsonPrimitive("final"), alerts["team_level"])
        assertEquals("cap on the wire", JsonPrimitive(12), alerts["max_per_hour"])
        assertFalse("dirty stays local", payload.containsKey("alerts_dirty"))
    }

    @Test
    fun `a stored blob without the alerts key decodes to the defaults`() {
        val old = """{"featured_event_id":"x","opt_in_state":"accepted","scores_shown":false}"""
        val prefs = json.decodeFromString<RadarPrefs>(old)
        assertEquals("defaults", SportsAlertSettings(), prefs.alerts)
        assertEquals("not dirty", false, prefs.alertsDirty)
        assertEquals("other fields intact", false, prefs.scoresShown)
    }

    @Test
    fun `settings and the dirty flag survive a local round trip`() {
        val back = json.decodeFromString<RadarPrefs>(json.encodeToString(dirty))
        assertEquals("settings", custom, back.alerts)
        assertEquals("dirty", true, back.alertsDirty)
    }

    @Test
    fun `a corrupt alerts value cannot reset the rest of the local blob`() {
        val bad = """{"featured_event_id":"keep","scores_shown":false,"alerts":5}"""
        val prefs = json.decodeFromString<RadarPrefs>(bad)
        assertEquals("rest kept", "keep", prefs.featuredEventId)
        assertEquals("scores kept", false, prefs.scoresShown)
        assertEquals("alerts default", SportsAlertSettings(), prefs.alerts)
        val state = json.decodeFromString<RadarLocalState>("""{"prefs":$bad}""")
        assertEquals("whole state decodes", "keep", state.prefs.featuredEventId)
    }

    @Test
    fun `a null or absent remote value keeps the local settings`() {
        assertEquals("null remote", custom, merge(clean, null).alerts)
    }

    @Test
    fun `a remote object applies when nothing is dirty`() {
        val r = merge(clean, remote)
        assertEquals("remote applied", remote, r.alerts)
        assertEquals("still clean", false, r.alertsDirty)
    }

    @Test
    fun `pull keeps the local alerts when dirty but still applies the other prefs`() {
        val r = merge(dirty, remote)
        assertEquals("local kept", custom, r.alerts)
        assertEquals("still dirty", true, r.alertsDirty)
        assertEquals("featured applies", "ev", r.featuredEventId)
    }

    @Test
    fun `an alerts change does not disturb the hide-scores protocol`() {
        val both = RadarPrefs(scoresShown = false, spoilerDirty = true, alerts = custom)
        val r = RadarPrefsMerge.merge(both, "ev", RadarOptIn.ACCEPTED, true, true, 0, remote)
        assertEquals("spoiler dirty keeps local shown", false, r.scoresShown)
        assertEquals("alerts apply since not dirty", remote, r.alerts)
    }

    @Test
    fun `a change writes value and dirty together`() {
        val p = AlertsSync.markChanged(RadarPrefs(), custom)
        assertEquals("value", custom, p.alerts)
        assertEquals("dirty", true, p.alertsDirty)
    }

    @Test
    fun `push clears dirty only when the settings still match what was pushed`() {
        assertEquals("matches", false, AlertsSync.clearIfPushed(dirty, custom).alertsDirty)
        assertEquals("edited after the push read stays dirty", true, AlertsSync.clearIfPushed(dirty.copy(alerts = remote), custom).alertsDirty)
        assertEquals("clean stays clean", false, AlertsSync.clearIfPushed(clean, custom).alertsDirty)
    }

    @Test
    fun `a pull that leaves alerts dirty triggers a push`() {
        assertTrue("dirty -> push", AlertsSync.shouldPushAfterPull(merge(dirty, remote)))
        assertFalse("clean -> no push", AlertsSync.shouldPushAfterPull(merge(clean, remote)))
    }
}

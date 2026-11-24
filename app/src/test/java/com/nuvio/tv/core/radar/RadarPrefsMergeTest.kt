package com.nuvio.tv.core.radar

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RadarPrefsMergeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val clean = RadarPrefs(scoresShown = false, scoreDelayS = 30)
    private val dirty = clean.copy(spoilerDirty = true)

    private fun merge(local: RadarPrefs, shown: Boolean?, delay: Int?) =
        RadarPrefsMerge.merge(local, "ev", RadarOptIn.ACCEPTED, true, shown, delay)

    @Test
    fun `dirty survives a decode round trip and is not part of the sync payload`() {
        val back = json.decodeFromString<RadarPrefs>(json.encodeToString(dirty))
        assertEquals("dirty persisted", true, back.spoilerDirty)
        assertFalse("never sent to the server", RadarPrefs(spoilerDirty = true).toSyncJson().containsKey("spoiler_dirty"))
    }

    @Test
    fun `a server row without the columns keeps the local setting`() {
        val r = merge(clean, null, null)
        assertEquals("shown kept", false, r.scoresShown)
        assertEquals("delay kept", 30, r.scoreDelayS)
        assertEquals("other fields come from the server", "ev", r.featuredEventId)
    }

    @Test
    fun `server values apply when present and nothing is dirty`() {
        val r = merge(clean, true, 15)
        assertEquals("shown", true, r.scoresShown)
        assertEquals("delay", 15, r.scoreDelayS)
        assertEquals("still clean", false, r.spoilerDirty)
    }

    @Test
    fun `pull keeps the local setting when dirty but still applies other prefs`() {
        val r = merge(dirty, true, 0)
        assertEquals("local shown kept", false, r.scoresShown)
        assertEquals("local delay kept", 30, r.scoreDelayS)
        assertEquals("still dirty", true, r.spoilerDirty)
        assertEquals("featured applies", "ev", r.featuredEventId)
        assertEquals("opt-in applies", RadarOptIn.ACCEPTED, r.optInState)
    }

    @Test
    fun `an invalid server delay reads as off`() {
        assertEquals("sanitised", 0, merge(RadarPrefs(), true, 45).scoreDelayS)
    }

    @Test
    fun `a toggle writes value and dirty together`() {
        val p = SpoilerSync.markChanged(RadarPrefs(), shown = false)
        assertEquals("shown", false, p.scoresShown)
        assertEquals("dirty", true, p.spoilerDirty)
        val d = SpoilerSync.markChanged(RadarPrefs(), delayS = 45)
        assertEquals("delay sanitised", 0, d.scoreDelayS)
        assertEquals("dirty", true, d.spoilerDirty)
    }

    @Test
    fun `push clears dirty only when the values still match what was pushed`() {
        assertEquals("matches", false, SpoilerSync.clearIfPushed(dirty, false, 30).spoilerDirty)
        assertEquals("a toggle after the push read stays dirty", true, SpoilerSync.clearIfPushed(dirty.copy(scoresShown = true), false, 30).spoilerDirty)
        assertEquals("delay changed after the push read stays dirty", true, SpoilerSync.clearIfPushed(dirty.copy(scoreDelayS = 60), false, 30).spoilerDirty)
        assertEquals("clean stays clean", false, SpoilerSync.clearIfPushed(clean, true, 0).spoilerDirty)
    }

    @Test
    fun `a pull that leaves the setting dirty triggers a push`() {
        assertEquals("dirty -> push", true, SpoilerSync.shouldPushAfterPull(merge(dirty, true, 0)))
        assertEquals("clean -> no push", false, SpoilerSync.shouldPushAfterPull(merge(clean, true, 0)))
    }
}

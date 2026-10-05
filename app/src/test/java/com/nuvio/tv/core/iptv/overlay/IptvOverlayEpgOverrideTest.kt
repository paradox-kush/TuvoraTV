package com.nuvio.tv.core.iptv.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * F14 manual guide picks ride the overlay's delta sync as kind "epg": dirty rows only, cleared on
 * ack, LWW on pull, per profile, purged with the playlist. TV twin of the KMP
 * IptvOverlayEpgOverrideTest (JUnit order: message, expected, actual). Lane G, additive to lane B's DB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IptvOverlayEpgOverrideTest {

    private val db = IptvOverlayDb(RuntimeEnvironment.getApplication())

    @Test
    fun `a pick is pushed once as a delta row and cleared on ack`() {
        db.setEpgOverride(1, "fp:v1:a", "pl", "itv1.uk", "ITV1", 100)
        val rows = db.channelRowsForPush(1).filter { it.kind == IptvOverlayDb.EPG_KIND }
        assertEquals(1, rows.size)
        assertEquals("{\"guide_id\":\"itv1.uk\",\"guide_name\":\"ITV1\"}", rows.single().valueJson)
        db.markChannelsPushed(1, rows)
        assertTrue("acked rows are not re-sent", db.channelRowsForPush(1).none { it.kind == IptvOverlayDb.EPG_KIND })
        assertEquals(mapOf("fp:v1:a" to "itv1.uk"), db.epgOverrides(1, "pl"))
    }

    @Test
    fun `clearing a pick pushes a delete and drops it from reads`() {
        db.setEpgOverride(1, "fp:v1:a", "pl", "itv1.uk", "ITV1", 100)
        db.setEpgOverride(1, "fp:v1:a", "pl", null, null, 200)
        assertTrue(db.channelRowsForPush(1).single { it.kind == IptvOverlayDb.EPG_KIND }.deleted)
        assertEquals(emptyMap<String, String>(), db.epgOverrides(1, "pl"))
    }

    @Test
    fun `a stale pulled pick never overwrites a newer local one`() {
        db.setEpgOverride(1, "fp:v1:a", "pl", "new.uk", null, 300)
        db.applyRemoteEpg(1, "fp:v1:a", "pl", "old.uk", null, 200, deleted = false)
        assertEquals(mapOf("fp:v1:a" to "new.uk"), db.epgOverrides(1, "pl"))
        db.applyRemoteEpg(1, "fp:v1:a", "pl", "web.uk", null, 400, deleted = false)
        assertEquals(mapOf("fp:v1:a" to "web.uk"), db.epgOverrides(1, "pl"))
    }

    @Test
    fun `picks are per profile but the ingest keeps every profile's channel`() {
        db.setEpgOverride(1, "fp:v1:a", "pl", "a.uk", null, 100)
        db.setEpgOverride(2, "fp:v1:a", "pl", "b.uk", null, 100)
        assertEquals(mapOf("fp:v1:a" to "a.uk"), db.epgOverrides(1, "pl"))
        assertEquals(setOf("a.uk", "b.uk"), db.epgOverrideGuideIds("pl"))
        db.purgePlaylist(1, "pl")
        assertEquals(setOf("b.uk"), db.epgOverrideGuideIds("pl"))
    }
}

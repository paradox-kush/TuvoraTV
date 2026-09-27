package com.nuvio.tv.core.iptv.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real-SQLite (Robolectric) tests for the overlay delta-push / dirty tracking — TV twin of
 * NuvioMobile's IptvOverlayStoreTest. Regression for the 2026-09-19 write-amplification: a push must
 * send only rows dirtied since the last ack, not the whole overlay set on every edit. The pull path
 * reuses setChannel with an explicit deletedOverride, so a remote apply must NOT become dirty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IptvOverlayDbTest {

    private val db = IptvOverlayDb(RuntimeEnvironment.getApplication())

    @Test
    fun `push sends only dirty channel rows and an ack clears them`() {
        db.setChannel(1, "fp:v1:bbc", "pl", ChannelOverlay(hidden = true), 100)
        val pending = db.channelRowsForPush(1)
        assertEquals(1, pending.size)
        assertEquals("fp:v1:bbc", pending.single().okey)
        db.markChannelsPushed(1, pending)
        assertTrue(db.channelRowsForPush(1).isEmpty()) // nothing owed once the server acked it
    }

    @Test
    fun `a pulled remote channel edit is never pushed back`() {
        db.setChannel(1, "fp:v1:remote", "pl", ChannelOverlay(pinned = true), 100, deletedOverride = false)
        assertTrue(db.channelRowsForPush(1).isEmpty()) // remote apply is not a local edit -> not dirty
        assertEquals(ChannelOverlay(pinned = true), db.snapshot(1).channels["fp:v1:remote"])
    }

    @Test
    fun `a row re-edited during a push stays dirty and is resent`() {
        db.setChannel(1, "fp:v1:x", "pl", ChannelOverlay(hidden = true), 100)
        val inflight = db.channelRowsForPush(1) // captured at updated_at=100
        db.setChannel(1, "fp:v1:x", "pl", ChannelOverlay(pinned = true), 200) // re-edited mid-push
        db.markChannelsPushed(1, inflight) // ack clears only the updated_at=100 version
        val still = db.channelRowsForPush(1)
        assertEquals(1, still.size)
        assertEquals(200, still.single().updatedAt)
    }

    @Test
    fun `a stale remote event does not clobber a newer pending local edit`() {
        db.setChannel(1, "fp:v1:y", "pl", ChannelOverlay(rename = "Local"), 200)
        db.setChannel(1, "fp:v1:y", "pl", ChannelOverlay(rename = "Older"), 100, deletedOverride = false) // remote, older
        assertEquals("Local", db.snapshot(1).channels["fp:v1:y"]?.rename)
        assertEquals(1, db.channelRowsForPush(1).size) // local edit still owed to the server
    }

    // F02: a group hidden on the TV must reach the website and the other devices. Only channel rows
    // were pushed, so a device category edit stayed dirty on the TV forever.
    @Test
    fun `a device category hide is pushed with its content type and an ack clears it`() {
        db.setCategory(1, "pl", "movies", "c:v1:horror", CategoryOverlay(hidden = true), 100)
        val pending = db.channelRowsForPush(1)
        assertEquals("one row owed", 1, pending.size)
        val row = pending.single()
        assertEquals("kind", "category", row.kind)
        assertEquals("okey", "c:v1:horror", row.okey)
        assertEquals("playlist", "pl", row.playlistId)
        assertTrue(row.valueJson, "\"content_type\":\"movies\"" in row.valueJson)
        assertTrue(row.valueJson, "\"hidden\":true" in row.valueJson)
        db.markChannelsPushed(1, pending)
        assertTrue("nothing owed after the ack", db.channelRowsForPush(1).isEmpty())
    }

    @Test
    fun `a pulled remote category edit is never pushed back`() {
        db.setCategory(1, "pl", "live", "c:v1:uk", CategoryOverlay(hidden = true), 100, deletedOverride = false)
        assertTrue("remote apply is not a local edit", db.channelRowsForPush(1).isEmpty())
    }

    @Test
    fun `unhiding a device category is pushed as a delete`() {
        db.setCategory(1, "pl", "live", "c:v1:uk", CategoryOverlay(hidden = true), 100)
        db.markChannelsPushed(1, db.channelRowsForPush(1))
        db.setCategory(1, "pl", "live", "c:v1:uk", CategoryOverlay(), 200)
        val row = db.channelRowsForPush(1).single()
        assertEquals("kind", "category", row.kind)
        assertTrue("an unhide is a delete", row.deleted)
    }
}

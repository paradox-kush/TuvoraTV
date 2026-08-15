package com.nuvio.tv.core.sync

import com.nuvio.tv.core.iptv.XtreamAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sync pull REPLACES the account list with objects rebuilt from the wire, so any field the payload
 * does not carry returns as its constructor default. The catch-up preferences are deliberately not
 * on the wire — they tune one panel's URL dialect as reached from THIS device — which makes them
 * exactly the fields a pull would silently reset.
 *
 * That is a bug nobody can report ("my setting keeps un-setting itself") and it only appears once a
 * second device exists, so it gets pinned rather than eyeballed.
 */
class XtreamCatchUpPrefsSyncTest {

    private fun account(
        id: String,
        preferM3u8: Boolean = false,
        correction: Int = 0,
    ) = XtreamAccount(
        id = id, name = "Panel", baseUrl = "http://host:8080", username = "u", password = "p",
        preferM3u8CatchUp = preferM3u8, catchUpCorrectionMinutes = correction,
    )

    @Test
    fun `a pull keeps this device's catch-up preferences`() {
        val local = listOf(account("http://host:8080|u", preferM3u8 = true, correction = -120))
        // What the wire rebuilds: the shared options, and the catch-up fields at their defaults.
        val pulled = listOf(account("http://host:8080|u"))

        val merged = preserveDeviceLocalPrefs(pulled, local).single()
        assertTrue("container preference kept", merged.preferM3u8CatchUp)
        assertEquals("time correction kept", -120, merged.catchUpCorrectionMinutes)
    }

    /** The rest of the pulled account still wins — this preserves two fields, not the whole object. */
    @Test
    fun `everything else still comes from the pull`() {
        val local = listOf(
            account("http://host:8080|u", preferM3u8 = true).copy(name = "Old name", enabled = false)
        )
        val pulled = listOf(account("http://host:8080|u").copy(name = "New name", enabled = true))

        val merged = preserveDeviceLocalPrefs(pulled, local).single()
        assertEquals("remote name wins", "New name", merged.name)
        assertTrue("remote enabled wins", merged.enabled)
        assertTrue("but the local preference survives", merged.preferM3u8CatchUp)
    }

    /** A playlist added on another device has no local preferences to keep. */
    @Test
    fun `an account this device has never seen takes the defaults`() {
        val merged = preserveDeviceLocalPrefs(listOf(account("http://other|u")), emptyList()).single()
        assertFalse("default container", merged.preferM3u8CatchUp)
        assertEquals("default correction", 0, merged.catchUpCorrectionMinutes)
    }

    /** Matching is by id, so one playlist's preference can never leak onto another's. */
    @Test
    fun `preferences do not leak between playlists`() {
        val local = listOf(
            account("a", preferM3u8 = true, correction = 60),
            account("b"),
        )
        val merged = preserveDeviceLocalPrefs(listOf(account("a"), account("b")), local)
        assertTrue("a keeps its own", merged.first { it.id == "a" }.preferM3u8CatchUp)
        assertFalse("b is untouched", merged.first { it.id == "b" }.preferM3u8CatchUp)
        assertEquals("and b's correction too", 0, merged.first { it.id == "b" }.catchUpCorrectionMinutes)
    }
}

package com.nuvio.tv.core.recommendations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit argument order: assertEquals(message, expected, actual). */
class LauncherAirPolicyTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `cached not-aired with a past release timestamp is aired`() {
        assertTrue("aired while the app was closed", LauncherAirPolicy.hasAired(now - 1_000L, false, now))
    }

    @Test
    fun `cached not-aired with a future release timestamp stays unaired`() {
        assertFalse("not yet", LauncherAirPolicy.hasAired(now + 1_000L, false, now))
    }

    @Test
    fun `an episode airing exactly now has aired`() {
        assertTrue("boundary", LauncherAirPolicy.hasAired(now, false, now))
    }

    @Test
    fun `a null release timestamp trusts the cache`() {
        assertTrue("cache says aired", LauncherAirPolicy.hasAired(null, true, now))
        assertFalse("cache says not aired", LauncherAirPolicy.hasAired(null, false, now))
    }

    @Test
    fun `something the cache says has aired never un-airs`() {
        assertTrue("monotonic", LauncherAirPolicy.hasAired(now + day, true, now))
    }

    @Test
    fun `an episode released after the last watch and within 60 days is a release alert`() {
        assertTrue(
            "new episode",
            LauncherAirPolicy.isReleaseAlert(false, aired = true, releaseTimestamp = now - day, lastWatchedMs = now - 10 * day, nowMs = now)
        )
    }

    @Test
    fun `an older release or one before the last watch is not a release alert`() {
        assertFalse(
            "older than 60 days",
            LauncherAirPolicy.isReleaseAlert(false, true, now - 61 * day, now - 90 * day, now)
        )
        assertFalse(
            "released before the last watch",
            LauncherAirPolicy.isReleaseAlert(false, true, now - 10 * day, now - day, now)
        )
        assertFalse(
            "not aired",
            LauncherAirPolicy.isReleaseAlert(false, false, now - day, now - 10 * day, now)
        )
        assertFalse(
            "no timestamp",
            LauncherAirPolicy.isReleaseAlert(false, true, null, now - 10 * day, now)
        )
    }

    @Test
    fun `a cached release alert stays a release alert`() {
        assertEquals(
            "cached alert",
            true,
            LauncherAirPolicy.isReleaseAlert(true, true, null, now, now)
        )
    }
}

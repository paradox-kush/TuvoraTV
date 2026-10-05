package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B03 (D1/D2) — which surfaces a device must pull after it may have missed Realtime invalidations
 * (asleep, socket dropped, channel closed). Event as hint, pull as truth: compare the server's version
 * vector with what this device last pulled.
 */
class SurfaceCatchUpPolicyTest {

    private fun v(surface: String, version: Long, profile: Int? = 1) = SurfaceVersion(profile, surface, version)
    private val supported = setOf("addons", "profile_settings", "profiles", "library")

    @Test
    fun aWebEditedSurfaceIsPulled() {
        val seen = SurfaceCatchUpPolicy.seenOf(listOf(v("profile_settings", 4), v("addons", 3)))
        val plan = SurfaceCatchUpPolicy.plan(seen, listOf(v("profile_settings", 5), v("addons", 3)), supported)
        assertEquals(listOf(v("profile_settings", 5)), plan)
    }

    @Test
    fun anIdleSlateMakesNoPulls() {
        val server = listOf(v("profile_settings", 5), v("addons", 3), v("profiles", 9, null))
        assertTrue(SurfaceCatchUpPolicy.plan(SurfaceCatchUpPolicy.seenOf(server), server, supported).isEmpty())
    }

    @Test
    fun accountWideRowsAreTrackedApartFromProfileRows() {
        val seen = SurfaceCatchUpPolicy.seenOf(listOf(v("addons", 3, 1)))
        val plan = SurfaceCatchUpPolicy.plan(seen, listOf(v("addons", 3, 1), v("addons", 1, null)), supported)
        assertEquals(listOf(v("addons", 1, null)), plan)
    }

    @Test
    fun unsupportedSurfacesAreIgnored() {
        assertTrue(SurfaceCatchUpPolicy.plan(emptyMap(), listOf(v("radar", 2)), supported).isEmpty())
    }

    @Test
    fun seenAdvancesOnlyForWhatWasPulled() {
        val seen = SurfaceCatchUpPolicy.seenOf(listOf(v("addons", 3)))
        val advanced = SurfaceCatchUpPolicy.advance(seen, listOf(v("profile_settings", 5)))
        assertEquals(3L, advanced[SurfaceCatchUpPolicy.key(1, "addons")])
        assertEquals(5L, advanced[SurfaceCatchUpPolicy.key(1, "profile_settings")])
    }

    @Test
    fun seenNeverMovesBackwards() {
        val seen = SurfaceCatchUpPolicy.seenOf(listOf(v("addons", 7)))
        assertEquals(7L, SurfaceCatchUpPolicy.advance(seen, listOf(v("addons", 2)))[SurfaceCatchUpPolicy.key(1, "addons")])
    }
}

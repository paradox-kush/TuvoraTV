package com.nuvio.tv.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingProviderVisibilityTest {

    private fun configured(vararg ids: TrackingProviderId): (TrackingProviderId) -> Boolean =
        { it in ids.toSet() }

    @Test
    fun `a fully configured build offers both providers`() {
        assertEquals(
            listOf(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL),
            visibleTrackingProviders(configured(TrackingProviderId.TRAKT, TrackingProviderId.SIMKL)),
        )
    }

    /**
     * The shipped state as of 2026-08: TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET are not set on the
     * release builds, so every Trakt call fails. Users reported the connect dialog erroring out and
     * concluded the feature had been dropped on purpose; one told another in Discord that "he's not
     * supporting it, probably there is a placeholder". An absent provider should be absent.
     */
    @Test
    fun `a build without Trakt credentials does not offer Trakt`() {
        assertEquals(
            listOf(TrackingProviderId.SIMKL),
            visibleTrackingProviders(configured(TrackingProviderId.SIMKL)),
        )
    }

    @Test
    fun `a build without Simkl credentials does not offer Simkl`() {
        assertEquals(
            listOf(TrackingProviderId.TRAKT),
            visibleTrackingProviders(configured(TrackingProviderId.TRAKT)),
        )
    }

    @Test
    fun `a build with neither offers nothing`() {
        assertEquals(emptyList<TrackingProviderId>(), visibleTrackingProviders(configured()))
    }
}

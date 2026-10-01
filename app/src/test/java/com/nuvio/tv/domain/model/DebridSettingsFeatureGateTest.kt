package com.nuvio.tv.domain.model

import com.nuvio.tv.core.debrid.DebridProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store builds compile Debrid out (AppFeaturePolicy.debridEnabled = false). Saved keys still sync in
 * from other devices / the web dashboard, so the settings model must refuse every debrid capability
 * when the feature is unavailable — while leaving the keys themselves readable so sync never wipes them.
 */
class DebridSettingsFeatureGateTest {

    private fun configured(featureAvailable: Boolean) = DebridSettings(
        featureAvailable = featureAvailable,
        enabled = true,
        cloudLibraryEnabled = true,
        torboxApiKey = "tb-key",
        premiumizeApiKey = "pm-key",
        realDebridApiKey = "rd-key",
        preferredResolverProviderId = DebridProviders.TORBOX_ID
    )

    @Test
    fun `unavailable feature disables link resolving and cloud library but keeps saved keys`() {
        val settings = configured(featureAvailable = false)

        assertFalse("link resolving must be off in store builds", settings.canResolvePlayableLinks)
        assertFalse("cloud library must be inactive in store builds", settings.cloudLibraryActive)
        assertFalse("cloud library must be unusable in store builds", settings.canUseCloudLibrary)

        assertEquals("saved Torbox key must survive untouched", "tb-key", settings.apiKeyFor(DebridProviders.TORBOX_ID))
        assertEquals("saved Premiumize key must survive untouched", "pm-key", settings.apiKeyFor(DebridProviders.PREMIUMIZE_ID))
        assertEquals("saved Real-Debrid key must survive untouched", "rd-key", settings.apiKeyFor(DebridProviders.REAL_DEBRID_ID))
        assertTrue("user preference is preserved, only capability is gated", settings.enabled)
        assertTrue("user preference is preserved, only capability is gated", settings.cloudLibraryEnabled)
    }

    @Test
    fun `available feature keeps full-build behaviour`() {
        val settings = configured(featureAvailable = true)

        assertTrue("full builds still resolve links", settings.canResolvePlayableLinks)
        assertTrue("full builds still activate the cloud library", settings.cloudLibraryActive)
        assertTrue("full builds still use the cloud library", settings.canUseCloudLibrary)
    }

    @Test
    fun `feature is available by default so existing callers are unchanged`() {
        assertTrue("default must match full-build behaviour", DebridSettings().featureAvailable)
    }
}

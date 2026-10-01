package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsCredentialPolicyTest {
    @Test
    fun `non tracker credentials are excluded from profile settings blobs`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "torbox_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "premiumize_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "real_debrid_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_client_id"))
    }

    @Test
    fun `tracker and non credential settings remain in their existing sync surfaces`() {
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("trakt_settings", "trakt_access_token"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "debrid_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_enabled"))
    }

    @Test
    fun `remote blob import keeps the local provider credentials it cannot carry`() {
        // B82: importing a remote blob clears each feature's store; the credential keys are never in the blob, so
        // they must be carried over or the TV wipes its MDBList/debrid/AnimeSkip keys and pushes the blanks.
        assertEquals(
            "debrid keys survive an import",
            setOf("torbox_api_key", "premiumize_api_key", "real_debrid_api_key"),
            profileSettingsKeysKeptOnImport("debrid_settings")
        )
        assertEquals(
            "MDBList key survives an import",
            setOf("mdblist_api_key"),
            profileSettingsKeysKeptOnImport("mdblist_settings")
        )
        assertEquals(
            "AnimeSkip client id survives an import",
            setOf("animeskip_client_id"),
            profileSettingsKeysKeptOnImport("animeskip_settings")
        )
    }

    @Test
    fun `features without local only keys keep nothing on import`() {
        assertTrue(profileSettingsKeysKeptOnImport("trakt_settings").isEmpty())
        assertTrue(profileSettingsKeysKeptOnImport("theme_settings").isEmpty())
    }
}

package com.nuvio.tv.core.sync

import org.junit.Assert.assertTrue
import org.junit.Test

class TvHomeSettingsSyncPolicyTest {
    @Test
    fun `TV home screen switches stay on this device`() {
        assertTrue(
            "publish switch is local only",
            shouldExcludePreferenceFromProfileSettingsSync("layout_settings", "tv_home_publish_enabled")
        )
        assertTrue(
            "next episodes switch is local only",
            shouldExcludePreferenceFromProfileSettingsSync("layout_settings", "tv_home_include_next_up")
        )
        assertTrue(
            "kept across a remote settings import",
            "tv_home_publish_enabled" in profileSettingsKeysKeptOnImport("layout_settings")
        )
    }
}

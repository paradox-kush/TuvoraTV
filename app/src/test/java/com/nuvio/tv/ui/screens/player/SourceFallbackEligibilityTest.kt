package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Upstream's "fall back to the next source on failure" must never auto-switch away from, or replay,
 * an IPTV / Stalker live, catch-up or single-use link: those own a bounded reconnect, and every extra
 * open of a single-use link spends the line.
 */
class SourceFallbackEligibilityTest {
    private fun may(
        iptvId: String? = null,
        isCatchUp: Boolean = false,
        isOwnSourceLabel: Boolean = false,
        singleUseLink: Boolean = false,
    ) = SourceFallbackEligibility.mayFallBackFrom(iptvId, isCatchUp, isOwnSourceLabel, singleUseLink)

    @Test
    fun `an add-on source may fall back`() {
        assertEquals("plain add-on source", true, may())
    }

    @Test
    fun `an IPTV channel or episode never falls back`() {
        assertEquals("iptv id", false, may(iptvId = "xtream:1:live:42"))
    }

    @Test
    fun `catch-up never falls back`() {
        assertEquals("catch-up", false, may(isCatchUp = true))
    }

    @Test
    fun `a matched IPTV copy never falls back`() {
        assertEquals("own source label", false, may(isOwnSourceLabel = true))
    }

    @Test
    fun `a single-use link never falls back`() {
        assertEquals("single use", false, may(singleUseLink = true))
    }

    private fun stream(name: String, url: String, addon: String = "Addon", kind: String? = null) = Stream(
        name = name, title = null, description = null, url = url, ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = addon, addonLogo = null,
        deviceSourceKind = kind,
    )

    @Test
    fun `the fallback never picks an IPTV source as the next candidate`() {
        val settings = PlayerSettings(
            streamAutoPlayFallbackOnFailure = true,
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
        )
        val iptv = stream("IPTV copy", "https://panel.example/play/1", addon = "My Playlist", kind = "iptv")
        val next = nextSourceFallbackCandidate(
            streams = listOf(iptv),
            settings = settings,
            installedAddonNames = emptySet(),
            triedKeys = emptySet(),
            failedAddonName = "Addon",
            failedInfoHash = null,
        )
        assertNull("an IPTV candidate would mint a single-use link", next)
    }
}

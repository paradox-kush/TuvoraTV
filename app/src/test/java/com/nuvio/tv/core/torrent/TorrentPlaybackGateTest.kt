package com.nuvio.tv.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentPlaybackGateTest {

    @Test
    fun `non-torrent streams always proceed`() {
        assertEquals(
            "a plain HTTP stream never needs P2P consent",
            TorrentGateDecision.PROCEED,
            TorrentPlaybackGate.decide(isTorrent = false, p2pAvailableInBuild = false, p2pEnabled = false)
        )
    }

    @Test
    fun `torrent with P2P off asks for consent`() {
        assertEquals(
            "first torrent play in a full build shows the consent dialog",
            TorrentGateDecision.ASK_CONSENT,
            TorrentPlaybackGate.decide(isTorrent = true, p2pAvailableInBuild = true, p2pEnabled = false)
        )
    }

    @Test
    fun `torrent with P2P on proceeds`() {
        assertEquals(
            "P2P already enabled plays straight away",
            TorrentGateDecision.PROCEED,
            TorrentPlaybackGate.decide(isTorrent = true, p2pAvailableInBuild = true, p2pEnabled = true)
        )
    }

    // B06 regression: pressing "Enable P2P" wrote the setting asynchronously, then re-ran the gate
    // against the still-false Compose state, so the dialog re-opened in the same frame and the
    // press looked like it did nothing.
    @Test
    fun `consent just granted proceeds even while the saved setting is still false`() {
        assertEquals(
            "Enable P2P must start playback without waiting for the DataStore round trip",
            TorrentGateDecision.PROCEED,
            TorrentPlaybackGate.decide(
                isTorrent = true,
                p2pAvailableInBuild = true,
                p2pEnabled = false,
                consentJustGranted = true
            )
        )
    }

    // B06 regression: store builds force P2P off, so the consent dialog could never succeed and
    // re-opened forever. They must say P2P is unavailable instead of asking.
    @Test
    fun `torrent in a build without P2P is unavailable, never a consent loop`() {
        assertEquals(
            "store flavor cannot enable P2P, so it must not offer the consent dialog",
            TorrentGateDecision.UNAVAILABLE,
            TorrentPlaybackGate.decide(isTorrent = true, p2pAvailableInBuild = false, p2pEnabled = false)
        )
        assertEquals(
            "a just-granted consent cannot override a build without P2P",
            TorrentGateDecision.UNAVAILABLE,
            TorrentPlaybackGate.decide(
                isTorrent = true,
                p2pAvailableInBuild = false,
                p2pEnabled = false,
                consentJustGranted = true
            )
        )
    }
}

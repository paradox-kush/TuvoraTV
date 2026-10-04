package com.nuvio.tv.core.torrent

/** What the stream screen should do with a selected stream before it reaches a player. */
enum class TorrentGateDecision {
    /** Not a torrent, or P2P is on (or the user just agreed): hand the stream to the player. */
    PROCEED,

    /** A torrent while P2P is off in a build that has it: show the P2P consent dialog. */
    ASK_CONSENT,

    /** A torrent in a build compiled without P2P (store flavor): consent can never turn it on. */
    UNAVAILABLE
}

/**
 * Pure decision for "can this stream play, or do we need P2P consent first?" (B06).
 *
 * Kept out of the composable so it is testable without Compose, DataStore or the player.
 */
object TorrentPlaybackGate {
    /**
     * @param p2pAvailableInBuild `AppFeaturePolicy.p2pEnabled` — false in store flavors, where the
     *   saved setting is forced off and consent can never take effect.
     * @param p2pEnabled the saved setting as last observed (may lag a just-written value).
     * @param consentJustGranted the user pressed "Enable P2P" for this very stream; the setting
     *   write is asynchronous, so the observed [p2pEnabled] is still false at this point.
     */
    fun decide(
        isTorrent: Boolean,
        p2pAvailableInBuild: Boolean,
        p2pEnabled: Boolean,
        consentJustGranted: Boolean = false
    ): TorrentGateDecision = when {
        !isTorrent -> TorrentGateDecision.PROCEED
        !p2pAvailableInBuild -> TorrentGateDecision.UNAVAILABLE
        p2pEnabled || consentJustGranted -> TorrentGateDecision.PROCEED
        else -> TorrentGateDecision.ASK_CONSENT
    }
}

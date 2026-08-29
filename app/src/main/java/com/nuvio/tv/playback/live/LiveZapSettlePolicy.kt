package com.nuvio.tv.playback.live

/**
 * The one zap settle window shared by every live surface. Held-remote navigation accumulates
 * into ONE committed tune this many milliseconds after the last press, so walking N channels
 * costs one provider connection and lands exactly on the aimed channel. 450ms is the
 * 1.5.8-proven product value (legacy LiveChannelZapPolicy.COMMIT_DELAY_MS); change it here or
 * nowhere.
 */
object LiveZapSettlePolicy {
    const val SETTLE_MS = 450L
}

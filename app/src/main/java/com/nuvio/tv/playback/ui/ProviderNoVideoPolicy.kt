package com.nuvio.tv.playback.ui

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy

/**
 * B259: a Stalker link was minted fine, then the provider's stream server sent nothing (a connection
 * that delivers 0 bytes, or a 200 with an empty body), and the viewer read "Live TV playback failed."
 * or "The channel uses an unreadable stream format." Neither says who failed.
 *
 * Applies only when ALL hold, so a named cause always keeps its own sentence:
 *  - the session ended FAILED on a live link that is single-use (`SESSION_ONLY` — only a minted Stalker
 *    link is, ProviderLinkReusePolicy) and was resolved (a request summary exists);
 *  - the last start attempt rendered nothing (no first frame, no audio): it failed at startup;
 *  - the failure has no named cause:
 *     - UNKNOWN reported by the engine (phase ENGINE_START / PLAYBACK). An UNKNOWN in the resolution or
 *       recovery phase can be the mint itself failing, so it keeps the generic wording;
 *     - NO_PROGRESS (a start watchdog: never produced by a mint);
 *     - DEMUX_FAILED / MANIFEST_INVALID with no byte received: "unreadable format" is the engine's
 *       guess about bytes that never came (mpv's bare "loading failed" maps to DEMUX_FAILED).
 * NETWORK_TIMEOUT / UNREACHABLE, HTTP statuses, connection limit, decoder, TLS, reconnect exhaustion
 * and every other code are named and keep their sentences.
 */
object ProviderNoVideoPolicy {
    fun applies(snapshot: PlaybackSnapshot): Boolean {
        if (snapshot.state != PlaybackState.FAILED) return false
        val summary = snapshot.requestSummary ?: return false
        if (summary.contentType != ContentType.LIVE) return false
        if (summary.transientLoadRetryPolicy != TransientLoadRetryPolicy.SESSION_ONLY) return false
        val progress = snapshot.progress
        if (progress.renderedVideoFrame || progress.renderedAudio) return false
        val failure = snapshot.failure ?: return false
        return when (failure.code) {
            FailureCode.UNKNOWN -> failure.phase == FailurePhase.ENGINE_START || failure.phase == FailurePhase.PLAYBACK
            FailureCode.NO_PROGRESS -> true
            FailureCode.DEMUX_FAILED,
            FailureCode.MANIFEST_INVALID,
            -> !progress.receivedBytes
            else -> false
        }
    }
}

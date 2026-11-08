package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameState

/** What the preview box at the top of the right column shows. */
sealed interface PreviewSlot {
    /** No box (finished / upcoming games). */
    data object Hidden : PreviewSlot
    /** The channel lookup is still running. */
    data object Searching : PreviewSlot
    /** Muted live preview of this channel, started after the dwell. */
    data class Live(val contentId: String, val name: String) : PreviewSlot
    /** A match exists but must not be previewed (single-use link, or only a possible match): logo + "Press OK to watch". */
    data class PressOk(val name: String, val logoUrl: String?) : PreviewSlot
    /** Nothing matched: the box carries the Find channels action instead of a black rectangle. */
    data class NoMatch(val failed: Boolean) : PreviewSlot
    /** Opened while watching: what is already playing, never a second stream. */
    data class Watching(val name: String?) : PreviewSlot
    /** The preview failed once: a quiet message with the reason, no retry loop. */
    data class Unavailable(val failure: PreviewFailure? = null) : PreviewSlot
}

/**
 * Why a preview did not play: [code] goes to telemetry (never a URL, host or title), [label] is the short reason shown in
 * the box ("Preview unavailable: stream timed out").
 */
data class PreviewFailure(val code: String, val label: String) {
    companion object {
        val NO_LINEUP = PreviewFailure("no_lineup", "not in your channel list")
        val TIMEOUT = PreviewFailure("timeout", "stream timed out")

        /** The clean live host refused to start: [reason] is the guide playback failure name. */
        fun rejected(reason: String) = PreviewFailure(
            "rejected_${reason.lowercase()}",
            when (reason) {
                "SELECTION_UNAVAILABLE" -> "channel unavailable"
                "PROFILE_CHANGED" -> "profile changed"
                "INVALID_TARGET" -> "channel not playable"
                "HOST_CREATION_FAILED" -> "player could not start"
                "TUNE_FAILED" -> "could not tune the channel"
                else -> "player error"
            },
        )

        /** The stream started and then failed: [engineCode] is the playback FailureCode name. */
        fun stream(engineCode: String?) = PreviewFailure(
            "stream_${(engineCode ?: "unknown").lowercase()}",
            when (engineCode) {
                "NETWORK_TIMEOUT", "NO_PROGRESS" -> "stream timed out"
                "NETWORK_UNREACHABLE" -> "stream unreachable"
                "AUTHORIZATION_REJECTED", "PROVIDER_CONNECTION_LIMIT" -> "provider refused the stream"
                "MANIFEST_INVALID", "DEMUX_FAILED" -> "stream format error"
                "VIDEO_DECODER_UNAVAILABLE", "VIDEO_DECODER_FAILED", "VIDEO_RENDERER_FAILED" -> "decoder error"
                "SURFACE_LOST" -> "video surface lost"
                else -> "stream error"
            },
        )
    }
}

/** Pure: which preview slot to show. Spends no link and starts no stream by itself. */
object PreviewResolver {
    /** Dwell on the screen before a preview stream is opened. */
    const val DWELL_MS = 1_000L

    fun resolve(
        state: GameState?,
        watchingContentId: String?,
        watchingName: String?,
        candidates: List<WatchCandidate>,
        matching: Boolean,
        matchingFailed: Boolean,
        hasPlaylists: Boolean,
        previewFailure: PreviewFailure?,
    ): PreviewSlot {
        if (state != GameState.LIVE) return PreviewSlot.Hidden
        if (watchingContentId != null) return PreviewSlot.Watching(watchingName)
        if (!hasPlaylists) return PreviewSlot.NoMatch(failed = false)
        val best = candidates.minWithOrNull(compareBy<WatchCandidate> { it.confidence }.thenByDescending { it.score })
        if (best == null) {
            return when {
                matching -> PreviewSlot.Searching
                else -> PreviewSlot.NoMatch(failed = matchingFailed)
            }
        }
        if (!best.confirmed || best.singleUse) return PreviewSlot.PressOk(best.name, best.logo)
        return if (previewFailure != null) PreviewSlot.Unavailable(previewFailure) else PreviewSlot.Live(best.contentId, best.name)
    }
}

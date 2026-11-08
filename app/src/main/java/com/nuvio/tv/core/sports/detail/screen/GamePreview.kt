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
    /** The preview failed once: a quiet message, no retry loop. */
    data object Unavailable : PreviewSlot
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
        previewFailed: Boolean,
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
        return if (previewFailed) PreviewSlot.Unavailable else PreviewSlot.Live(best.contentId, best.name)
    }
}

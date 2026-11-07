package com.nuvio.tv.core.sports.detail.screen

enum class WatchMode { WATCHING, BROWSING }
enum class WatchStatus { FINDING, READY, NO_CHANNELS, FAILED, NO_PROVIDER, ALREADY_ON_IT }

/** A channel the existing sports matcher found. [confidence]: 0 = best tier. */
data class WatchCandidate(val contentId: String, val name: String, val confidence: Int, val score: Int)

sealed interface PrimaryAction {
    data class Play(val channel: WatchCandidate) : PrimaryAction
    data class Switch(val channel: WatchCandidate) : PrimaryAction
    data object AddProvider : PrimaryAction
    data object None : PrimaryAction
}

data class WatchUi(
    val mode: WatchMode,
    val currentChannelName: String?,
    val primary: PrimaryAction,
    val status: WatchStatus,
    /** Other matching channels beyond the primary one (reachable through "Channels"). */
    val alternatives: Int,
)

/** Pure: matcher output + entry state in, the Watch block out. Entry decides Play (browsing) vs Switch (watching). */
object WatchResolver {
    fun resolve(
        watchingContentId: String?,
        watchingName: String?,
        candidates: List<WatchCandidate>,
        matching: Boolean,
        matchingFailed: Boolean,
        hasPlaylists: Boolean,
    ): WatchUi {
        val mode = if (watchingContentId != null) WatchMode.WATCHING else WatchMode.BROWSING
        if (!hasPlaylists) return WatchUi(mode, watchingName, PrimaryAction.AddProvider, WatchStatus.NO_PROVIDER, 0)
        val best = candidates.minWithOrNull(compareBy<WatchCandidate> { it.confidence }.thenByDescending { it.score })
        val alternatives = (candidates.size - 1).coerceAtLeast(0)
        if (best == null) {
            val status = when {
                matching -> WatchStatus.FINDING
                matchingFailed -> WatchStatus.FAILED
                else -> WatchStatus.NO_CHANNELS
            }
            return WatchUi(mode, watchingName, PrimaryAction.None, status, 0)
        }
        if (watchingContentId != null) {
            if (candidates.any { it.contentId == watchingContentId }) {
                return WatchUi(mode, watchingName, PrimaryAction.None, WatchStatus.ALREADY_ON_IT, alternatives)
            }
            return WatchUi(mode, watchingName, PrimaryAction.Switch(best), WatchStatus.READY, alternatives)
        }
        return WatchUi(mode, null, PrimaryAction.Play(best), WatchStatus.READY, alternatives)
    }
}

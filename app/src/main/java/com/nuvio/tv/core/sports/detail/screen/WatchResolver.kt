package com.nuvio.tv.core.sports.detail.screen

enum class WatchMode { WATCHING, BROWSING }
enum class WatchStatus { FINDING, READY, NO_CHANNELS, FAILED, NO_PROVIDER, ALREADY_ON_IT, HIDDEN }

/** A channel the existing sports matcher found. [confidence]: 0 = best tier. */
data class WatchCandidate(
    val contentId: String,
    val name: String,
    val confidence: Int,
    val score: Int,
    val logo: String? = null,
    /** Link minted per play (Stalker / blank listed URL): never spent on a preview. */
    val singleUse: Boolean = false,
) {
    val confirmed: Boolean get() = confidence == 0
}

sealed interface PrimaryAction {
    data class Play(val channel: WatchCandidate) : PrimaryAction
    data class Switch(val channel: WatchCandidate) : PrimaryAction
    data object AddProvider : PrimaryAction
    /** A finished game: play it from kick-off on a channel whose archive has it. */
    data class Replay(val channel: WatchCandidate) : PrimaryAction
    /** Re-run the channel lookup: "Find channels" when nothing matched, "Retry" when the lookup failed or timed out. */
    data class Rematch(val failed: Boolean) : PrimaryAction
    data object None : PrimaryAction
}

data class WatchUi(
    val mode: WatchMode,
    val currentChannelName: String?,
    val primary: PrimaryAction,
    val status: WatchStatus,
    /** Other matching channels beyond the primary one (reachable through "Channels"). */
    val alternatives: Int,
) {
    /** "Channels" (the full match sheet) only when it can show something; otherwise it would be a dead end. */
    val showChannels: Boolean
        get() = status != WatchStatus.NO_CHANNELS && status != WatchStatus.FAILED && status != WatchStatus.NO_PROVIDER &&
            status != WatchStatus.HIDDEN && primary !is PrimaryAction.Replay

    /** The whole source row is hidden for a finished game with nothing to replay: never a failure on open. */
    val visible: Boolean get() = status != WatchStatus.HIDDEN
}

/** Pure: matcher output + entry state in, the Watch block out. Entry decides Play (browsing) vs Switch (watching). */
object WatchResolver {
    /** The channel lookup ends within this: a slow provider becomes "Couldn't check your channels" + Retry. */
    const val LOOKUP_DEADLINE_MS = 6_000L

    /** A finished game's replay lookup runs quietly in the background, so it may take longer (cold caches after install). */
    const val FINISHED_LOOKUP_DEADLINE_MS = 25_000L

    fun resolve(
        watchingContentId: String?,
        watchingName: String?,
        candidates: List<WatchCandidate>,
        matching: Boolean,
        matchingFailed: Boolean,
        hasPlaylists: Boolean,
        finished: Boolean = false,
        replayIds: Set<String> = emptySet(),
    ): WatchUi {
        if (finished) {
            // A finished game offers only what is useful: its replay when a channel archive has it, else no row at all.
            val withReplay = candidates.filter { it.contentId in replayIds }
            val best = withReplay.minWithOrNull(compareBy<WatchCandidate> { it.confidence }.thenByDescending { it.score })
            return if (best != null) WatchUi(WatchMode.BROWSING, null, PrimaryAction.Replay(best), WatchStatus.READY, withReplay.size - 1)
            else WatchUi(WatchMode.BROWSING, null, PrimaryAction.None, WatchStatus.HIDDEN, 0)
        }
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
            val action = when (status) {
                WatchStatus.NO_CHANNELS -> PrimaryAction.Rematch(failed = false)
                WatchStatus.FAILED -> PrimaryAction.Rematch(failed = true)
                else -> PrimaryAction.None
            }
            return WatchUi(mode, watchingName, action, status, 0)
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

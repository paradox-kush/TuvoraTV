package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.contracts.CatchUpReopen

/**
 * What the player does differently for a replay whose link is spent by being opened (a Stalker archive
 * `create_link`). Pure, so every decision tests without a player; the controller only acts on them.
 *
 * The rule behind all of them: such a URL can be requested ONCE. Anything that opens it a second time -
 * a frame-rate probe, a DoVi probe, parallel Range connections, a cached re-read, an engine failover, a
 * dialect walk, an automatic retry - spends the link before the viewer's player has used it. The only
 * recovery is a fresh mint, which the viewer's Retry asks for ([retryStep]).
 */
internal object SingleUseReplayPolicy {

    /** The words to end the replay with, or null when ordinary handling (walk, failover, recovery) runs. */
    fun endMessage(isCatchUp: Boolean, portMessage: String?): String? = portMessage.takeIf { isCatchUp }

    /** AFR preflight and the DoVi probe each open the URL themselves. */
    fun allowsOpeningProbes(singleUse: Boolean): Boolean = !singleUse

    /** Parallel Range / chunk-session sources open the URL on several connections. */
    fun allowsParallelRange(requested: Boolean, singleUse: Boolean): Boolean = requested && !singleUse

    /** The VOD disk cache re-reads ranges of the URL. */
    fun allowsVodCache(requested: Boolean, singleUse: Boolean): Boolean = requested && !singleUse

    /** A startup failover to the other engine re-initialises on the same URL. */
    fun allowsEngineFailover(singleUse: Boolean): Boolean = !singleUse

    enum class ErrorRoute { END_WITH_RETRY, DIALECT_WALK, PLAYER_RECOVERY }

    /** Where a playback error goes: a spent single-use link ends with Retry before any other rung. */
    fun routeError(singleUse: Boolean, adapterRoute: CatchUpReplayAdapter.ErrorRoute): ErrorRoute = when {
        singleUse -> ErrorRoute.END_WITH_RETRY
        adapterRoute == CatchUpReplayAdapter.ErrorRoute.DIALECT_WALK_FIRST -> ErrorRoute.DIALECT_WALK
        else -> ErrorRoute.PLAYER_RECOVERY
    }

    sealed interface RetryStep {
        data class Open(val url: String) : RetryStep
        data class End(val message: String) : RetryStep
    }

    /** What a fresh mint means for the player: open the new link, or end with the portal's words. */
    fun retryStep(reopened: CatchUpReopen?, noAnswer: String): RetryStep = when (reopened) {
        is CatchUpReopen.Link -> RetryStep.Open(reopened.url)
        is CatchUpReopen.Refused -> RetryStep.End(reopened.message)
        null -> RetryStep.End(noAnswer)
    }

    /**
     * Whether to tie the replay's mint (creds + programme id, needed for Retry) to this player now. A
     * single-use replay is held from player start, so leaving before the first playhead tick still lets go.
     */
    fun holdAtStart(singleUse: Boolean, alreadyHeld: Boolean): Boolean = singleUse && !alreadyHeld
}

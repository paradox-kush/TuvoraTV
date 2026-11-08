package com.nuvio.tv.playback.core

/**
 * When the session's rolling live-reconnect window ([PlaybackPolicy.LiveReconnectConfiguration]:
 * 6 attempts per 120 s) starts over.
 *
 * The window guards one stream against a reconnect storm, but the session outlives the stream: one
 * session per host, across every zap and Retry. Cleared only by 30 s of stable playback, the window
 * used to carry a dead channel's pressure into the next channel and into the viewer's Retry, so a
 * healthy stream went straight to LIVE_RECONNECT_EXHAUSTED on its first blip (B52: "recovers after
 * zapping and waiting 1-2 min").
 *
 * Resets only on a viewer decision: Retry, or a tune/zap to a different stream. Never on the
 * pipeline's own reconnects (they are not commands), and never on a link change: a re-minted link
 * is still the same stream, and its new URL must not refill the budget (playback skill 02 §2.1).
 * A tune/zap to the stream already playing keeps the pressure, so a caller that re-issues the same
 * channel cannot turn the window into an unbounded mint loop. Per-attempt delays are unchanged, so
 * a reset never makes the session mint faster than [PlaybackPolicy.liveReconnectDelayMs] allows.
 */
object LiveReconnectPressureResetPolicy {

    fun resetsOn(command: PlaybackCommand, current: PlaybackLaunch?): Boolean = when (command) {
        PlaybackCommand.Retry -> true
        is PlaybackCommand.Tune -> !sameStream(current, command.launch)
        is PlaybackCommand.Zap -> !sameStream(current, command.launch)
        else -> false
    }

    fun sameStream(current: PlaybackLaunch?, next: PlaybackLaunch): Boolean {
        val currentIdentity = current?.let(::streamIdentity) ?: return false
        return currentIdentity == streamIdentity(next)
    }

    /** Stream identity, never the minted URL of a provider link. */
    fun streamIdentity(launch: PlaybackLaunch): String = when (launch) {
        is PlaybackLaunch.DeferredProvider ->
            "provider|${launch.selection.accountId.value}|${launch.selection.contentKey.value}"
        // A concrete request is a fixed URL (it is never re-minted), so the URL is its identity
        // when no content key was supplied.
        is PlaybackLaunch.ConcreteRequest ->
            launch.request.contentKey?.let { "key|${it.value}" } ?: "url|${launch.request.url}"
    }
}

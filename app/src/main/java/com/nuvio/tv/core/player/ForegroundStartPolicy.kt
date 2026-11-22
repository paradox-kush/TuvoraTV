package com.nuvio.tv.core.player

/**
 * Decides when a foreground service may be started and stopped. TV twin of NuvioMobile's
 * `NowPlayingServicePolicy`, used by [ExternalPlaybackKeepAliveService]: "publish" means the
 * keep-alive is wanted, "hide" means stop() was called.
 *
 * Android's contract: once `startForegroundService()` is accepted, the service must call
 * `startForeground()` before it is stopped. Stopping it first (with `stopService` or `stopSelf`)
 * makes the system crash the app with `ForegroundServiceDidNotStartInTimeException`
 * (AOSP `ActiveServices.bringDownServiceLocked`, the `fgRequired` branch). Plain
 * `startService()` starts carry no such duty, but this policy treats every start the same way:
 * a start that `onStartCommand` has not answered yet is never stopped. A hide that arrives
 * in that window is deferred until the service reports it has been promoted.
 *
 * At most one start is in flight, so a stop issued here can never cut off a newer start.
 * Request ids tie the service's reports to the start they answer, so a late report from an
 * earlier service instance cannot move the state.
 *
 * Pure and not thread-safe: the caller runs every call on one thread.
 */
internal class ForegroundStartPolicy {
    sealed interface Phase {
        data object Idle : Phase

        /** A start was sent and `onStartCommand` has not reported back yet. */
        data class StartPending(val requestId: Long, val stopDeferred: Boolean = false) : Phase

        /** The service promoted itself for [requestId] and is in the foreground. */
        data class Running(val requestId: Long) : Phase
    }

    var phase: Phase = Phase.Idle
        private set

    private var nextRequestId = 1L

    /** Content wants to be shown. Returns the id of a start to send, or null when one is in flight or running. */
    fun onPublish(): Long? {
        return when (val current = phase) {
            Phase.Idle -> nextRequestId++.also { phase = Phase.StartPending(it) }
            is Phase.StartPending -> {
                if (current.stopDeferred) phase = current.copy(stopDeferred = false)
                null
            }
            is Phase.Running -> null
        }
    }

    /** The start for [requestId] could not be sent at all (both start calls threw). */
    fun onStartFailed(requestId: Long) {
        if ((phase as? Phase.StartPending)?.requestId == requestId) phase = Phase.Idle
    }

    /**
     * Content was withdrawn. Returns true when the service may be stopped now. While idle no
     * start is outstanding, so a stop is harmless (it only ends a service nobody tracks).
     */
    fun onHide(): Boolean {
        return when (val current = phase) {
            Phase.Idle -> true
            is Phase.StartPending -> {
                phase = current.copy(stopDeferred = true)
                false
            }
            is Phase.Running -> {
                phase = Phase.Idle
                true
            }
        }
    }

    /**
     * The service called `startForeground()` for [requestId]. Returns true when the service
     * should be stopped now: the content was withdrawn while the start was pending, or the
     * report answers no start we are tracking while nothing is in flight (an untracked
     * foreground service that would otherwise run forever). Stopping is safe here because the
     * service is already promoted and no other start is outstanding.
     */
    fun onPromoted(requestId: Long, stillWanted: Boolean): Boolean {
        val pending = when (val current = phase) {
            Phase.Idle -> return true
            is Phase.Running -> return false
            is Phase.StartPending -> current
        }
        if (pending.requestId != requestId) return false
        return if (stillWanted && !pending.stopDeferred) {
            phase = Phase.Running(requestId)
            false
        } else {
            phase = Phase.Idle
            true
        }
    }

    /**
     * `startForeground()` threw for [requestId] and the service stopped itself. The next
     * publish starts again; there is no automatic retry, which would loop while the app is
     * not allowed to run a foreground service.
     */
    fun onPromoteFailed(requestId: Long) {
        if ((phase as? Phase.StartPending)?.requestId == requestId) phase = Phase.Idle
    }

    /**
     * The service instance that last answered [lastRequestId] was destroyed. Returns true when
     * content is still wanted and a new start should be sent. A destroy from an earlier
     * instance (its request is not the current one) changes nothing.
     */
    fun onServiceDestroyed(lastRequestId: Long?, stillWanted: Boolean): Boolean {
        val currentId = when (val current = phase) {
            Phase.Idle -> return false
            is Phase.StartPending -> current.requestId
            is Phase.Running -> current.requestId
        }
        if (lastRequestId != currentId) return false
        phase = Phase.Idle
        return stillWanted
    }

    /** Test-only: forget any tracked start. */
    fun reset() {
        phase = Phase.Idle
    }
}

package com.nuvio.tv.core.analytics

/**
 * Start/stop pairing for [Breadcrumbs.playbackStarted] / [Breadcrumbs.playbackStopped].
 *
 * Those breadcrumbs went silent when the VOD player moved to upstream's player (no caller was
 * left), so the next-launch `app_exit` never knew the app died mid-playback and memory sampling
 * never ran. Every playback surface now reports through one [PlaybackBreadcrumbTracker], whose
 * pairing decision is this pure policy:
 *
 * - output appears with nothing active → START;
 * - output appears with the same identity active → nothing (a reconnect, a zap to the same kind
 *   of stream, a re-sent first frame: a re-start of the same viewing, never a second count);
 * - output appears with a different identity (engine handoff, preview promoted to fullscreen,
 *   zap to another container) → STOP then START, so every start still has exactly one stop;
 * - the player ended, failed, stopped or is released → STOP if something is active;
 * - anything in between (resolving, buffering, reconnecting) → nothing.
 *
 * Only code identities travel — never a URL, title or content id.
 */
data class PlaybackBreadcrumbIdentity(
    val kind: String,
    val engine: String,
    val surface: String,
    val container: String,
) {
    companion object {
        const val KIND_LIVE = "live"
        const val KIND_VOD = "vod"
        const val ENGINE_MEDIA3 = "media3"
        const val ENGINE_LIBMPV = "libmpv"
        const val ENGINE_UNKNOWN = "unknown"
        const val SURFACE_FULLSCREEN = "fullscreen"
        const val SURFACE_PREVIEW = "preview"
        const val CONTAINER_UNKNOWN = "unknown"
    }
}

/** What the player is doing, reduced to what pairing needs. */
enum class PlaybackBreadcrumbPhase {
    /** Output has actually started (first frame / rendering), not merely resolving. */
    OUTPUT,

    /** Resolving, buffering, reconnecting, handing off: neither a start nor a stop. */
    TRANSIENT,

    /** Stopped, failed, ended or releasing. */
    ENDED,
}

sealed interface PlaybackBreadcrumbDecision {
    data object None : PlaybackBreadcrumbDecision
    data class Start(val identity: PlaybackBreadcrumbIdentity) : PlaybackBreadcrumbDecision
    data object Stop : PlaybackBreadcrumbDecision
    data class Restart(val identity: PlaybackBreadcrumbIdentity) : PlaybackBreadcrumbDecision
}

object PlaybackBreadcrumbPolicy {
    fun decide(
        active: PlaybackBreadcrumbIdentity?,
        phase: PlaybackBreadcrumbPhase,
        identity: PlaybackBreadcrumbIdentity?,
    ): PlaybackBreadcrumbDecision = when (phase) {
        PlaybackBreadcrumbPhase.TRANSIENT -> PlaybackBreadcrumbDecision.None
        PlaybackBreadcrumbPhase.ENDED ->
            if (active != null) PlaybackBreadcrumbDecision.Stop else PlaybackBreadcrumbDecision.None
        PlaybackBreadcrumbPhase.OUTPUT -> when {
            // Output with no known identity (target already torn down) cannot start anything.
            identity == null -> PlaybackBreadcrumbDecision.None
            active == null -> PlaybackBreadcrumbDecision.Start(identity)
            active == identity -> PlaybackBreadcrumbDecision.None
            else -> PlaybackBreadcrumbDecision.Restart(identity)
        }
    }
}

/** Where paired breadcrumbs go. [owner] identifies the tracker so one surface can't stop another. */
interface PlaybackBreadcrumbSink {
    fun started(owner: Any, identity: PlaybackBreadcrumbIdentity, nowMs: Long)
    fun stopped(owner: Any)
}

/**
 * The process has one persisted "is playing" slot ([Breadcrumbs.CrashWriter]) and one Stalker
 * traffic flag, but more than one player can be alive for a moment (guide preview being detached
 * while the fullscreen player reaches its first frame). Only the most recent starter may clear
 * the slot; a late stop from a surface that was superseded is ignored.
 */
class PlaybackBreadcrumbArbiter(
    private val onStarted: (PlaybackBreadcrumbIdentity, Long) -> Unit,
    private val onStopped: () -> Unit,
) : PlaybackBreadcrumbSink {
    private var owner: Any? = null

    @Synchronized
    override fun started(owner: Any, identity: PlaybackBreadcrumbIdentity, nowMs: Long) {
        this.owner = owner
        onStarted(identity, nowMs)
    }

    @Synchronized
    override fun stopped(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        onStopped()
    }

    companion object {
        /** Process-wide sink in front of [Breadcrumbs]. */
        val Default: PlaybackBreadcrumbArbiter = PlaybackBreadcrumbArbiter(
            onStarted = { identity, nowMs ->
                Breadcrumbs.playbackStarted(
                    kind = identity.kind,
                    engine = identity.engine,
                    surface = identity.surface,
                    container = identity.container,
                    nowMs = nowMs,
                )
            },
            onStopped = { Breadcrumbs.playbackStopped() },
        )
    }
}

/**
 * One per player owner, confined to that owner's main-thread scope. [onPhase] feeds player state;
 * [stop] is the idempotent teardown every exit path (release, error, leave, rebind) calls.
 */
class PlaybackBreadcrumbTracker(
    private val sink: PlaybackBreadcrumbSink = PlaybackBreadcrumbArbiter.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var active: PlaybackBreadcrumbIdentity? = null
        private set

    fun onPhase(phase: PlaybackBreadcrumbPhase, identity: PlaybackBreadcrumbIdentity?) {
        when (val decision = PlaybackBreadcrumbPolicy.decide(active, phase, identity)) {
            PlaybackBreadcrumbDecision.None -> Unit
            PlaybackBreadcrumbDecision.Stop -> stop()
            is PlaybackBreadcrumbDecision.Start -> start(decision.identity)
            is PlaybackBreadcrumbDecision.Restart -> {
                stop()
                start(decision.identity)
            }
        }
    }

    /** Output has started for [identity]; same as [onPhase] with [PlaybackBreadcrumbPhase.OUTPUT]. */
    fun onOutputStarted(identity: PlaybackBreadcrumbIdentity) =
        onPhase(PlaybackBreadcrumbPhase.OUTPUT, identity)

    /** Idempotent: a stop with nothing active does nothing. */
    fun stop() {
        if (active == null) return
        active = null
        sink.stopped(this)
    }

    private fun start(identity: PlaybackBreadcrumbIdentity) {
        active = identity
        sink.started(this, identity, clock())
    }
}

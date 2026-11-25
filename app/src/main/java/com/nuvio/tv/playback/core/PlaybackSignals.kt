package com.nuvio.tv.playback.core

/** Which surface a playback view ran on, for the playback telemetry counts. */
enum class PlaybackSignalKind { LIVE, VOD, PREVIEW, MULTIVIEW_TILE }

/**
 * Port the clean pipeline reports playback facts through (first frame, engine hand-off, stall, teardown).
 * The core stays pure: the Android wiring maps these to the telemetry counts. Implementations must be
 * cheap and must never throw; the callers also guard them.
 */
interface PlaybackSignalSink {
    /** The first rendered frame (or, for audio-only, the first rendered audio) after [ms] from the play request. */
    fun firstFrame(kind: PlaybackSignalKind, engine: EngineType?, ms: Long) {}

    /** The one engine hand-off of an incident completed: [from] failed with [cause], [to] took over. */
    fun handoff(from: EngineType, to: EngineType, cause: FailureCode?) {}

    /** A buffering / reconnect stall of [ms] ended: [recovered] true when playback resumed, false when it failed. */
    fun stall(kind: PlaybackSignalKind, recovered: Boolean, ms: Long) {}

    /** A host released its player after [durationMs]; [failed] when the release threw or its barrier did not hold. */
    fun teardown(engine: EngineType, durationMs: Long, failed: Boolean) {}

    object None : PlaybackSignalSink
}

/**
 * Facts about the playback a host is running that the diagnostics of its session should carry but that no
 * session event knows (which kind of provider the channel came from). Written by the host when it accepts a
 * command, read by the diagnostics wrapper when an event is recorded. An enum or null: never an account or URL.
 */
class PlaybackDiagnosticContext {
    @Volatile
    var sourceType: ProviderSourceType? = null
}

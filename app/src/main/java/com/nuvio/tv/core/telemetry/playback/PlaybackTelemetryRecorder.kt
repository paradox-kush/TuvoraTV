package com.nuvio.tv.core.telemetry.playback

/**
 * Turns playback facts into the contract's signals. Routine successes (first frame, a clean teardown, a
 * multiview open) are only COUNTED into `session_health.counts`; nothing here sends an event except the
 * single `multiview_session` summary at close. The user's "Crash & usage reports" switch ([consent])
 * disables counting and the event entirely; the local QA mirror is independent of it (it never leaves the
 * device).
 *
 * Pure apart from the injected sinks, so it is unit tested without PostHog or Android.
 */
class PlaybackTelemetryRecorder(
    private val consent: () -> Boolean,
    /** Adds 1 to a `session_health` count key. */
    private val count: (String) -> Unit,
    /** Sends one event (production: `PostHog.capture`, which the gate then lets through). */
    private val sendEvent: (String, Map<String, Any>) -> Unit,
    private val qa: QaMirror = QaMirror.OFF,
    /** The newest memory sample, for the `multiview_session` context. */
    private val lastMemory: () -> MemorySample? = { null },
    nowMs: () -> Long = System::currentTimeMillis,
) {
    private val multiview = MultiviewSessionTracker(nowMs)

    /** True when the local QA mirror is on; lets the memory sampler run even with reports switched off. */
    val qaEnabled: Boolean get() = qa.enabled

    /** Play request to first rendered frame (or the app's existing first-frame / ready signal). */
    fun ttff(kind: PlayKind, engine: PlayEngine, ms: Long) {
        val clamped = ms.coerceAtLeast(0L)
        qa.emit("ttff", mapOf("kind" to kind.wire, "engine" to engine.wire, "ms" to clamped))
        if (!consent()) return
        count(PlaybackTelemetryKeys.ttff(kind, engine, PlaybackTelemetryBuckets.ttff(clamped)))
    }

    fun fallback(from: PlayEngine, to: PlayEngine, trigger: FallbackTrigger) {
        qa.emit("fallback", mapOf("from" to from.wire, "to" to to.wire, "trigger" to trigger.wire))
        if (!consent()) return
        count(PlaybackTelemetryKeys.fallback(from, to, trigger))
    }

    /** One stall ended: recovered, or the session gave up. [ms] is how long it lasted. */
    fun stall(kind: PlayKind, outcome: StallOutcome, ms: Long) {
        val clamped = ms.coerceAtLeast(0L)
        qa.emit("stall", mapOf("kind" to kind.wire, "outcome" to outcome.wire, "ms" to clamped))
        if (!consent()) return
        count(PlaybackTelemetryKeys.stall(kind, outcome))
        count(PlaybackTelemetryKeys.stallMs(kind, PlaybackTelemetryBuckets.stall(clamped)))
    }

    /** A player release took [durationMs]; [failed] when it threw or the barrier did not complete. */
    fun teardown(engine: PlayEngine, durationMs: Long, failed: Boolean) {
        val outcome = PlaybackTelemetryBuckets.teardownOutcome(durationMs, failed)
        qa.emit("teardown", mapOf("engine" to engine.wire, "outcome" to outcome.wire, "ms" to durationMs.coerceAtLeast(0L)))
        if (!consent()) return
        count(PlaybackTelemetryKeys.teardown(engine, outcome))
    }

    // ---- multiview ----

    fun multiviewOpened() {
        if (!multiview.open()) return
        qa.emit("multiview", mapOf("event" to MultiviewEvent.OPENED.wire))
        if (!consent()) return
        count(PlaybackTelemetryKeys.multiview(MultiviewEvent.OPENED))
    }

    /** The grid changed; every newly failed tile is counted as `multiview:tile_failed`. */
    fun multiviewTiles(tileCount: Int, playing: Int, failedTileIds: Set<Int>) {
        repeat(multiview.onTiles(tileCount, playing, failedTileIds)) {
            qa.emit("multiview", mapOf("event" to MultiviewEvent.TILE_FAILED.wire, "tiles" to tileCount))
            if (consent()) count(PlaybackTelemetryKeys.multiview(MultiviewEvent.TILE_FAILED))
        }
    }

    fun multiviewBackgrounded() = multiview.onBackgrounded()
    fun multiviewForegrounded() = multiview.onForegrounded()

    /** The system asked the app to trim memory (counts toward `end_reason = memory` while a session is open). */
    fun memoryTrim() = multiview.onMemoryTrim()

    /** Closes the session: counts `multiview:closed` and `multiview_tiles_max:<n>`, and sends `multiview_session`. */
    fun multiviewClosed() {
        val summary = multiview.close() ?: return
        val memory = lastMemory()
        val pssPeak = maxOf(multiview.pssPeakMb(), memory?.pssMb ?: 0)
        val properties = LinkedHashMap<String, Any>(summary.toProperties(pssPeak))
        memory?.let { properties.putAll(it.toProperties()) }
        qa.emit("multiview", mapOf("event" to MultiviewEvent.CLOSED.wire, "tiles_max" to summary.tilesMax))
        qa.emit("multiview_session", properties + ("duration_s" to summary.durationSeconds))
        if (!consent()) return
        count(PlaybackTelemetryKeys.multiview(MultiviewEvent.CLOSED))
        if (summary.tilesMax >= PlaybackTelemetryKeys.MULTIVIEW_TILES_MIN) {
            count(PlaybackTelemetryKeys.multiviewTilesMax(summary.tilesMax))
        }
        runCatching { sendEvent(MULTIVIEW_SESSION_EVENT, properties) }
    }

    /** A new memory sample landed: QA line, and the multiview PSS peak. */
    fun memorySampled(sample: MemorySample) {
        multiview.onMemorySample(sample.pssMb)
        if (qa.enabled) qa.emit("mem", sample.toProperties())
    }

    /** Extra properties on a failure diagnostic, mirrored for QA. */
    fun diagnosticExtras(extras: Map<String, Any>) {
        if (extras.isNotEmpty()) qa.emit("diag_extras", extras)
    }

    companion object {
        const val MULTIVIEW_SESSION_EVENT = "multiview_session"
    }
}

/**
 * Process-wide handle for code that has no injected recorder (the playback accumulator wiring, the VOD
 * controller, the memory sampler). Installed once by `TelemetryService.start`; every call is a no-op until
 * then and in tests.
 */
object PlaybackTelemetry {
    @Volatile
    var recorder: PlaybackTelemetryRecorder? = null

    fun ttff(kind: PlayKind, engine: PlayEngine, ms: Long) { recorder?.ttff(kind, engine, ms) }
    fun fallback(from: PlayEngine, to: PlayEngine, trigger: FallbackTrigger) { recorder?.fallback(from, to, trigger) }
    fun stall(kind: PlayKind, outcome: StallOutcome, ms: Long) { recorder?.stall(kind, outcome, ms) }
    fun teardown(engine: PlayEngine, durationMs: Long, failed: Boolean) { recorder?.teardown(engine, durationMs, failed) }
    fun diagnosticExtras(extras: Map<String, Any>) { recorder?.diagnosticExtras(extras) }
    fun multiviewOpened() { recorder?.multiviewOpened() }
    fun multiviewTiles(tileCount: Int, playing: Int, failedTileIds: Set<Int>) {
        recorder?.multiviewTiles(tileCount, playing, failedTileIds)
    }
    fun multiviewBackgrounded() { recorder?.multiviewBackgrounded() }
    fun multiviewForegrounded() { recorder?.multiviewForegrounded() }
    fun multiviewClosed() { recorder?.multiviewClosed() }
    fun memoryTrim() { recorder?.memoryTrim() }
}

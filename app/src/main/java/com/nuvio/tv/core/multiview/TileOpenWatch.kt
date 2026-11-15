package com.nuvio.tv.core.multiview

/** Where a tile's open stands, as the player layer sees it. */
internal enum class TilePhase { IDLE, WAITING, PLAYING, FAILED }

/** The one terminal outcome of one open (or one lost-and-recovered incident). Never a host, URL or message. */
internal sealed interface TileOutcome {
    val durationMs: Long

    data class Loaded(override val durationMs: Long) : TileOutcome
    data class Failed(val failure: TileFailure, override val durationMs: Long) : TileOutcome

    /** No progress for the stall window; the tile shows [TileFailure.NoData]. */
    data class TimedOut(override val durationMs: Long) : TileOutcome
}

/**
 * Bounded-load rule for one multiview tile (repo-root CLAUDE.md, "every loading state has a deadline
 * and a terminal outcome"): an open ends Playing or in a typed failure, and a wait that makes no
 * progress for [stallMs] ends as [TileFailure.NoData] even if the player never answers.
 *
 * Single-owner state (the Android main thread). Feed every playback change to [observe]; arm a timer for
 * [deadlineAtMs] and call [expire] when it passes. "Progress" is [stage] going up (a later open stage,
 * or more evidence of data, decoding or rendering): the same stage repeating, or a reconnect cycling
 * back, is not progress. A picture ends the incident; a later loss starts a new one.
 */
internal class TileOpenWatch(private val stallMs: Long) {
    init {
        require(stallMs > 0) { "Stall window must be positive" }
    }

    private var incidentStartedAtMs: Long? = null
    private var bestStage = Int.MIN_VALUE
    private var progressAtMs = 0L
    private var reported = false

    /** The wait passed its deadline: the screen shows it as failed until a picture or a retry. */
    var stalled: Boolean = false
        private set

    /** When the open wait must end, or null when nothing is being waited for. */
    val deadlineAtMs: Long?
        get() = incidentStartedAtMs?.takeIf { !reported }?.let { progressAtMs + stallMs }

    fun observe(phase: TilePhase, stage: Int, failure: TileFailure?, nowMs: Long): TileOutcome? = when (phase) {
        TilePhase.WAITING -> {
            if (incidentStartedAtMs == null) {
                incidentStartedAtMs = nowMs
                progressAtMs = nowMs
                bestStage = stage
                reported = false
            } else if (stage > bestStage) {
                bestStage = stage
                progressAtMs = nowMs
            }
            null
        }
        TilePhase.PLAYING -> {
            val started = incidentStartedAtMs
            val outcome = if (started != null && !reported) TileOutcome.Loaded((nowMs - started).coerceAtLeast(0L)) else null
            clearIncident()
            stalled = false
            outcome
        }
        TilePhase.FAILED -> {
            val started = incidentStartedAtMs ?: nowMs
            stalled = false
            if (reported) {
                null
            } else {
                reported = true
                TileOutcome.Failed(failure ?: TileFailure.Other, (nowMs - started).coerceAtLeast(0L))
            }
        }
        TilePhase.IDLE -> null
    }

    /** The deadline passed with no answer: one [TileOutcome.TimedOut], then nothing more until a new incident. */
    fun expire(nowMs: Long): TileOutcome? {
        val deadline = deadlineAtMs ?: return null
        if (nowMs < deadline) return null
        val started = incidentStartedAtMs ?: return null
        reported = true
        stalled = true
        return TileOutcome.TimedOut((nowMs - started).coerceAtLeast(0L))
    }

    /** A user retry opens a fresh incident. */
    fun reset() {
        clearIncident()
        stalled = false
    }

    private fun clearIncident() {
        incidentStartedAtMs = null
        bestStage = Int.MIN_VALUE
        reported = false
    }
}

/** Reporting and the stall window for tile opens. The Android side backs it with BoundedLoad (`multiview_tile`). */
internal interface TileLoadBounds {
    val stallMs: Long

    /**
     * Closed vocabulary only: [sourceType] is an enum name, [tileCount] a number. Never a host, URL,
     * account, channel name or engine message.
     */
    fun report(outcome: TileOutcome, sourceType: String, tileCount: Int)
}

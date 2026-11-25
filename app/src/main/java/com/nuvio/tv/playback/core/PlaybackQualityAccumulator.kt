package com.nuvio.tv.playback.core

/**
 * One playback-quality summary per host session, built from snapshot transitions only.
 *
 * Metric names follow CTA-2066 / Mux (views, startup time, exits before start, rebuffer count and
 * duration, watch time). Sent once at session end — never per second — and carries numbers and
 * enum names only: no URL, host, account, provider or channel. Time between two snapshots is
 * attributed to the earlier one, which is exact because the snapshot only changes on transitions.
 */
class PlaybackQualityAccumulator(
    private val now: () -> Long,
    /** Playback telemetry counts (first frame, hand-off, stall); default reports nothing. */
    private val signals: PlaybackSignalSink = PlaybackSignalSink.None,
    /** True for a multiview tile host: its views are `multiview_tile`, whatever the profile says. */
    private val isTile: Boolean = false,
) {
    private var views = 0
    private var viewsStarted = 0
    private val startupBuckets = IntArray(STARTUP_BUCKET_LIMITS_MS.size + 1)
    private var exitsBeforeStart = 0
    private var zapsUnder1s = 0
    private var startupFailures = 0
    private var playbackFailures = 0
    private var rebufferCount = 0
    private var rebufferMs = 0L
    private var watchMs = 0L
    private var freezes = 0
    private var reconnects = 0
    private var handoffs = 0
    private var displayRateSwitches = 0
    private var engineMsLibmpv = 0L
    private var engineMsMedia3 = 0L
    private val resolutionMs = LongArray(RESOLUTION_CLASSES)
    private val failureCodes = linkedMapOf<FailureCode, Int>()

    private var decodeHealth = DecodeHealthTotals.NONE

    private var previous: PlaybackSnapshot? = null
    private var previousAt = 0L
    private var viewGeneration: Long? = null
    private var viewStartedAt = 0L
    private var viewStarted = false
    private var viewFailed = false

    // Playback telemetry: the one hand-off of an incident (reported when the new graph is chosen) and the
    // buffering / reconnect stall in progress. Both are per view generation and dropped when it changes.
    private var pendingHandoffFrom: EngineType? = null
    private var pendingHandoffCause: FailureCode? = null
    private var pendingHandoffGraph: PlaybackGraph? = null
    private var stallStartedAt = -1L
    private var stallGeneration = -1L

    fun observe(snapshot: PlaybackSnapshot) {
        val at = now()
        attributeElapsed(at)
        val prior = previous
        if (snapshot.generation > 0 && snapshot.state != PlaybackState.IDLE &&
            snapshot.generation != viewGeneration
        ) {
            closeView(at)
            views += 1
            viewGeneration = snapshot.generation
            viewStartedAt = at
            viewStarted = false
            viewFailed = false
            pendingHandoffFrom = null
        }
        if (viewGeneration == snapshot.generation) {
            if (!viewStarted && snapshot.hasStarted()) {
                viewStarted = true
                viewsStarted += 1
                startupBuckets[startupBucket(at - viewStartedAt)] += 1
                // A local live-buffer playlist is the app's own file: not a source start.
                if (snapshot.requestSummary?.scheme != LOCAL_SCHEME) {
                    signal { signals.firstFrame(kindOf(snapshot), snapshot.graph?.engine, at - viewStartedAt) }
                }
            }
            if (viewStarted && snapshot.isBuffering && prior?.isBuffering != true) rebufferCount += 1
        }
        if (snapshot.isReconnecting && prior?.isReconnecting != true) reconnects += 1
        if (snapshot.state == PlaybackState.HANDING_OFF_ONCE && prior?.state != PlaybackState.HANDING_OFF_ONCE) {
            handoffs += 1
            pendingHandoffFrom = snapshot.graph?.engine ?: prior?.graph?.engine
            pendingHandoffCause = snapshot.failure?.code ?: prior?.failure?.code
            pendingHandoffGraph = snapshot.graph
        }
        observeHandoffTelemetry(snapshot)
        observeStallTelemetry(snapshot, at)
        if (snapshot.playbackOutputStatus == PlaybackOutputStatus.APPLIED &&
            prior?.playbackOutputStatus != PlaybackOutputStatus.APPLIED
        ) {
            displayRateSwitches += 1
        }
        val failure = snapshot.failure?.code
        if (failure != null && failure != prior?.failure?.code) {
            failureCodes[failure] = (failureCodes[failure] ?: 0) + 1
            if (failure == FailureCode.NO_PROGRESS) freezes += 1
        }
        if (snapshot.state == PlaybackState.FAILED && prior?.state != PlaybackState.FAILED) {
            if (viewStarted) playbackFailures += 1 else startupFailures += 1
            viewFailed = true
        }
        previous = snapshot
        previousAt = at
    }

    /** Reported once the hand-off has chosen its new graph; dropped when the incident ended without one. */
    private fun observeHandoffTelemetry(snapshot: PlaybackSnapshot) {
        val from = pendingHandoffFrom ?: return
        if (snapshot.state == PlaybackState.HANDING_OFF_ONCE) return
        val graph = snapshot.graph
        when {
            snapshot.state == PlaybackState.FAILED || snapshot.state == PlaybackState.STOPPED ||
                snapshot.state == PlaybackState.IDLE -> pendingHandoffFrom = null
            graph != null && graph != pendingHandoffGraph -> {
                val cause = pendingHandoffCause
                pendingHandoffFrom = null
                signal { signals.handoff(from, graph.engine, cause) }
            }
        }
    }

    /**
     * A stall is a buffering or reconnect stretch after the picture started that lasts at least
     * [MIN_STALL_MS]. It ends `recovered` when playback resumes and `gave_up` when the session fails; a stall
     * the viewer ends by leaving (new generation, stop, release) is nobody's outcome and is not reported.
     */
    private fun observeStallTelemetry(snapshot: PlaybackSnapshot, at: Long) {
        val inStall = viewStarted && viewGeneration == snapshot.generation &&
            (snapshot.isBuffering || snapshot.isReconnecting)
        if (inStall) {
            if (stallStartedAt < 0) {
                stallStartedAt = at
                stallGeneration = snapshot.generation
            }
            return
        }
        if (stallStartedAt < 0) return
        val duration = at - stallStartedAt
        val sameView = stallGeneration == snapshot.generation
        stallStartedAt = -1L
        if (!sameView || duration < MIN_STALL_MS) return
        val recovered = when (snapshot.state) {
            PlaybackState.PLAYING, PlaybackState.DEGRADED -> true
            PlaybackState.FAILED -> false
            else -> return
        }
        signal { signals.stall(kindOf(snapshot), recovered, duration) }
    }

    private fun kindOf(snapshot: PlaybackSnapshot): PlaybackSignalKind = when {
        isTile -> PlaybackSignalKind.MULTIVIEW_TILE
        snapshot.profile == SessionProfile.GUIDE -> PlaybackSignalKind.PREVIEW
        snapshot.requestSummary?.contentType == ContentType.LIVE -> PlaybackSignalKind.LIVE
        else -> PlaybackSignalKind.VOD
    }

    /** Telemetry must never break the pipeline. */
    private inline fun signal(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    /** Decode-health totals for the session so far (cumulative; the latest call wins). */
    fun observeDecodeHealth(totals: DecodeHealthTotals) {
        decodeHealth = totals
    }

    fun finish(): PlaybackQualitySummary {
        val at = now()
        attributeElapsed(at)
        closeView(at)
        previous = null
        pendingHandoffFrom = null
        stallStartedAt = -1L
        return PlaybackQualitySummary(
            views = views,
            viewsStarted = viewsStarted,
            startupBuckets = startupBuckets.toList(),
            exitsBeforeStart = exitsBeforeStart,
            zapsUnder1s = zapsUnder1s,
            startupFailures = startupFailures,
            playbackFailures = playbackFailures,
            rebufferCount = rebufferCount,
            rebufferMs = rebufferMs,
            watchMs = watchMs,
            freezes = freezes,
            reconnects = reconnects,
            handoffs = handoffs,
            displayRateSwitches = displayRateSwitches,
            engineMsLibmpv = engineMsLibmpv,
            engineMsMedia3 = engineMsMedia3,
            resolutionMs = resolutionMs.toList(),
            failureCodes = failureCodes.entries
                .sortedByDescending { it.value }
                .take(MAX_FAILURE_CODES)
                .joinToString(",") { "${it.key.name}:${it.value}" },
            decodeIssues = decodeHealth.issueCount,
            decodeIssueCodes = decodeHealth.issueCodes(),
            decodeFramesDropped = decodeHealth.framesDropped,
            decodeUnderruns = decodeHealth.underruns,
            decodeSinkErrors = decodeHealth.sinkErrors,
            bFramesYes = decodeHealth.bFramesYes,
            bFramesNo = decodeHealth.bFramesNo,
            bFramesUnknown = decodeHealth.bFramesUnknown,
        )
    }

    private fun attributeElapsed(at: Long) {
        val prior = previous ?: return
        val elapsed = (at - previousAt).coerceAtLeast(0)
        if (elapsed == 0L || !viewStarted || prior.generation != viewGeneration) return
        if (prior.isBuffering) rebufferMs += elapsed
        if (!prior.isPlaying) return
        watchMs += elapsed
        when (prior.graph?.engine) {
            EngineType.LIBMPV -> engineMsLibmpv += elapsed
            EngineType.MEDIA3 -> engineMsMedia3 += elapsed
            null -> Unit
        }
        prior.tracks.videoDimensions?.let { resolutionMs[resolutionClass(it)] += elapsed }
    }

    private fun closeView(at: Long) {
        if (viewGeneration == null || viewStarted || viewFailed) return
        if (at - viewStartedAt < QUICK_ZAP_MS) zapsUnder1s += 1 else exitsBeforeStart += 1
        viewFailed = true
    }

    private fun PlaybackSnapshot.hasStarted(): Boolean =
        progress.renderedVideoFrame || (!tracks.hasVideoTrack && progress.renderedAudio)

    private fun startupBucket(ms: Long): Int =
        STARTUP_BUCKET_LIMITS_MS.indexOfFirst { ms < it }.takeIf { it >= 0 } ?: STARTUP_BUCKET_LIMITS_MS.size

    private fun resolutionClass(dimensions: VideoDimensions): Int {
        val lines = minOf(dimensions.width, dimensions.height)
        return when {
            lines < 700 -> 0
            lines < 1000 -> 1
            lines <= 1200 -> 2
            else -> 3
        }
    }

    private companion object {
        val STARTUP_BUCKET_LIMITS_MS = longArrayOf(500, 1_000, 2_000, 4_000, 8_000)
        const val QUICK_ZAP_MS = 1_000L
        const val MIN_STALL_MS = 1_000L
        const val LOCAL_SCHEME = "file"
        const val RESOLUTION_CLASSES = 4
        const val MAX_FAILURE_CODES = 5
    }
}

data class PlaybackQualitySummary(
    val views: Int,
    val viewsStarted: Int,
    /** Counts per startup bucket: <0.5 s, <1 s, <2 s, <4 s, <8 s, >=8 s. */
    val startupBuckets: List<Int>,
    val exitsBeforeStart: Int,
    val zapsUnder1s: Int,
    val startupFailures: Int,
    val playbackFailures: Int,
    val rebufferCount: Int,
    val rebufferMs: Long,
    val watchMs: Long,
    val freezes: Int,
    val reconnects: Int,
    val handoffs: Int,
    val displayRateSwitches: Int,
    val engineMsLibmpv: Long,
    val engineMsMedia3: Long,
    /** Watch time by resolution class: SD, HD (720p), FHD (1080p), UHD. */
    val resolutionMs: List<Long>,
    /** Top failure codes as `NAME:count`, enum names only. */
    val failureCodes: String,
    /** Decode-health issues seen this session (before the per-session cap), with `NAME:count` codes. */
    val decodeIssues: Int = 0,
    val decodeIssueCodes: String = "",
    val decodeFramesDropped: Long = 0,
    val decodeUnderruns: Int = 0,
    val decodeSinkErrors: Int = 0,
    /** Video streams by frame type: B-frames yes / no / unknown. */
    val bFramesYes: Int = 0,
    val bFramesNo: Int = 0,
    val bFramesUnknown: Int = 0,
) {
    fun toProperties(): Map<String, Any> = linkedMapOf(
        "schema_version" to 1,
        "views" to views,
        "views_started" to viewsStarted,
        "startup_lt_500ms" to startupBuckets[0],
        "startup_lt_1s" to startupBuckets[1],
        "startup_lt_2s" to startupBuckets[2],
        "startup_lt_4s" to startupBuckets[3],
        "startup_lt_8s" to startupBuckets[4],
        "startup_ge_8s" to startupBuckets[5],
        "exits_before_start" to exitsBeforeStart,
        "zaps_under_1s" to zapsUnder1s,
        "startup_failures" to startupFailures,
        "playback_failures" to playbackFailures,
        "rebuffer_count" to rebufferCount,
        "rebuffer_ms" to rebufferMs,
        "watch_ms" to watchMs,
        "freeze_count" to freezes,
        "reconnects" to reconnects,
        "handoffs" to handoffs,
        "display_rate_switches" to displayRateSwitches,
        "engine_ms_libmpv" to engineMsLibmpv,
        "engine_ms_media3" to engineMsMedia3,
        "res_ms_sd" to resolutionMs[0],
        "res_ms_hd" to resolutionMs[1],
        "res_ms_fhd" to resolutionMs[2],
        "res_ms_uhd" to resolutionMs[3],
        "failure_codes" to failureCodes,
        "decode_issues" to decodeIssues,
        "decode_issue_codes" to decodeIssueCodes,
        "decode_frames_dropped" to decodeFramesDropped,
        "decode_underruns" to decodeUnderruns,
        "decode_sink_errors" to decodeSinkErrors,
        "b_frames_yes" to bFramesYes,
        "b_frames_no" to bFramesNo,
        "b_frames_unknown" to bFramesUnknown,
    )
}

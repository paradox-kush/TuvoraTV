package com.nuvio.tv.playback.core

/**
 * What kind of decode problem a live stream showed. Closed vocabulary: the names are the `issue`
 * property of the `DECODE_HEALTH_ISSUE` diagnostic (TIMESTAMP-SANITIZER-SPEC section 4).
 */
enum class DecodeIssue {
    AUDIO_TIMESTAMP_FAULT,
    VIDEO_TIMESTAMP_FAULT,
    AUDIO_NOT_PLAYING,
    VIDEO_FROZEN_AUDIO_PLAYING,
    AUDIO_DECODER_ERROR,
    VIDEO_DECODER_ERROR,
    VIDEO_DROP_BURST,
    AUDIO_UNDERRUN_BURST,
    DECODER_INIT_SLOW,

    /** Reserved for the future timestamp sanitizer. Defined so the vocabulary is stable; never emitted yet. */
    TIMESTAMP_REPAIRED,
}

/**
 * Why a timestamp issue was raised. [SINK_DISCONTINUITY] and [AV_OFFSET] are what the engines can
 * observe today; the rest are the sanitizer's fault classes, reserved for when it exists.
 */
enum class DecodeFault {
    SINK_DISCONTINUITY,
    AV_OFFSET,
    FOREIGN_BASE,
    STALLED_CLOCK,
    RUNAWAY_CLOCK,
    BACKWARD_JUMP,
    FORWARD_JUMP,
    PTS_WRAP,
}

/** Which screen the session plays on. */
enum class DecodeSurface(val wire: String) {
    FULLSCREEN("fullscreen"),
    GUIDE_PREVIEW("guide_preview"),
    MULTIVIEW_TILE("multiview_tile"),
}

/**
 * Engine facts the policy reads. Engines raise these as they happen; they carry numbers and enums
 * only (never a message, URL or host). Repeated identical faults are coalesced by the engine
 * adapter into one signal with a [count], so a flooding decoder cannot flood the events flow.
 */
sealed interface DecodeSignal {
    /** Media3 `AudioSink.UnexpectedDiscontinuityException`: the audio clock jumped under the sink. */
    data class AudioSinkDiscontinuity(val count: Int = 1) : DecodeSignal

    /** Any other audio sink init or write error. */
    data class AudioSinkError(val count: Int = 1) : DecodeSignal

    /** Media3 `onAudioCodecError`, or an mpv `ad` / `ao` warning or worse. */
    data class AudioDecoderError(val count: Int = 1) : DecodeSignal

    /** Media3 `onVideoCodecError`, or an mpv `vd` warning or worse. */
    data class VideoDecoderError(val count: Int = 1) : DecodeSignal

    data class AudioUnderrun(val count: Int = 1) : DecodeSignal

    /** Media3 `onDroppedVideoFrames`. */
    data class DroppedFrames(val frames: Int) : DecodeSignal

    data class DecoderInitialized(val video: Boolean, val durationMs: Long) : DecodeSignal

    /** mpv `avsync`: the audio-minus-video difference, in seconds. */
    data class AvSyncOffset(val seconds: Double) : DecodeSignal

    /** A timestamp fault found on one elementary stream by the sanitizer (reserved; nothing raises it yet). */
    data class TimestampFault(val video: Boolean, val fault: DecodeFault) : DecodeSignal

    /** Format facts the engine learned; null fields mean "no change". Never drives an issue. */
    data class FormatFacts(
        val audioCodec: String? = null,
        val audioChannels: Int? = null,
        val audioSampleRate: Int? = null,
        val hdr: StreamHdrCode? = null,
        /** Video reorder depth (0-16); 0 means no B-frames. Null when the engine cannot say. */
        val reorderDepth: Int? = null,
    ) : DecodeSignal
}

/** The stream's shape as the engine and evidence know it. Every field is bucketed or a closed code. */
data class DecodeStreamShape(
    val container: StreamContainerCode? = null,
    val videoCodec: StreamCodecCode? = null,
    val videoHeight: StreamHeightCode? = null,
    val videoFps: Float? = null,
    val hdr: StreamHdrCode? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    /** Component name as the engine reported it; sanitised to an identifier at the formatter. */
    val decoder: String? = null,
    val decoderMode: DecoderMode? = null,
    /** True for a stream with a video track: only then are the frame-type fields reported. */
    val hasVideo: Boolean = false,
    val reorderDepth: Int? = null,
) {
    /** `yes` (reorder depth above 0), `no` (0) or `unknown`; null for a stream without video. */
    fun bFrames(): String? = if (hasVideo) bFramesOf(reorderDepth) else null

    val isEmpty: Boolean
        get() = container == null && videoCodec == null && videoHeight == null && videoFps == null &&
            hdr == null && audioCodec == null && audioChannels == null && audioSampleRate == null

    /** `le25`, `30`, `50`, `gt50`: identifier-safe so the on-device trail keeps it. */
    fun fpsBucket(): String? = videoFps?.takeIf { it > 0f }?.let {
        when {
            it <= 25.5f -> "le25"
            it <= 30.5f -> "30"
            it <= 50.5f -> "50"
            else -> "gt50"
        }
    }

    companion object {
        fun bFramesOf(reorderDepth: Int?): String = when {
            reorderDepth == null || reorderDepth !in 0..MAX_REORDER_DEPTH -> "unknown"
            reorderDepth > 0 -> "yes"
            else -> "no"
        }

        const val MAX_REORDER_DEPTH = 16

        /** Short identifier for an audio codec/mime string: `audio/eac3` -> `eac3`. Never keeps free text. */
        fun audioCodecWire(raw: String?): String? {
            val cleaned = raw?.lowercase()
                ?.removePrefix("audio/")
                ?.removePrefix("x-")
                ?.replace(Regex("[^a-z0-9_-]+"), "_")
                ?.trim('_')
                ?.take(16)
            return cleaned?.takeIf { it.isNotEmpty() }
        }

        fun videoCodecCode(mime: String?): StreamCodecCode? = when (mime?.lowercase()) {
            null -> null
            "video/avc" -> StreamCodecCode.H264
            "video/hevc" -> StreamCodecCode.HEVC
            "video/av01" -> StreamCodecCode.AV1
            "video/x-vnd.on2.vp9" -> StreamCodecCode.VP9
            "video/mpeg2" -> StreamCodecCode.MPEG2
            else -> StreamCodecCode.OTHER
        }
    }
}

/**
 * One finding, ready for the diagnostics adapter. Counts are per tune so far. [surface] and
 * [tileCount] are filled by the session, which knows which screen it plays on.
 */
data class DecodeHealthReport(
    val issue: DecodeIssue,
    val fault: DecodeFault? = null,
    val shape: DecodeStreamShape = DecodeStreamShape(),
    /** `<1s`, `1-10s`, `10-60s`, `>60s`: only for an A/V offset finding. */
    val avOffsetBucket: String? = null,
    /** Whole seconds the condition was observed for when the threshold was met. */
    val secondsInBucket: Int? = null,
    val framesDropped: Long = 0,
    val underruns: Int = 0,
    val sinkErrors: Int = 0,
    val surface: DecodeSurface? = null,
    val tileCount: Int? = null,
)

/** Per-session totals for the `clean_playback_session` summary; observed even when the cap held an event back. */
data class DecodeHealthTotals(
    val framesDropped: Long = 0,
    val underruns: Int = 0,
    val sinkErrors: Int = 0,
    val issues: Map<DecodeIssue, Int> = emptyMap(),
    /** Video streams seen this session by frame type (one per tune). */
    val bFramesYes: Int = 0,
    val bFramesNo: Int = 0,
    val bFramesUnknown: Int = 0,
) {
    val issueCount: Int get() = issues.values.sum()

    /** `NAME:count`, most frequent first, enum names only. */
    fun issueCodes(): String = issues.entries
        .sortedByDescending { it.value }
        .joinToString(",") { "${it.key.name}:${it.value}" }

    companion object {
        val NONE = DecodeHealthTotals()
    }
}

/** What one runtime-window tick measured. Null counters mean the engine cannot say. */
data class DecodeWindow(
    val windowMs: Long,
    /** Session PLAYING and not paused. Anything else resets the flat-time counters. */
    val playing: Boolean,
    val hasAudioTrack: Boolean,
    /** Only the audible tile (or a fullscreen / preview session with audio on) may report AUDIO_NOT_PLAYING. */
    val audible: Boolean,
    val videoRenderedDelta: Long?,
    /** Dropped plus skipped video frames in the window. */
    val videoLostDelta: Long?,
    val audioProgressDelta: Long?,
)

/** Caps: how many `DECODE_HEALTH_ISSUE` events one budget owner may emit. */
class DecodeHealthBudget(private val max: Int = MAX_PER_SESSION) {
    private var used = 0

    @Synchronized
    fun tryAcquire(): Boolean {
        if (used >= max) return false
        used += 1
        return true
    }

    @Synchronized
    fun used(): Int = used

    companion object {
        /** At most this many per playback session, and per multiview screen session in total. */
        const val MAX_PER_SESSION = 5
    }
}

/**
 * Turns engine facts into at most one new [DecodeHealthReport] per (issue, tune). Pure: no clock, no
 * I/O, never a timer. The caller feeds it events as they happen ([onSignal]) and at the existing
 * runtime-window ticks ([onWindow]).
 *
 * A tune is one generation. Reconnects inside it keep their dedupe set, so a stream that keeps
 * failing the same way is reported once. In multiview ([dedupePerSession]) a tile keeps its dedupe
 * for its whole life and shares a [DecodeHealthBudget] with the other tiles of the screen, so four
 * tiles on a bad provider cannot multiply events.
 */
class DecodeHealthPolicy(
    private val budget: DecodeHealthBudget = DecodeHealthBudget(),
    private val dedupePerSession: Boolean = false,
) {
    private val reported = mutableSetOf<DecodeIssue>()
    private val underrunTimes = ArrayDeque<Long>()

    private var burstFrames = 0L
    private var lastDropAtMs = Long.MIN_VALUE
    private var audioFlatMs = 0L
    private var videoFlatMs = 0L

    // Per tune: the numbers a report carries.
    private var tuneFramesDropped = 0L
    private var tuneUnderruns = 0
    private var tuneSinkErrors = 0

    // Per session: the numbers the summary carries.
    private var totalFramesDropped = 0L
    private var totalUnderruns = 0
    private var totalSinkErrors = 0
    private val issueTotals = linkedMapOf<DecodeIssue, Int>()

    private var tuneHasVideo = false
    private var tuneReorderDepth: Int? = null
    private var framesYes = 0
    private var framesNo = 0
    private var framesUnknown = 0

    /** What this tune's video stream looks like; the latest call wins and counts once when the tune ends. */
    @Synchronized
    fun observeFrameType(hasVideo: Boolean, reorderDepth: Int?) {
        tuneHasVideo = hasVideo
        tuneReorderDepth = reorderDepth
    }

    private fun frameCounts(includeCurrent: Boolean): IntArray {
        val out = intArrayOf(framesYes, framesNo, framesUnknown)
        if (includeCurrent && tuneHasVideo) {
            when (DecodeStreamShape.bFramesOf(tuneReorderDepth)) {
                "yes" -> out[0]++
                "no" -> out[1]++
                else -> out[2]++
            }
        }
        return out
    }

    /** A new channel: counters and the once-per-tune set start over. */
    @Synchronized
    fun startTune() {
        frameCounts(true).let { framesYes = it[0]; framesNo = it[1]; framesUnknown = it[2] }
        tuneHasVideo = false
        tuneReorderDepth = null
        if (!dedupePerSession) reported.clear()
        resetAttempt()
        tuneFramesDropped = 0
        tuneUnderruns = 0
        tuneSinkErrors = 0
    }

    /** A reconnect or hand-off inside the same tune: window state starts over, dedupe stays. */
    @Synchronized
    fun startAttempt() = resetAttempt()

    private fun resetAttempt() {
        underrunTimes.clear()
        burstFrames = 0
        lastDropAtMs = Long.MIN_VALUE
        audioFlatMs = 0
        videoFlatMs = 0
    }

    /** Frames the engine lost that no [DecodeSignal.DroppedFrames] reported (libmpv counters). */
    @Synchronized
    fun addDroppedFrames(frames: Long) {
        if (frames <= 0) return
        tuneFramesDropped += frames
        totalFramesDropped += frames
    }

    @Synchronized
    fun totals(): DecodeHealthTotals {
        val frames = frameCounts(true)
        return DecodeHealthTotals(
            framesDropped = totalFramesDropped,
            underruns = totalUnderruns,
            sinkErrors = totalSinkErrors,
            issues = issueTotals.toMap(),
            bFramesYes = frames[0],
            bFramesNo = frames[1],
            bFramesUnknown = frames[2],
        )
    }

    @Synchronized
    fun onSignal(
        signal: DecodeSignal,
        nowMs: Long,
        shape: DecodeStreamShape = DecodeStreamShape(),
    ): DecodeHealthReport? = when (signal) {
        is DecodeSignal.FormatFacts -> null
        is DecodeSignal.AudioSinkDiscontinuity -> {
            countSinkErrors(signal.count)
            finding(DecodeIssue.AUDIO_TIMESTAMP_FAULT, shape, fault = DecodeFault.SINK_DISCONTINUITY)
        }
        is DecodeSignal.AudioSinkError -> {
            countSinkErrors(signal.count)
            finding(DecodeIssue.AUDIO_DECODER_ERROR, shape)
        }
        is DecodeSignal.AudioDecoderError -> finding(DecodeIssue.AUDIO_DECODER_ERROR, shape)
        is DecodeSignal.VideoDecoderError -> finding(DecodeIssue.VIDEO_DECODER_ERROR, shape)
        is DecodeSignal.AudioUnderrun -> underrun(signal.count, nowMs, shape)
        is DecodeSignal.DroppedFrames -> dropped(signal.frames, nowMs, shape)
        is DecodeSignal.DecoderInitialized ->
            if (signal.durationMs > INIT_SLOW_MS) {
                finding(DecodeIssue.DECODER_INIT_SLOW, shape, seconds = (signal.durationMs / 1000).toInt())
            } else {
                null
            }
        is DecodeSignal.AvSyncOffset -> {
            val offset = kotlin.math.abs(signal.seconds)
            if (offset.isFinite() && offset > AV_OFFSET_FAULT_S) {
                finding(
                    DecodeIssue.AUDIO_TIMESTAMP_FAULT,
                    shape,
                    fault = DecodeFault.AV_OFFSET,
                    avOffsetBucket = avOffsetBucket(offset),
                )
            } else {
                null
            }
        }
        is DecodeSignal.TimestampFault -> finding(
            if (signal.video) DecodeIssue.VIDEO_TIMESTAMP_FAULT else DecodeIssue.AUDIO_TIMESTAMP_FAULT,
            shape,
            fault = signal.fault,
        )
    }

    /** One runtime-window tick. May return up to three findings (drop burst, silent audio, frozen picture). */
    @Synchronized
    fun onWindow(window: DecodeWindow, shape: DecodeStreamShape = DecodeStreamShape()): List<DecodeHealthReport> {
        if (!window.playing || window.windowMs <= 0) {
            audioFlatMs = 0
            videoFlatMs = 0
            return emptyList()
        }
        val out = mutableListOf<DecodeHealthReport>()
        val seconds = (window.windowMs / 1000).toInt()

        val rendered = window.videoRenderedDelta
        val lost = window.videoLostDelta
        if (window.windowMs >= DROP_WINDOW_MS && rendered != null && lost != null) {
            val total = rendered + lost
            if (total >= DROP_MIN_FRAMES && lost * 100 >= DROP_RATIO_PERCENT * total) {
                finding(DecodeIssue.VIDEO_DROP_BURST, shape, seconds = seconds)?.let(out::add)
            }
        }

        val audio = window.audioProgressDelta
        // A muted tile's audio is off by design: its silence is not a fault, and it must not
        // accumulate silent time that would fire the moment it turns audible.
        audioFlatMs = if (window.hasAudioTrack && window.audible && audio != null && audio == 0L) {
            audioFlatMs + window.windowMs
        } else {
            0
        }
        if (audioFlatMs >= AUDIO_NOT_PLAYING_MS) {
            finding(DecodeIssue.AUDIO_NOT_PLAYING, shape, seconds = (audioFlatMs / 1000).toInt())?.let(out::add)
        }

        videoFlatMs = if (rendered != null && rendered == 0L && audio != null && audio > 0L) {
            videoFlatMs + window.windowMs
        } else {
            0
        }
        if (videoFlatMs >= VIDEO_FROZEN_MS) {
            finding(DecodeIssue.VIDEO_FROZEN_AUDIO_PLAYING, shape, seconds = (videoFlatMs / 1000).toInt())?.let(out::add)
        }
        return out
    }

    private fun countSinkErrors(count: Int) {
        val n = count.coerceAtLeast(1)
        tuneSinkErrors += n
        totalSinkErrors += n
    }

    private fun underrun(count: Int, nowMs: Long, shape: DecodeStreamShape): DecodeHealthReport? {
        val n = count.coerceAtLeast(1)
        tuneUnderruns += n
        totalUnderruns += n
        repeat(n.coerceAtMost(UNDERRUN_BURST_COUNT)) { underrunTimes.addLast(nowMs) }
        while (underrunTimes.isNotEmpty() && nowMs - underrunTimes.first() > UNDERRUN_WINDOW_MS) underrunTimes.removeFirst()
        return if (underrunTimes.size >= UNDERRUN_BURST_COUNT) {
            finding(DecodeIssue.AUDIO_UNDERRUN_BURST, shape, seconds = (UNDERRUN_WINDOW_MS / 1000).toInt())
        } else {
            null
        }
    }

    private fun dropped(frames: Int, nowMs: Long, shape: DecodeStreamShape): DecodeHealthReport? {
        if (frames <= 0) return null
        tuneFramesDropped += frames
        totalFramesDropped += frames
        // Media3 reports drops in batches of at most ~50 frames: batches close together are one burst.
        burstFrames = if (lastDropAtMs != Long.MIN_VALUE && nowMs - lastDropAtMs <= BURST_GAP_MS) {
            burstFrames + frames
        } else {
            frames.toLong()
        }
        lastDropAtMs = nowMs
        val fps = shape.videoFps?.takeIf { it > 0f } ?: DEFAULT_FPS
        val threshold = kotlin.math.ceil(fps * DROP_BURST_SECONDS_OF_FRAMES).toLong()
        return if (burstFrames >= threshold) {
            finding(DecodeIssue.VIDEO_DROP_BURST, shape, seconds = DROP_BURST_SECONDS_OF_FRAMES)
        } else {
            null
        }
    }

    private fun finding(
        issue: DecodeIssue,
        shape: DecodeStreamShape,
        fault: DecodeFault? = null,
        avOffsetBucket: String? = null,
        seconds: Int? = null,
    ): DecodeHealthReport? {
        if (!reported.add(issue)) return null
        issueTotals[issue] = (issueTotals[issue] ?: 0) + 1
        // Spent before the cap: a capped issue is counted in the summary but never retried.
        if (!budget.tryAcquire()) return null
        return DecodeHealthReport(
            issue = issue,
            fault = fault,
            shape = shape,
            avOffsetBucket = avOffsetBucket,
            secondsInBucket = seconds,
            framesDropped = tuneFramesDropped,
            underruns = tuneUnderruns,
            sinkErrors = tuneSinkErrors,
        )
    }

    companion object {
        /** An A/V difference past this is a timestamp fault, not drift. */
        const val AV_OFFSET_FAULT_S = 5.0
        const val AUDIO_NOT_PLAYING_MS = 8_000L
        const val VIDEO_FROZEN_MS = 5_000L
        const val DROP_WINDOW_MS = 5_000L
        const val DROP_RATIO_PERCENT = 25L

        /** A ratio over too few frames is noise (a paused-then-resumed window). */
        const val DROP_MIN_FRAMES = 25L
        const val DROP_BURST_SECONDS_OF_FRAMES = 2
        const val BURST_GAP_MS = 1_000L
        const val DEFAULT_FPS = 30f
        const val UNDERRUN_BURST_COUNT = 3
        const val UNDERRUN_WINDOW_MS = 10_000L
        const val INIT_SLOW_MS = 3_000L

        fun avOffsetBucket(seconds: Double): String = when {
            seconds < 1.0 -> "<1s"
            seconds < 10.0 -> "1-10s"
            seconds < 60.0 -> "10-60s"
            else -> ">60s"
        }
    }
}

/**
 * How a session takes part in decode-health reporting. A tile shares one [budget] with the other
 * tiles of its multiview screen; every other session owns its own.
 */
class DecodeHealthContext(
    val budget: DecodeHealthBudget = DecodeHealthBudget(),
    val tile: Boolean = false,
    val tileCount: () -> Int = { 0 },
    /** A tile starts muted; the multiview coordinator turns audio on for the one audible tile. */
    val startsAudible: Boolean = true,
) {
    companion object {
        fun forTile(tile: TileHostConfig?): DecodeHealthContext {
            if (tile == null) return DecodeHealthContext()
            val link = tile.decodeHealth
            return DecodeHealthContext(
                budget = link?.budget ?: DecodeHealthBudget(),
                tile = true,
                tileCount = link?.tileCount ?: { 0 },
                startsAudible = false,
            )
        }
    }
}

/** What a multiview screen hands every tile it builds. */
class DecodeHealthTileLink(
    val budget: DecodeHealthBudget = DecodeHealthBudget(),
    val tileCount: () -> Int = { 0 },
)

/**
 * Coalesces a repeating fault into at most one signal per interval, carrying the count since the
 * last one. Engines call it from their decoder threads; a flooding decoder then cannot flood the
 * events flow (a full flow drops the oldest-needed events such as the first frame).
 */
class DecodeSignalCoalescer(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var pending = 0
    private var lastEmitAtMs = Long.MIN_VALUE

    /** Returns the count to emit now, or null when this occurrence is held back. */
    @Synchronized
    fun offer(nowMs: Long): Int? {
        pending += 1
        if (lastEmitAtMs != Long.MIN_VALUE && nowMs - lastEmitAtMs < intervalMs) return null
        val out = pending
        pending = 0
        lastEmitAtMs = nowMs
        return out
    }

    @Synchronized
    fun reset() {
        pending = 0
        lastEmitAtMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
    }
}

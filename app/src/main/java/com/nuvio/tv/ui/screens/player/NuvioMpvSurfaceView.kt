package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.SubtitleStyleSettings
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.pow
import kotlin.math.roundToLong

/** libmpv mpv_event_id: MPV_EVENT_END_FILE (stable ABI value). */
private const val MPV_EVENT_END_FILE = 7

class NuvioMpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {

    private var initialized = false
    private var hasQueuedInitialMedia = false
    private var lastMediaRequestKey: String? = null
    private var pendingInitialMediaUrl: String? = null
    private var pendingInitialStartOption: String? = null

    /**
     * Invoked (on mpv's event thread — hop before touching UI/player state) when a file unloads
     * because of an ERROR: libmpv end-file with reason "error"; [fileError] is mpv's error string
     * (e.g. "loading failed"). Deliberately silent for eof/stop/quit/redirect unloads, so channel
     * switches ("loadfile replace" → reason "stop") and natural EOF never fire it.
     */
    @Volatile var onPlaybackEndedWithError: ((fileError: String?) -> Unit)? = null

    // All mpv control calls (property writes, loadfile, seeks, teardown) run here,
    // serialized in submission order. mpv_set_property/mpv_command take the same core
    // lock as reads: on a wedged live demuxer a lifecycle setPaused or a seek on the
    // main thread blocks >5s → ANR (reproduced on mobile; same call shape here). Reads
    // are lock-free via the property shadow; writes queue onto this thread.
    private val mpvCtl = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-ctl")
    }
    @Volatile private var pendingDestroy: Future<*>? = null

    private fun ctl(block: () -> Unit) {
        runCatching { mpvCtl.execute { runCatching(block) } }
    }
    private var hardwareDecodeMode: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE
    private var currentAspectMode: AspectMode = AspectMode.ORIGINAL
    private var pendingAspectRetryCount = 0
    private val aspectReapplyRunnable = Runnable {
        applyAspectModeInternal(currentAspectMode, allowRetry = true)
    }

    // Shadow of the observed playback properties, updated from mpv's event thread.
    // The readers below (isPlayingNow, currentPositionMs, readTrackSnapshot, …) return
    // these instead of calling mpv_get_property: a synchronous read takes the mpv core
    // lock, which stalls for seconds while a live demuxer is busy or tearing down — on
    // the main thread (500ms progress poll, seek gate, style/aspect application) that's
    // an ANR (Play vitals: getPropertyBoolean → pthread_cond_wait).
    @Volatile private var obsPaused = true
    @Volatile private var obsPausedForCache = false
    @Volatile private var obsCoreIdle = false
    @Volatile private var obsTimePosMs = 0L
    @Volatile private var obsDurationMs = 0L
    @Volatile private var obsVid: String? = null
    @Volatile private var obsAid: String? = null
    @Volatile private var obsSid: String? = null
    @Volatile private var obsTrackList: MPVNode? = null
    @Volatile private var obsVideoOutParams: MPVNode? = null
    @Volatile private var obsVideoParams: MPVNode? = null
    // Rolling bitrate estimates. Live MPEG-TS almost never declares a bitrate in the
    // container, so the demuxer's per-track `demux-bitrate` is usually absent — mpv's
    // running estimate is the only number the stream info panel can show for live.
    @Volatile private var obsVideoBitrate: Double? = null
    @Volatile private var obsAudioBitrate: Double? = null

    private fun resetPropertyShadow() {
        obsPaused = true
        obsPausedForCache = false
        obsCoreIdle = false
        obsTimePosMs = 0L
        obsDurationMs = 0L
        obsVid = null
        obsAid = null
        obsSid = null
        obsTrackList = null
        obsVideoOutParams = null
        obsVideoParams = null
        obsVideoBitrate = null
        obsAudioBitrate = null
    }

    private val propertyShadow = object : MPV.EventObserver {
        override fun eventProperty(property: String) {
            // MPV_FORMAT_NONE: property became unavailable — fall back to the same
            // defaults a failed synchronous read used to produce.
            when (property) {
                "pause" -> obsPaused = true
                "paused-for-cache" -> obsPausedForCache = false
                "core-idle" -> obsCoreIdle = false
                "time-pos" -> obsTimePosMs = 0L
                "duration" -> obsDurationMs = 0L
                "vid" -> obsVid = null
                "aid" -> obsAid = null
                "sid" -> obsSid = null
                "track-list" -> obsTrackList = null
                "video-out-params" -> obsVideoOutParams = null
                "video-params" -> obsVideoParams = null
                "video-bitrate" -> obsVideoBitrate = null
                "audio-bitrate" -> obsAudioBitrate = null
            }
        }

        override fun eventProperty(property: String, value: Long) = Unit

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> obsPaused = value
                "paused-for-cache" -> obsPausedForCache = value
                "core-idle" -> obsCoreIdle = value
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> obsTimePosMs = (value * 1000.0).roundToLong().coerceAtLeast(0L)
                "duration" -> obsDurationMs = (value * 1000.0).roundToLong().coerceAtLeast(0L)
                "video-bitrate" -> obsVideoBitrate = value.takeIf { it > 0.0 }
                "audio-bitrate" -> obsAudioBitrate = value.takeIf { it > 0.0 }
            }
        }

        override fun eventProperty(property: String, value: String) {
            when (property) {
                "vid" -> obsVid = value
                "aid" -> obsAid = value
                "sid" -> obsSid = value
            }
        }

        override fun eventProperty(property: String, value: MPVNode) {
            when (property) {
                "track-list" -> obsTrackList = value
                "video-out-params" -> obsVideoOutParams = value
                "video-params" -> obsVideoParams = value
            }
        }

        override fun event(eventId: Int, data: MPVNode) {
            if (eventId != MPV_EVENT_END_FILE) return
            // mpv_event_to_node shape: {"reason": "eof"|"stop"|"quit"|"error"|"redirect",
            //  "file_error": "<mpv error string>" (only when reason == "error")}.
            val map = runCatching { data.asMap() }.getOrNull() ?: return
            if (runCatching { map["reason"]?.asString() }.getOrNull() != "error") return
            val fileError = runCatching { map["file_error"]?.asString() }.getOrNull()
            onPlaybackEndedWithError?.invoke(fileError)
        }
    }

    fun ensureInitialized() {
        if (initialized) return
        // A queued teardown from the previous session (releasePlayer) must finish before
        // re-creating the core on the same MPV instance. Only blocks when re-init races
        // an in-flight destroy — the old code blocked main on every destroy instead.
        pendingDestroy?.let { destroyJob ->
            runCatching { destroyJob.get() }
            pendingDestroy = null
        }
        // copyAssets re-writes fonts + cacert from assets and is slow on first run; skip it
        // once the marker file exists so repeat inits (e.g. the Live TV preview) don't block.
        if (!java.io.File(context.filesDir, "cacert.pem").exists()) {
            Utils.copyAssets(context)
        }
        initialize(
            configDir = context.filesDir.path,
            cacheDir = context.cacheDir.path
        )
        initialized = true
    }

    fun setMedia(url: String, headers: Map<String, String>, startPositionMs: Long = 0L) {
        ensureInitialized()
        val requestKey = buildMediaRequestKey(url = url, headers = headers) +
            "#start=${startPositionMs.coerceAtLeast(0L)}"
        if (hasQueuedInitialMedia && requestKey == lastMediaRequestKey) {
            return
        }
        applyHeaders(headers)
        val startOption = startPositionMs
            .takeIf { it > 0L }
            ?.let { String.format(Locale.US, "start=%.3f", it / 1000.0) }
        if (startOption != null && holder.surface?.isValid == true) {
            ensureSurfaceAttachedIfAlreadyAvailable()
            ctl { loadFileWithOptions(url, startOption) }
            hasQueuedInitialMedia = true
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
        } else if (startOption != null) {
            pendingInitialMediaUrl = url
            pendingInitialStartOption = startOption
            hasQueuedInitialMedia = true
        } else if (hasQueuedInitialMedia) {
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
            if (holder.surface?.isValid == true) {
                ensureSurfaceAttachedIfAlreadyAvailable()
                ctl { mpv.command("loadfile", url, "replace") }
            } else {
                playFile(url)
            }
        } else {
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
            playFile(url)
            ensureSurfaceAttachedIfAlreadyAvailable()
            hasQueuedInitialMedia = true
        }
        lastMediaRequestKey = requestKey
        applyDefaultTrackSelectionForNewLoad()
        scheduleAspectModeRefresh(resetRetryCount = true)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // BaseMPVView writes android-surface-size straight from this callback, and
        // mpv_set_property takes the core lock — which a live demuxer holds for seconds at a
        // time. SurfaceView resizes synchronously inside View.layout, so that stalls main:
        //
        //   main  pthread_cond_wait <- mpv_set_property <- MPV.setPropertyString
        //         <- SurfaceView.updateSurface <- SurfaceView.setFrame <- View.layout
        //
        // Rarer here than on phones (no docked <-> fullscreen toggle), but the surface still
        // resizes on display-mode/AFR switches, and this is the same rule the rest of this
        // class follows: no mpv call ever runs on Main.
        //
        // Deliberately does NOT call super: the whole of BaseMPVView.surfaceChanged is that
        // one property write, which is what we are re-issuing off the main thread.
        ctl { mpv.setPropertyString("android-surface-size", "${width}x$height") }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        val url = pendingInitialMediaUrl ?: return
        val startOption = pendingInitialStartOption
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
        if (startOption != null) {
            ctl { loadFileWithOptions(url, startOption) }
        } else {
            ctl { mpv.command("loadfile", url, "replace") }
        }
    }

    /**
     * mpv's `loadfile` signature is `<url> [<flags> [<index> [<options>]]]`, so the per-file option
     * list belongs in the fifth argument. Passing it where `<index>` is expected makes mpv reject
     * the whole command and stay idle, i.e. resuming at a position would never load the file.
     */
    private fun loadFileWithOptions(url: String, options: String) {
        mpv.command("loadfile", url, "replace", LOADFILE_DEFAULT_INDEX, options)
    }

    fun setMediaUsingLoadfile(url: String, headers: Map<String, String>) {
        ensureInitialized()
        val requestKey = buildMediaRequestKey(url = url, headers = headers)
        applyHeaders(headers)
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
        if (holder.surface?.isValid == true) {
            ensureSurfaceAttachedIfAlreadyAvailable()
            ctl { mpv.command("loadfile", url, "replace") }
        } else {
            playFile(url)
        }
        hasQueuedInitialMedia = true
        lastMediaRequestKey = requestKey
        applyDefaultTrackSelectionForNewLoad()
        scheduleAspectModeRefresh(resetRetryCount = true)
    }

    private fun ensureSurfaceAttachedIfAlreadyAvailable() {
        if (!initialized) return
        val currentHolder = holder
        val currentSurface = currentHolder.surface ?: return
        if (!currentSurface.isValid) return
        runCatching {
            // Some fallback transitions initialize mpv after the Surface is already alive.
            // In that path, SurfaceHolder callback may not fire again, so force attach.
            surfaceCreated(currentHolder)
        }.onFailure {
            Log.w(TAG, "Failed to force MPV surface attach: ${it.message}")
        }
    }

    private fun applyDefaultTrackSelectionForNewLoad() = ctl {
        runCatching {
            // Let mpv choose the default streams for every new media load.
            mpv.setPropertyString("aid", "auto")
            mpv.setPropertyString("sid", "auto")
            mpv.setPropertyBoolean("sub-visibility", true)
        }.onFailure {
            Log.w(TAG, "Failed to reset default A/V track selection: ${it.message}")
        }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        // Optimistic shadow echo so isPlayingNow() right after reflects the intent;
        // mpv's own pause event confirms (or corrects) it moments later.
        obsPaused = paused
        ctl { mpv.setPropertyBoolean("pause", paused) }
    }

    fun stopPlayback() {
        if (!initialized) return
        // "stop" sets mpv's abort token at enqueue, interrupting a demuxer wedged in a
        // dead-socket read — the state that blocks the main thread inside BaseMPVView's
        // synchronous vo teardown when the window goes away. A raw thread, not the ctl
        // queue: the queue itself can be wedged inside a blocked command, and the whole
        // point is to abort that.
        Thread({ runCatching { mpv.command("stop") } }, "mpv-stop").start()
    }

    fun isPlayingNow(): Boolean {
        if (!initialized) return false
        return !obsPaused
    }

    fun isPausedForCacheNow(): Boolean {
        if (!initialized) return false
        return obsPausedForCache
    }

    fun isCoreIdleNow(): Boolean {
        if (!initialized) return false
        return obsCoreIdle
    }

    fun seekToMs(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        ctl { mpv.setPropertyDouble("time-pos", seconds) }
    }

    fun currentPositionMs(): Long {
        if (!initialized) return 0L
        return obsTimePosMs
    }

    fun durationMs(): Long {
        if (!initialized) return 0L
        return obsDurationMs
    }

    fun hasVideoTrackSelectedNow(): Boolean {
        if (!initialized) return false
        val vid = obsVid?.trim()
        return !vid.isNullOrBlank() && !vid.equals("no", ignoreCase = true)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!initialized) return
        ctl { mpv.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun applyAudioAmplificationDb(db: Int) {
        if (!initialized) return
        val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        val linearScale = 10.0.pow(clampedDb / 20.0)
        val targetVolumePercent = (100.0 * linearScale).coerceIn(0.0, MPV_MAX_VOLUME_PERCENT)
        ctl {
            runCatching {
                mpv.setPropertyDouble("volume", targetVolumePercent)
            }.onFailure {
                Log.w(TAG, "Failed to apply audio amplification on mpv (db=$clampedDb): ${it.message}")
            }
        }
    }

    fun applyAudioLanguagePreferences(languages: List<String>) {
        if (!initialized) return
        val normalized = languages
            .mapNotNull { language ->
                language.trim().takeIf { it.isNotBlank() }
            }
            .distinct()
        ctl {
            runCatching {
                // Empty value resets language preference back to default behavior.
                mpv.setPropertyString("alang", normalized.joinToString(","))
                // Re-run automatic audio selection with the latest preferences.
                mpv.setPropertyString("aid", "auto")
            }.onFailure {
                Log.w(TAG, "Failed to set audio language preference: ${it.message}")
            }
        }
    }

    fun applyHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
        hardwareDecodeMode = mode
        if (!initialized) return
        ctl {
            runCatching {
                mpv.setPropertyString("hwdec", mode.toMpvHwdecValue())
            }.onFailure {
                Log.w(TAG, "Failed to apply mpv hardware decode mode ($mode): ${it.message}")
            }
        }
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        if (!initialized) return
        ctl {
            runCatching {
                mpv.setPropertyDouble("sub-delay", delayMs / 1000.0)
            }.onFailure {
                Log.w(TAG, "Failed to set subtitle delay on mpv: ${it.message}")
            }
        }
    }

    fun applyAspectMode(mode: AspectMode) {
        currentAspectMode = mode
        pendingAspectRetryCount = 0
        removeCallbacks(aspectReapplyRunnable)
        applyAspectModeInternal(mode, allowRetry = true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return
        pendingAspectRetryCount = 0
        removeCallbacks(aspectReapplyRunnable)
        post {
            applyAspectModeInternal(currentAspectMode, allowRetry = true)
        }
    }

    private fun applyAspectModeInternal(mode: AspectMode, allowRetry: Boolean) {
        val viewAspect = readViewAspectRatio(width, height)
        // Video aspect comes from the observed-property shadow (lock-free — the direct
        // mpv reads here once ANR'd the Live guide on expand-resize); modes that don't
        // use it still skip the lookup.
        val videoAspect = if (aspectModeNeedsVideoAspect(mode)) readVideoAspectRatio() else null
        val scale = resolveAspectScale(
            mode = mode,
            viewAspect = viewAspect,
            videoAspect = videoAspect
        )
        scaleX = scale.scaleX
        scaleY = scale.scaleY
        if (
            allowRetry &&
            aspectModeNeedsVideoAspect(mode) &&
            (viewAspect <= 0f || videoAspect == null || videoAspect <= 0f)
        ) {
            scheduleAspectModeRefresh(resetRetryCount = false)
        }
    }

    private fun scheduleAspectModeRefresh(resetRetryCount: Boolean) {
        if (resetRetryCount) {
            pendingAspectRetryCount = 0
        }
        removeCallbacks(aspectReapplyRunnable)
        if (pendingAspectRetryCount >= MAX_ASPECT_RETRY_COUNT) {
            return
        }
        val delayMs = if (pendingAspectRetryCount == 0) 0L else ASPECT_RETRY_DELAY_MS
        pendingAspectRetryCount += 1
        postDelayed(aspectReapplyRunnable, delayMs)
    }

    fun applySubtitleStyle(style: SubtitleStyleSettings) {
        if (!initialized) return
        ctl { applySubtitleStyleNow(style) }
    }

    // Runs on the mpv-ctl thread only.
    private fun applySubtitleStyleNow(style: SubtitleStyleSettings) {
        runCatching {
            val scale = (style.size / 100.0).coerceIn(0.5, 3.0)
            val clampedOffset = style.verticalOffset.coerceIn(
                SUBTITLE_VERTICAL_OFFSET_MIN,
                SUBTITLE_VERTICAL_OFFSET_MAX
            )
            val normalizedOffset = (clampedOffset - SUBTITLE_VERTICAL_OFFSET_MIN).toDouble() /
                (SUBTITLE_VERTICAL_OFFSET_MAX - SUBTITLE_VERTICAL_OFFSET_MIN).toDouble()
            val subPos = MPV_SUB_POS_AT_BOTTOM -
                (normalizedOffset * (MPV_SUB_POS_AT_BOTTOM - MPV_SUB_POS_AT_TOP))
            val subMarginY = (MPV_SUB_MARGIN_Y_MIN +
                (normalizedOffset * (MPV_SUB_MARGIN_Y_MAX - MPV_SUB_MARGIN_Y_MIN))).toInt()
            val outlineSize = when {
                !style.outlineEnabled -> 0.0
                isAssOrSsaSubtitleSelectedNow() -> style.outlineWidth.coerceIn(1, 6).toDouble()
                else -> 1.0
            }
            val backgroundAlpha = (style.backgroundColor ushr 24) and 0xFF
            val borderStyle = if (backgroundAlpha > 0) "opaque-box" else "outline-and-shadow"

            mpv.setPropertyDouble("sub-scale", scale)
            mpv.setPropertyBoolean("sub-bold", style.bold)
            mpv.setPropertyDouble("sub-outline-size", outlineSize)
            mpv.setPropertyDouble("sub-pos", subPos)
            mpv.setPropertyInt("sub-margin-y", subMarginY)
            mpv.setPropertyDouble("sub-shadow-offset", 0.0)
            mpv.setPropertyString("sub-border-style", borderStyle)
            mpv.setPropertyString("sub-color", toMpvColor(style.textColor))
            mpv.setPropertyString("sub-back-color", toMpvColor(style.backgroundColor))
            mpv.setPropertyString("sub-outline-color", toMpvColor(style.outlineColor))
        }.onFailure {
            Log.w(TAG, "Failed to apply subtitle style on mpv: ${it.message}")
        }
    }

    private fun isAssOrSsaSubtitleSelectedNow(): Boolean {
        if (!initialized) return false
        val codec = readTrackSnapshot().subtitleTracks.firstOrNull { it.isSelected }
            ?.codec?.lowercase(Locale.US) ?: return false
        return codec.contains("ass") || codec.contains("ssa")
    }

    // The Boolean returns below report "accepted for dispatch": the write itself runs on
    // the mpv-ctl thread. With a live core the old synchronous calls only returned false
    // on a dead handle, which the initialized guard already covers.
    fun selectAudioTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        ctl {
            runCatching {
                mpv.setPropertyInt("aid", trackId)
            }.onFailure {
                Log.w(TAG, "Failed to select audio track id=$trackId: ${it.message}")
            }
        }
        return true
    }

    fun selectSubtitleTrackById(trackId: Int): Boolean {
        if (!initialized) return false
        ctl {
            runCatching {
                mpv.setPropertyBoolean("sub-visibility", true)
                mpv.setPropertyInt("sid", trackId)
            }.onFailure {
                Log.w(TAG, "Failed to select subtitle track id=$trackId: ${it.message}")
            }
        }
        return true
    }

    fun disableSubtitles(): Boolean {
        if (!initialized) return false
        ctl {
            runCatching {
                mpv.setPropertyString("sid", "no")
                mpv.setPropertyBoolean("sub-visibility", false)
            }.onFailure {
                Log.w(TAG, "Failed to disable subtitles: ${it.message}")
            }
        }
        return true
    }

    fun addAndSelectExternalSubtitle(
        url: String,
        title: String? = null,
        language: String? = null
    ): Boolean {
        if (!initialized) return false
        if (url.isBlank()) return false
        ctl {
            runCatching {
                // "cached" avoids duplicate re-loads for the same external subtitle.
                val safeTitle = title?.takeIf { it.isNotBlank() }
                val safeLanguage = language?.takeIf { it.isNotBlank() }
                when {
                    safeTitle != null && safeLanguage != null ->
                        mpv.command("sub-add", url, "cached", safeTitle, safeLanguage)
                    safeTitle != null ->
                        mpv.command("sub-add", url, "cached", safeTitle)
                    else ->
                        mpv.command("sub-add", url, "cached")
                }
                mpv.setPropertyBoolean("sub-visibility", true)
            }.onFailure {
                Log.w(TAG, "Failed to add external subtitle: ${it.message}")
            }
        }
        return true
    }

    fun applySubtitleLanguagePreferences(preferred: String, secondary: String?) {
        if (!initialized) return
        val languages = listOfNotNull(
            preferred.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) },
            secondary?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        )
        if (languages.isEmpty()) {
            disableSubtitles()
            return
        }
        ctl {
            runCatching {
                mpv.setPropertyString("slang", languages.joinToString(","))
            }.onFailure {
                Log.w(TAG, "Failed to set subtitle language preference: ${it.message}")
            }
        }
    }

    /**
     * Video facts for the stream info panel. Like [readTrackSnapshot] this reads only the
     * property shadow — never the mpv core — so it is safe to call from the main thread.
     *
     * Resolution comes from `video-params` (what the decoder actually produced) and falls
     * back to the selected video track's demuxer header. Bitrate prefers mpv's rolling
     * estimate because live MPEG-TS rarely declares one.
     *
     * Total by contract: every field is optional and any node access can throw if mpv
     * publishes an unexpected shape, so failure degrades to "unknown" rather than
     * propagating. Unlike [readTrackSnapshot] — whose only caller wraps it — this is read
     * straight from the UI event that opens the panel, where a throw would take the
     * player down.
     */
    fun readVideoSnapshot(): MpvVideoSnapshot = runCatching {
        if (!initialized) return@runCatching MpvVideoSnapshot()
        val videoTrack = obsTrackList?.asArray()?.toList().orEmpty().firstOrNull { node ->
            node.nodeString("type")?.lowercase() == "video" && node.nodeBoolean("selected") == true
        }
        MpvVideoSnapshot(
            width = obsVideoParams.nodeInt("w") ?: videoTrack.nodeInt("demux-w"),
            height = obsVideoParams.nodeInt("h") ?: videoTrack.nodeInt("demux-h"),
            codec = videoTrack.nodeString("codec"),
            frameRate = videoTrack.nodeDouble("demux-fps")?.toFloat()?.takeIf { it > 0f },
            // All three are bits per second, same unit as ExoPlayer's Format.bitrate.
            // Measured first, then the container's average, then the HLS variant's
            // declared rate (the only one many Xtream live channels expose).
            bitrate = (
                obsVideoBitrate
                    ?: videoTrack.nodeDouble("demux-bitrate")
                    ?: videoTrack.nodeDouble("hls-bitrate")
                )?.takeIf { it > 0.0 }?.roundToLong()?.toInt(),
            audioBitrate = obsAudioBitrate?.takeIf { it > 0.0 }?.roundToLong()?.toInt()
        )
    }.getOrElse {
        Log.w(TAG, "Failed to read mpv video snapshot: ${it.message}")
        MpvVideoSnapshot()
    }

    fun readTrackSnapshot(): MpvTrackSnapshot {
        if (!initialized) return MpvTrackSnapshot(emptyList(), emptyList())
        // Built from the observed track-list shadow — no synchronous mpv reads. The old
        // current-tracks/* fallbacks are gone: the per-track selected flag plus aid/sid
        // cover selection, and the snapshot refreshes every progress tick anyway.
        val nodes = obsTrackList?.asArray()?.toList().orEmpty()
        if (nodes.isEmpty()) {
            return MpvTrackSnapshot(emptyList(), emptyList())
        }

        val selectedAudioTrackId = obsAid?.toIntOrNull()
        val selectedSubtitleTrackId = obsSid?.toIntOrNull()

        val audioTracks = mutableListOf<MpvTrack>()
        val subtitleTracks = mutableListOf<MpvTrack>()

        for (node in nodes) {
            val type = node.nodeString("type")?.lowercase() ?: continue
            val id = node.nodeInt("id") ?: continue
            val language = node.nodeString("lang")
            val title = node.nodeString("title")
            val codec = node.nodeString("codec")
            val selectedByFlag = node.nodeBoolean("selected") == true
            val external = node.nodeBoolean("external") == true
            val channelCount = node.nodeInt("demux-channel-count")
                ?: node.nodeInt("audio-channels")
                ?: node.nodeInt("channels")
            val sampleRate = node.nodeInt("demux-samplerate")
            val forced = (node.nodeBoolean("forced") == true) || listOfNotNull(title, language).any {
                it.contains("forced", ignoreCase = true)
            }
            val selected = when (type) {
                "audio" -> (selectedAudioTrackId != null && selectedAudioTrackId == id) || selectedByFlag
                "sub" -> (selectedSubtitleTrackId != null && selectedSubtitleTrackId == id) || selectedByFlag
                else -> selectedByFlag
            }

            when (type) {
                "audio" -> {
                    audioTracks += MpvTrack(
                        id = id,
                        type = type,
                        name = title ?: language ?: context.getString(com.nuvio.tv.R.string.player_track_audio_fallback, id),
                        language = language,
                        codec = codec,
                        channelCount = channelCount,
                        sampleRate = sampleRate,
                        isSelected = selected,
                        isForced = false,
                        isExternal = external
                    )
                }

                "sub" -> {
                    subtitleTracks += MpvTrack(
                        id = id,
                        type = type,
                        name = title ?: language ?: context.getString(com.nuvio.tv.R.string.player_track_subtitle_fallback, id),
                        language = language,
                        codec = codec,
                        channelCount = null,
                        isSelected = selected,
                        isForced = forced,
                        isExternal = external
                    )
                }
            }
        }

        return MpvTrackSnapshot(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks
        )
    }

    fun releasePlayer() {
        if (!initialized) return
        removeCallbacks(aspectReapplyRunnable)
        // Flip the guard first so readers/writers no-op, then tear down on the control
        // thread: mpv_terminate_destroy joins the demuxer, which can hang on a dead
        // network read — that hang used to land on the main thread (BACK during a stall).
        initialized = false
        // Abort a wedged demuxer before destroy reaches the serialized control queue. Otherwise
        // an earlier blocked command can retain the native core and all of its buffers after the
        // UI releases this player.
        val destroyCompletion = CompletableFuture<Unit>()
        pendingDestroy = destroyCompletion
        Thread({
            runCatching { mpv.command("stop") }
            val destroyJob = runCatching {
                mpvCtl.submit {
                    runCatching { destroy() }
                        .onFailure { Log.w(TAG, "Failed to destroy libmpv view cleanly: ${it.message}") }
                }
            }.getOrNull()
            if (destroyJob != null) {
                runCatching { destroyJob.get() }
            }
            destroyCompletion.complete(Unit)
        }, "mpv-stop-release").start()
        hasQueuedInitialMedia = false
        lastMediaRequestKey = null
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
    }

    override fun initOptions() {
        mpv.setOptionString("profile", "fast")
        setVo("gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("user-agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        // Preserve native ASS/SSA styling behavior on MPV.
        mpv.setOptionString("sub-ass-override", "no")
        mpv.setOptionString("sub-font", "Roboto")
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-force-margins", "yes")
        mpv.setOptionString("hwdec", hardwareDecodeMode.toMpvHwdecValue())
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        // Bound blocking network reads (ffmpeg rw_timeout): a half-dead live socket
        // otherwise wedges the demuxer — and with it any thread waiting on the core.
        mpv.setOptionString("network-timeout", "15")
        // ffmpeg's HTTP demuxer does not reconnect on its own: an IPTV panel that closes the
        // socket mid-stream reads as a clean EOF, and with keep-open=yes the core parks on the
        // last frame forever. These make it re-open the URL instead, which covers the transient
        // drops before the app-level reconnect (PlayerRuntimeControllerLiveFreeze) has to.
        mpv.setOptionString(
            "stream-lavf-o",
            "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5",
        )
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        mpv.setOptionString("input-default-bindings", "yes")
        // Tuvora supplies its own controls; do not load mpv's built-in Lua console.
        mpv.setOptionString("load-console", "no")
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("softvol", "yes")
        mpv.setOptionString("volume-max", MPV_MAX_VOLUME_PERCENT.toInt().toString())
    }

    override fun postInitOptions() {
        mpv.setOptionString("save-position-on-quit", "no")
    }

    override fun observeProperties() {
        // Feed the property shadow (see fields above). PlayerRuntimeController still
        // polls, but the polls now read the shadow instead of the mpv core.
        resetPropertyShadow()
        // releasePlayer() → ensureInitialized() re-runs this; don't double-register.
        mpv.removeObserver(propertyShadow)
        mpv.addObserver(propertyShadow)
        val props = mapOf(
            "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "core-idle" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "time-pos" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "vid" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "aid" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "sid" to MPV.mpvFormat.MPV_FORMAT_STRING,
            "track-list" to MPV.mpvFormat.MPV_FORMAT_NODE,
            "video-out-params" to MPV.mpvFormat.MPV_FORMAT_NODE,
            "video-params" to MPV.mpvFormat.MPV_FORMAT_NODE,
            "video-bitrate" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "audio-bitrate" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
        )
        props.forEach { (name, format) -> mpv.observeProperty(name, format) }
    }

    private fun applyHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) {
            ctl { mpv.setPropertyString("http-header-fields", "") }
            return
        }
        val raw = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = ",") { (key, value) ->
                val escapedHeader = "$key: $value"
                    .replace("\\", "\\\\")
                    .replace(",", "\\,")
                escapedHeader
            }
        ctl { mpv.setPropertyString("http-header-fields", raw) }
    }

    private fun buildMediaRequestKey(url: String, headers: Map<String, String>): String {
        val normalizedHeaders = headers.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }, { it.value }))
            .joinToString(separator = "|") { "${it.key.trim()}:${it.value.trim()}" }
        return "$url#$normalizedHeaders"
    }

    private fun MpvHardwareDecodeMode.toMpvHwdecValue(): String {
        return when (this) {
            MpvHardwareDecodeMode.LEGACY_DIRECT_COPY -> "mediacodec,mediacodec-copy"
            MpvHardwareDecodeMode.AUTO_SAFE -> "auto-safe"
            MpvHardwareDecodeMode.HARDWARE_COPY -> "mediacodec-copy"
            MpvHardwareDecodeMode.HARDWARE_DIRECT -> "mediacodec"
            MpvHardwareDecodeMode.DISABLED -> "no"
        }
    }

    private fun toMpvColor(color: Int): String {
        return String.format(Locale.US, "#%08X", color)
    }

    private fun applyCoverAspectScale() {
        val viewAspect = if (width > 0 && height > 0) {
            width.toFloat() / height.toFloat()
        } else {
            0f
        }
        val videoAspect = readVideoAspectRatio()

        if (videoAspect != null && videoAspect > 0f && viewAspect > 0f) {
            if (videoAspect > viewAspect) {
                scaleX = 1.0f
                scaleY = videoAspect / viewAspect
            } else {
                scaleX = viewAspect / videoAspect
                scaleY = 1.0f
            }
            return
        }

        // Fallback to a visible zoom when video metadata/aspect is unavailable.
        scaleX = MPV_COVER_FALLBACK_SCALE
        scaleY = MPV_COVER_FALLBACK_SCALE
    }

    private fun readVideoAspectRatio(): Float? {
        if (!initialized) return null

        val directAspect = obsVideoOutParams.nodeDouble("aspect")
            ?: obsVideoParams.nodeDouble("aspect")
        if (directAspect != null && directAspect > 0.0) {
            return directAspect.toFloat()
        }

        val width = obsVideoOutParams.nodeInt("dw")
            ?: obsVideoParams.nodeInt("w")
            ?: return null
        val height = obsVideoOutParams.nodeInt("dh")
            ?: obsVideoParams.nodeInt("h")
            ?: return null
        if (width <= 0 || height <= 0) return null

        return width.toFloat() / height.toFloat()
    }

    companion object {
        private const val TAG = "NuvioMpvSurfaceView"
        /** `loadfile` insertion index; only meaningful for insert-at flags, -1 is mpv's default. */
        private const val LOADFILE_DEFAULT_INDEX = "-1"
        private const val MPV_COVER_FALLBACK_SCALE = 1.15f
        private const val MPV_MAX_VOLUME_PERCENT = 400.0
        private const val ASPECT_RETRY_DELAY_MS = 120L
        private const val MAX_ASPECT_RETRY_COUNT = 10
        private const val SUBTITLE_VERTICAL_OFFSET_MIN = -20
        private const val SUBTITLE_VERTICAL_OFFSET_MAX = 50
        private const val MPV_SUB_POS_AT_BOTTOM = 103.4
        private const val MPV_SUB_POS_AT_TOP = 72.4
        private const val MPV_SUB_MARGIN_Y_MIN = 0
        private const val MPV_SUB_MARGIN_Y_MAX = 60
    }
}

private fun MPVNode?.nodeString(key: String): String? =
    runCatching { this?.get(key)?.asString() }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

private fun MPVNode?.nodeInt(key: String): Int? =
    runCatching { this?.get(key)?.asInt()?.toInt() }.getOrNull()

private fun MPVNode?.nodeDouble(key: String): Double? =
    runCatching { this?.get(key)?.asDouble() }.getOrNull()

private fun MPVNode?.nodeBoolean(key: String): Boolean? =
    runCatching { this?.get(key)?.asBoolean() }.getOrNull()

data class MpvTrackSnapshot(
    val audioTracks: List<MpvTrack>,
    val subtitleTracks: List<MpvTrack>
)

data class MpvTrack(
    val id: Int,
    val type: String,
    val name: String,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val sampleRate: Int? = null,
    val isSelected: Boolean,
    val isForced: Boolean,
    val isExternal: Boolean
)

/**
 * What the stream info panel needs about the video being decoded, read off the
 * observed-property shadow. ExoPlayer hands the same facts over via `Format`; under
 * libmpv — which every live IPTV stream is forced onto — nothing else reports them.
 */
data class MpvVideoSnapshot(
    val width: Int? = null,
    val height: Int? = null,
    val codec: String? = null,
    val frameRate: Float? = null,
    val bitrate: Int? = null,
    val audioBitrate: Int? = null
)

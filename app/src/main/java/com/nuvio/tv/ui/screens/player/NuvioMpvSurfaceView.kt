package com.nuvio.tv.ui.screens.player

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.nuvio.tv.core.analytics.AppExitReporter
import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.SubtitleStyleSettings
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import com.nuvio.tv.player.mpv.MpvPropertyShadow
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.math.roundToLong

class NuvioMpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs), MpvSurface {

    private var initialized = false
    private var hasQueuedInitialMedia = false
    private var lastMediaRequestKey: String? = null
    private var pendingInitialMediaUrl: String? = null
    private var pendingInitialStartOption: String? = null
    @Volatile private var nativeCoreAlive = false
    private var lifecycleInstanceId = 0L
    // Read and written only by mpv-ctl.
    private var attachedSurface: Surface? = null

    /**
     * Invoked (on mpv's event thread — hop before touching UI/player state) when a file unloads
     * because of an ERROR: libmpv end-file with reason "error"; [fileError] is mpv's error string
     * (e.g. "loading failed"). Deliberately silent for eof/stop/quit/redirect unloads, so channel
     * switches ("loadfile replace" → reason "stop") and natural EOF never fire it.
     */
    /** Set by the controller in attachMpvView before setMedia; read in initOptions. */
    @Volatile override var demuxerBudget: com.nuvio.tv.core.contracts.DemuxerBudgetBytes? = null

    @Volatile override var onPlaybackEndedWithError: ((fileError: String?) -> Unit)? = null

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
        val instanceId = lifecycleInstanceId
        if (!initialized || !nativeCoreAlive || instanceId <= 0L) return
        runCatching {
            mpvCtl.execute {
                if (!nativeCoreAlive || lifecycleInstanceId != instanceId) return@execute
                runCatching(block)
            }
        }
    }
    private var hardwareDecodeMode: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE
    private var currentAspectMode: AspectMode = AspectMode.ORIGINAL
    private var pendingAspectRetryCount = 0
    private val aspectReapplyRunnable = Runnable {
        applyAspectModeInternal(currentAspectMode, allowRetry = true)
    }

    // The mpv property shadow lives in the fork-owned engine package; the surface view reads these
    // lock-free instead of calling mpv_get_property on the main thread (ANR). See MpvPropertyShadow.
    private val shadow = MpvPropertyShadow(onEndFileError = { onPlaybackEndedWithError?.invoke(it) })


    override fun ensureInitialized() {
        if (initialized) return
        // A queued teardown from the previous session (releasePlayer) must finish before
        // re-creating the core on the same MPV instance. Only blocks when re-init races
        // an in-flight destroy — the old code blocked main on every destroy instead.
        pendingDestroy?.let { destroyJob ->
            if (!destroyJob.isDone) {
                recordMpvStage("waiting_for_destroy", waitingInstances = 1)
            }
            runCatching { destroyJob.get() }
            pendingDestroy = null
        }
        check(!nativeCoreAlive) { "Previous native MPV core did not finish destruction" }
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
        nativeCoreAlive = true
        lifecycleInstanceId = NEXT_MPV_INSTANCE_ID.getAndIncrement()
        val active = ACTIVE_MPV_INSTANCES.incrementAndGet()
        updatePeakActiveInstances(active)
        recordMpvStage("initialized")
    }

    override fun setMedia(url: String, headers: Map<String, String>, startPositionMs: Long) {
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
                pendingInitialMediaUrl = url
            }
        } else {
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
            if (holder.surface?.isValid == true) {
                ensureSurfaceAttachedIfAlreadyAvailable()
                ctl { mpv.command("loadfile", url, "replace") }
            } else {
                pendingInitialMediaUrl = url
            }
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
        val surface = holder.surface ?: return
        val url = pendingInitialMediaUrl
        val startOption = pendingInitialStartOption
        if (url != null) {
            pendingInitialMediaUrl = null
            pendingInitialStartOption = null
        }
        // Do not call BaseMPVView: it attaches the Surface with blocking native calls on Main.
        ctl {
            attachSurfaceInternal(surface)
            if (url != null && startOption != null) {
                loadFileWithOptions(url, startOption)
            } else if (url != null) {
                mpv.command("loadfile", url, "replace")
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Keep detach ordered with every load, seek, stop, and destroy operation.
        ctl {
            detachSurfaceInternal()
            recordMpvStage("surface_detached")
        }
    }

    private fun attachSurfaceInternal(surface: Surface) {
        if (!surface.isValid) return
        if (attachedSurface === surface) return
        detachSurfaceInternal()
        mpv.attachSurface(surface)
        attachedSurface = surface
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
        recordMpvStage("surface_attached")
    }

    private fun detachSurfaceInternal() {
        if (attachedSurface == null) return
        runCatching { mpv.setPropertyString("vo", "null") }
        runCatching { mpv.setPropertyString("force-window", "no") }
        runCatching { mpv.detachSurface() }
        attachedSurface = null
    }

    /**
     * mpv's `loadfile` signature is `<url> [<flags> [<index> [<options>]]]`, so the per-file option
     * list belongs in the fifth argument. Passing it where `<index>` is expected makes mpv reject
     * the whole command and stay idle, i.e. resuming at a position would never load the file.
     */
    private fun loadFileWithOptions(url: String, options: String) {
        mpv.command("loadfile", url, "replace", LOADFILE_DEFAULT_INDEX, options)
    }

    override fun setMediaUsingLoadfile(url: String, headers: Map<String, String>) {
        ensureInitialized()
        val requestKey = buildMediaRequestKey(url = url, headers = headers)
        applyHeaders(headers)
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
        if (holder.surface?.isValid == true) {
            ensureSurfaceAttachedIfAlreadyAvailable()
            ctl { mpv.command("loadfile", url, "replace") }
        } else {
            pendingInitialMediaUrl = url
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

    override fun setPaused(paused: Boolean) {
        if (!initialized) return
        // Optimistic shadow echo so isPlayingNow() right after reflects the intent;
        // mpv's own pause event confirms (or corrects) it moments later.
        shadow.obsPaused = paused
        ctl { mpv.setPropertyBoolean("pause", paused) }
    }

    override fun stopPlayback() {
        if (!initialized) return
        ctl { mpv.command("stop") }
    }

    override fun isPlayingNow(): Boolean {
        if (!initialized) return false
        return !shadow.obsPaused
    }

    override fun isPausedForCacheNow(): Boolean {
        if (!initialized) return false
        return shadow.obsPausedForCache
    }

    override fun isCoreIdleNow(): Boolean {
        if (!initialized) return false
        return shadow.obsCoreIdle
    }

    /**
     * Evidence the picture is alive, for live-freeze detection. Only ever increments, so any
     * change since the previous sample means a frame was produced. Audio cannot move it, which
     * is exactly why the playhead is not enough.
     */
    override fun videoFrameTicksNow(): Long = shadow.obsVideoFrameTicks

    /** Whether a picture is expected at all — IPTV radio stations legitimately render none. */
    override fun hasVideoTrackNow(): Boolean = initialized && shadow.obsVideoParams != null

    /**
     * mpv `frame-drop-count`, read off the shadow: frames the VO dropped, including frames it
     * could not display on time. [videoFrameTicksNow] is fed by `estimated-vf-fps`, which
     * measures the filter chain — decoding — rather than presentation; mpv has no true
     * presented-frames property, so this and [voDelayedFrameCountNow] are the closest VO-level
     * signals to "the picture reached the screen". The TV twin of mobile's
     * `PlayerPlaybackSnapshot.voDroppedFrameCount`; recorded for the live-freeze work, not a
     * detection input yet. Resets with the core, so consumers must diff defensively.
     */
    override fun voDroppedFrameCountNow(): Long = shadow.obsVoDroppedFrames

    /** mpv `vo-delayed-frame-count`: delayed-vsync estimate. See [voDroppedFrameCountNow]. */
    override fun voDelayedFrameCountNow(): Long = shadow.obsVoDelayedFrames

    /**
     * Reinitialises the video track off the demuxer that is already connected, for a channel
     * whose picture died while its audio kept playing. Costs the provider nothing — no new
     * create_link, nothing spent against its connection cap — unlike a full re-prepare.
     */
    override fun reloadVideoTrack() {
        if (!initialized) return
        ctl { mpv.command("video-reload") }
    }

    override fun seekToMs(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        ctl { mpv.setPropertyDouble("time-pos", seconds) }
    }

    override fun currentPositionMs(): Long {
        if (!initialized) return 0L
        return shadow.obsTimePosMs
    }

    override fun durationMs(): Long {
        if (!initialized) return 0L
        return shadow.obsDurationMs
    }

    override fun hasVideoTrackSelectedNow(): Boolean {
        if (!initialized) return false
        val vid = shadow.obsVid?.trim()
        return !vid.isNullOrBlank() && !vid.equals("no", ignoreCase = true)
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (!initialized) return
        ctl { mpv.setPropertyDouble("speed", speed.toDouble()) }
    }

    override fun applyAudioAmplificationDb(db: Int) {
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

    override fun applyAudioLanguagePreferences(languages: List<String>) {
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

    override fun applyHardwareDecodeMode(mode: MpvHardwareDecodeMode) {
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

    override fun setSubtitleDelayMs(delayMs: Int) {
        if (!initialized) return
        ctl {
            runCatching {
                mpv.setPropertyDouble("sub-delay", delayMs / 1000.0)
            }.onFailure {
                Log.w(TAG, "Failed to set subtitle delay on mpv: ${it.message}")
            }
        }
    }

    override fun applyAspectMode(mode: AspectMode) {
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

    override fun applySubtitleStyle(style: SubtitleStyleSettings) {
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
    override fun selectAudioTrackById(trackId: Int): Boolean {
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

    override fun selectSubtitleTrackById(trackId: Int): Boolean {
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

    override fun disableSubtitles(): Boolean {
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

    override fun addAndSelectExternalSubtitle(
        url: String,
        title: String?,
        language: String?
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

    override fun applySubtitleLanguagePreferences(preferred: String, secondary: String?) {
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
    override fun readVideoSnapshot(): MpvVideoSnapshot = runCatching {
        if (!initialized) return@runCatching MpvVideoSnapshot()
        val videoTrack = shadow.obsTrackList?.asArray()?.toList().orEmpty().firstOrNull { node ->
            node.nodeString("type")?.lowercase() == "video" && node.nodeBoolean("selected") == true
        }
        MpvVideoSnapshot(
            width = shadow.obsVideoParams.nodeInt("w") ?: videoTrack.nodeInt("demux-w"),
            height = shadow.obsVideoParams.nodeInt("h") ?: videoTrack.nodeInt("demux-h"),
            codec = videoTrack.nodeString("codec"),
            frameRate = videoTrack.nodeDouble("demux-fps")?.toFloat()?.takeIf { it > 0f },
            // All three are bits per second, same unit as ExoPlayer's Format.bitrate.
            // Measured first, then the container's average, then the HLS variant's
            // declared rate (the only one many Xtream live channels expose).
            bitrate = (
                shadow.obsVideoBitrate
                    ?: videoTrack.nodeDouble("demux-bitrate")
                    ?: videoTrack.nodeDouble("hls-bitrate")
                )?.takeIf { it > 0.0 }?.roundToLong()?.toInt(),
            audioBitrate = shadow.obsAudioBitrate?.takeIf { it > 0.0 }?.roundToLong()?.toInt()
        )
    }.getOrElse {
        Log.w(TAG, "Failed to read mpv video snapshot: ${it.message}")
        MpvVideoSnapshot()
    }

    override fun readTrackSnapshot(): MpvTrackSnapshot {
        if (!initialized) return MpvTrackSnapshot(emptyList(), emptyList())
        // Built from the observed track-list shadow — no synchronous mpv reads. The old
        // current-tracks/* fallbacks are gone: the per-track selected flag plus aid/sid
        // cover selection, and the snapshot refreshes every progress tick anyway.
        val nodes = shadow.obsTrackList?.asArray()?.toList().orEmpty()
        if (nodes.isEmpty()) {
            return MpvTrackSnapshot(emptyList(), emptyList())
        }

        val selectedAudioTrackId = shadow.obsAid?.toIntOrNull()
        val selectedSubtitleTrackId = shadow.obsSid?.toIntOrNull()

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

    override fun releasePlayer() {
        if (!initialized) return
        removeCallbacks(aspectReapplyRunnable)
        runCatching { holder.removeCallback(this) }
        // Flip the guard first so readers/writers no-op, then tear down on the control
        // thread: mpv_terminate_destroy joins the demuxer, which can hang on a dead
        // network read — that hang used to land on the main thread (BACK during a stall).
        initialized = false
        val releaseStartedAtMs = SystemClock.elapsedRealtime()
        val instanceId = lifecycleInstanceId
        recordMpvStage("release_started", instanceId = instanceId)
        val destroyCompletion = CompletableFuture<Unit>()
        pendingDestroy = destroyCompletion
        runCatching {
            mpvCtl.submit {
                var destroyed = false
                try {
                    // A single ordered teardown prevents stop/surface detach/destroy from racing
                    // each other while the native core is releasing descriptors and buffers.
                    runCatching { mpv.command("stop") }
                    detachSurfaceInternal()
                    runCatching { mpv.removeObserver(shadow) }
                    runCatching { mpv.destroy() }
                        .onSuccess { destroyed = true }
                        .onFailure { Log.w(TAG, "Failed to destroy libmpv view cleanly: ${it.message}") }
                } finally {
                    val waitMs = SystemClock.elapsedRealtime() - releaseStartedAtMs
                    if (destroyed) {
                        nativeCoreAlive = false
                        ACTIVE_MPV_INSTANCES.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
                        recordMpvStage("destroyed", waitMs, instanceId)
                        destroyCompletion.complete(Unit)
                    } else {
                        // Do not unblock reinitialization if the previous native core may still
                        // own descriptors or Surface buffers.
                        recordMpvStage("destroy_failed", waitMs, instanceId)
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to queue libmpv destruction: ${error.message}")
            recordMpvStage("destroy_queue_failed", instanceId = instanceId)
            destroyCompletion.complete(Unit)
        }
        postDelayed({
            if (!destroyCompletion.isDone) {
                recordMpvStage(
                    stage = "destroy_timeout",
                    destroyWaitMs = SystemClock.elapsedRealtime() - releaseStartedAtMs,
                    instanceId = instanceId,
                )
            }
        }, MPV_DESTROY_WATCHDOG_MS)
        hasQueuedInitialMedia = false
        lastMediaRequestKey = null
        pendingInitialMediaUrl = null
        pendingInitialStartOption = null
    }

    private fun recordMpvStage(
        stage: String,
        destroyWaitMs: Long? = null,
        instanceId: Long = lifecycleInstanceId,
        waitingInstances: Int = 0,
    ) {
        if (instanceId <= 0L) return
        AppExitReporter.recordMpvLifecycle(
            context = context,
            instanceId = instanceId,
            stage = stage,
            activeInstances = ACTIVE_MPV_INSTANCES.get(),
            waitingInstances = waitingInstances,
            peakActiveInstances = PEAK_ACTIVE_MPV_INSTANCES.get(),
            destroyWaitMs = destroyWaitMs,
        )
    }


    override fun initOptions() {
        mpv.setOptionString("profile", "fast")
        setVo("gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("user-agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        // Preserve native ASS/SSA styling behavior on MPV.
        mpv.setOptionString("sub-ass-override", "no")
        mpv.setOptionString("sub-codepage", "auto:utf-8")
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
        // Demuxer cache tiered by device memory. The flat 64+64MiB this replaces was the
        // largest native cache in the fleet, granted on the smallest devices; LOW now gets
        // 48+16MiB and everything else 64+32MiB — mobile's proven forward:back ratio, which
        // spends the budget on the forward window that actually absorbs network jitter.
        // Budget is injected by the controller (device tier resolved via PlayerMemoryBudget); fall
        // back to the MID/HIGH values if unset (matches the previous no-context default).
        val demuxerBytes = demuxerBudget
            ?: com.nuvio.tv.core.contracts.DemuxerBudgetBytes(64L * 1024 * 1024, 32L * 1024 * 1024)
        mpv.setOptionString("demuxer-max-bytes", "${demuxerBytes.maxBytes}")
        mpv.setOptionString("demuxer-max-back-bytes", "${demuxerBytes.maxBackBytes}")
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
        shadow.reset()
        // releasePlayer() → ensureInitialized() re-runs this; don't double-register.
        mpv.removeObserver(shadow)
        mpv.addObserver(shadow)
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
            // Fires roughly per frame, like time-pos above; the handler is a volatile increment.
            "estimated-vf-fps" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            // VO-level counters: change only when a frame is dropped or a vsync runs long, so
            // they are near-silent during healthy playback.
            "frame-drop-count" to MPV.mpvFormat.MPV_FORMAT_INT64,
            "vo-delayed-frame-count" to MPV.mpvFormat.MPV_FORMAT_INT64,
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

        val directAspect = shadow.obsVideoOutParams.nodeDouble("aspect")
            ?: shadow.obsVideoParams.nodeDouble("aspect")
        if (directAspect != null && directAspect > 0.0) {
            return directAspect.toFloat()
        }

        val width = shadow.obsVideoOutParams.nodeInt("dw")
            ?: shadow.obsVideoParams.nodeInt("w")
            ?: return null
        val height = shadow.obsVideoOutParams.nodeInt("dh")
            ?: shadow.obsVideoParams.nodeInt("h")
            ?: return null
        if (width <= 0 || height <= 0) return null

        return width.toFloat() / height.toFloat()
    }

    companion object {
        private const val TAG = "NuvioMpvSurfaceView"
        private const val MPV_DESTROY_WATCHDOG_MS = 20_000L
        private val NEXT_MPV_INSTANCE_ID = AtomicLong(1L)
        private val ACTIVE_MPV_INSTANCES = AtomicInteger(0)
        private val PEAK_ACTIVE_MPV_INSTANCES = AtomicInteger(0)

        private fun updatePeakActiveInstances(activeInstances: Int) {
            while (true) {
                val currentPeak = PEAK_ACTIVE_MPV_INSTANCES.get()
                if (activeInstances <= currentPeak) return
                if (PEAK_ACTIVE_MPV_INSTANCES.compareAndSet(currentPeak, activeInstances)) return
            }
        }
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


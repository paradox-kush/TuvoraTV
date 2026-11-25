package com.nuvio.tv.core.telemetry

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import com.nuvio.tv.core.analytics.AppExitReporter
import com.nuvio.tv.core.telemetry.playback.MemorySamplePolicy
import com.nuvio.tv.core.telemetry.playback.MemorySampleRing
import com.nuvio.tv.core.telemetry.playback.PlaybackTelemetry
import com.nuvio.tv.core.telemetry.playback.PlaybackTelemetryRecorder
import com.nuvio.tv.core.telemetry.playback.QaMirror
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.posthog.PostHog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Reached by the crash handler, which runs outside DI. Set once by [TelemetryService.start]. */
object TelemetryHolder {
    @Volatile var recorder: FlightRecorder? = null

    /** Mirrors the "Crash & usage reports" switch. False until startup has read it. */
    @Volatile var consentEnabled: Boolean = false

    /** The newest process memory samples, read by the crash handler (outside DI) and by reports. */
    @Volatile var memoryRing: MemorySampleRing? = null
}

@Singleton
class TelemetryService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sentrySettings: SentrySettingsDataStore,
) : TelemetryPort {

    val recorder = FlightRecorder()
    val health = SessionHealth()

    /** Tiny ring of process memory samples (every 10 s while playing); the last one rides on exits, crashes and reports. */
    val memoryRing = MemorySampleRing()
    private val memorySampler = ProcessMemorySampler()

    /**
     * Playback telemetry (time to first frame, fallbacks, stalls, teardown, multiview). Routine successes are only
     * counted into [health]; the one event it sends is `multiview_session`. The "Crash & usage reports" switch
     * ([TelemetryHolder.consentEnabled]) disables counting and sending; the QA mirror is local logcat only.
     */
    val playbackTelemetry = PlaybackTelemetryRecorder(
        consent = { TelemetryHolder.consentEnabled },
        count = { health.increment(it) },
        sendEvent = { event, properties -> PostHog.capture(event = event, properties = properties) },
        qa = QaMirror(
            enabled = QaMirror.parseEnabled(readQaProperty()),
            sink = { line -> android.util.Log.i(QaMirror.TAG, line) },
        ),
        lastMemory = { memoryRing.last() },
    )
    private val rateLimit = ReportRateLimit()

    override val supportCode: String by lazy { SupportCodeStore.get(context) }

    // First-run funnel. A support code already on disk is an existing-install signal, so it is read here, at
    // construction (Hilt builds this in super.onCreate), before anything can generate one.
    private val firstRun = FirstRunFunnelService(
        store = PrefsFirstRunStateStore(context),
        nowMs = System::currentTimeMillis,
        consentEnabled = { TelemetryHolder.consentEnabled },
        sink = { event, props -> PostHog.capture(event = event, properties = props) },
        flush = { flushAnalyticsAsync() },
        supportCodeExistedAtStart = SupportCodeGenerator.isValid(
            runCatching { context.getSharedPreferences("telemetry", Context.MODE_PRIVATE).getString("support_code", null) }.getOrNull(),
        ),
    ).also { FirstRun.service = it }

    @Volatile private var previousRun: List<DiagEntry> = emptyList()
    @Volatile private var appVersion: String = ""
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var lastWrittenVersion = -1L
    @Volatile private var sampling = false
    @Volatile private var samplingGeneration = 0

    val gate: TelemetryGate by lazy {
        TelemetryGate(
            recorder = recorder,
            health = health,
            onceStore = PrefsOncePerVersionStore(context),
            appVersion = appVersion,
            previousRun = { previousRun },
        )
    }

    private val dir get() = File(context.filesDir, "telemetry")
    private val ringFile get() = File(dir, "ring.json")

    /** Call once, early in Application.onCreate, before any other component records. */
    fun start(appVersion: String) {
        this.appVersion = appVersion
        previousRun = runCatching { DiagTrailPolicy.fromJson(ringFile.takeIf { it.exists() }?.readText().orEmpty()) }
            .getOrDefault(emptyList())
        TelemetryHolder.recorder = recorder
        TelemetryHolder.memoryRing = memoryRing
        PlaybackTelemetry.recorder = playbackTelemetry
        val thread = HandlerThread("telemetry-io", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        worker = thread
        handler = Handler(thread.looper).also { scheduleWrite(it) }
        record(DiagEntry(System.currentTimeMillis(), DiagArea.LIFECYCLE, "app_start", mapOf("sdk_int" to android.os.Build.VERSION.SDK_INT)))
    }

    override fun record(entry: DiagEntry) = recorder.record(entry)

    /**
     * First-run funnel start, once per process, early in Application.onCreate before anything creates user state.
     * [otherExistingSignals] (playlists, add-ons, watch history in any profile) is asked only on the first launch
     * of a build with the funnel. Events wait for [firstRunSdkReady].
     */
    fun startFirstRunFunnel(otherExistingSignals: () -> Boolean) = firstRun.start(otherExistingSignals)

    /** The whole app went to / came back from the background (process lifecycle): drives `first_leave`. */
    fun firstRunAppBackgrounded() = firstRun.onBackground()
    fun firstRunAppForegrounded() = firstRun.onForeground()

    /**
     * `PostHog.flush()` (verified present in the resolved posthog 6.35.0 `PostHog` class) must not run on Main, where the
     * background signal arrives. A short-lived thread, not the telemetry worker: playback stop clears that handler's queue.
     * The capture was submitted to the SDK's own executor first, so this flush uploads it. Once per install at most.
     */
    private fun flushAnalyticsAsync() {
        Thread({ runCatching { PostHog.flush() } }, "first-leave-flush").apply { isDaemon = true }.start()
    }

    /** PostHog is set up: funnel events held until now go out (consent is checked as each is sent). */
    fun firstRunSdkReady() = firstRun.markSdkReady()

    private fun scheduleWrite(h: Handler) {
        h.postDelayed({
            writeRingIfDirty()
            scheduleWrite(h)
        }, RING_WRITE_INTERVAL_MS)
    }

    /** Delta-shaped: writes only when something was recorded since the last write; atomic. */
    fun writeRingIfDirty() {
        if (!TelemetryHolder.consentEnabled) return
        val v = recorder.version.get()
        if (v == lastWrittenVersion) return
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "ring.json.tmp")
            tmp.writeText(DiagTrailPolicy.toJson(recorder.snapshot()))
            tmp.renameTo(ringFile)
            lastWrittenVersion = v
        }
    }

    /**
     * Applies the "Crash & usage reports" switch to everything held on disk. Turning reports OFF
     * deletes the saved ring and any waiting crash records and clears what is in memory, so nothing
     * recorded while they were off, or before, can be sent later.
     */
    fun applyConsent(enabled: Boolean) {
        TelemetryHolder.consentEnabled = enabled
        if (enabled) return
        previousRun = emptyList()
        recorder.restore(emptyList())
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /** Immediately after an error, so a following kill still leaves the lead-up on disk. */
    fun flushNow() { handler?.post { writeRingIfDirty() } }

    fun onPlaybackStarted() {
        if (sampling) return
        sampling = true
        val gen = ++samplingGeneration
        handler?.post(SampleTask(gen))
    }

    fun onPlaybackStopped() {
        sampling = false
        samplingGeneration++ // a loop that is mid-run sees the new generation and ends
        handler?.removeCallbacksAndMessages(null)
        handler?.let { scheduleWrite(it) }
    }

    /**
     * One sampling loop per playback; a stale loop (older generation) never reschedules itself. It runs on the
     * telemetry worker (never Main) every [MEMORY_SAMPLE_INTERVAL_MS]: one process memory sample into the ring (and
     * the QA mirror), the flight-recorder line every [FLIGHT_RECORDER_EVERY] samples as before, and the compact
     * sample persisted every [PERSIST_EVERY] samples so a kill still leaves one for the next launch's `app_exit`.
     */
    private inner class SampleTask(private val generation: Int) : Runnable {
        private var tick = 0

        override fun run() {
            if (!sampling || generation != samplingGeneration) return
            if (TelemetryHolder.consentEnabled || playbackTelemetry.qaEnabled) {
                runCatching {
                    val sample = memorySampler.sample()
                    memoryRing.add(sample)
                    playbackTelemetry.memorySampled(sample)
                    if (TelemetryHolder.consentEnabled && tick % PERSIST_EVERY == 0) {
                        AppExitReporter.recordMemoryContext(context, sample)
                    }
                }
            }
            if (tick % FLIGHT_RECORDER_EVERY == 0) {
                val rt = Runtime.getRuntime()
                recorder.record(
                    DiagArea.MEMORY, "sample",
                    mapOf(
                        "heap_used_mb" to (rt.totalMemory() - rt.freeMemory()) / MB,
                        "heap_max_mb" to rt.maxMemory() / MB,
                        "native_mb" to Debug.getNativeHeapAllocatedSize() / MB,
                    ),
                )
            }
            tick++
            handler?.postDelayed(this, MEMORY_SAMPLE_INTERVAL_MS)
        }
    }

    /** One compact event per app session; call when the app goes to the background. */
    fun flushHealth() {
        val counts = health.drain()
        if (counts.isEmpty()) return
        PostHog.capture(
            event = SessionHealth.EVENT,
            properties = mapOf("schema_version" to 1, "counts" to counts),
        )
    }

    override suspend fun sendUserReport(
        category: ReportCategory,
        surface: ReportSurface,
        failureCode: String?,
    ): ReportResult {
        if (!sentrySettings.isEnabled()) return ReportResult.ConsentOff
        val wait = rateLimit.tryAcquire(System.currentTimeMillis())
        if (wait > 0) return ReportResult.RateLimited(((wait + 999) / 1000).toInt())
        // PostHog.flush() must not run on Main: callers launch from UI scopes.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching {
            val rt = Runtime.getRuntime()
            recorder.record(DiagArea.UI, "user_report", mapOf("surface" to surface.wire, "category" to category.wire))
            PostHog.capture(
                event = "user_issue_report",
                properties = buildMap {
                    put("schema_version", 1)
                    put("report_id", UUID.randomUUID().toString())
                    put("category", category.wire)
                    put("surface", surface.wire)
                    failureCode?.takeIf { it.isNotBlank() }?.let { put("failure_code", DiagTrailPolicy.safeCode(it)) }
                    put("heap_used_mb", (rt.totalMemory() - rt.freeMemory()) / MB)
                    put("heap_max_mb", rt.maxMemory() / MB)
                    put("native_mb", Debug.getNativeHeapAllocatedSize() / MB)
                    // Playback telemetry contract: the last on-device memory sample (one taken now when none exists yet).
                    (memoryRing.last() ?: runCatching { memorySampler.sample() }.getOrNull())?.let { putAll(it.toProperties()) }
                    // A report carries the long trail (80 lines); the gate keeps a trail that is already set.
                    put("diag_trail", DiagTrailPolicy.render(recorder.snapshot(), TelemetryGate.TRAIL_ON_REPORT))
                },
            )
            PostHog.flush()
            flushNow()
            ReportResult.Sent(supportCode)
        }.getOrElse { rateLimit.release(); ReportResult.Failed } }
    }

    companion object {
        private const val MB = 1024L * 1024L
        private const val RING_WRITE_INTERVAL_MS = 30_000L
        private const val MEMORY_SAMPLE_INTERVAL_MS = MemorySamplePolicy.MIN_INTERVAL_MS
        private const val FLIGHT_RECORDER_EVERY = 6 // 60 s, as before
        private const val PERSIST_EVERY = 3 // 30 s

        /**
         * `debug.tuvora.qa` read once: reflection on the framework's SystemProperties, else `getprop`. Anything but
         * a literal `1` (or a failed read) leaves the QA mirror off.
         */
        private fun readQaProperty(): String? = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, QaMirror.ANDROID_PROPERTY, "") as? String
        }.getOrNull() ?: runCatching {
            val process = ProcessBuilder("getprop", QaMirror.ANDROID_PROPERTY).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrNull()
    }
}

private class PrefsOncePerVersionStore(context: Context) : OncePerVersionStore {
    private val prefs = context.getSharedPreferences("telemetry_once", Context.MODE_PRIVATE)

    @Synchronized
    override fun firstTime(key: String): Boolean {
        if (prefs.contains(key)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }
}

package com.nuvio.tv.core.telemetry

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
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
}

@Singleton
class TelemetryService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sentrySettings: SentrySettingsDataStore,
) : TelemetryPort {

    val recorder = FlightRecorder()
    val health = SessionHealth()
    private val rateLimit = ReportRateLimit()

    override val supportCode: String by lazy { SupportCodeStore.get(context) }

    @Volatile private var previousRun: List<DiagEntry> = emptyList()
    @Volatile private var appVersion: String = ""
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var lastWrittenVersion = -1L
    @Volatile private var sampling = false

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
        val thread = HandlerThread("telemetry-io", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        worker = thread
        handler = Handler(thread.looper).also { scheduleWrite(it) }
        record(DiagEntry(System.currentTimeMillis(), DiagArea.LIFECYCLE, "app_start", mapOf("sdk_int" to android.os.Build.VERSION.SDK_INT)))
    }

    override fun record(entry: DiagEntry) = recorder.record(entry)

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

    /** Immediately after an error, so a following kill still leaves the lead-up on disk. */
    fun flushNow() { handler?.post { writeRingIfDirty() } }

    fun onPlaybackStarted() {
        if (sampling) return
        sampling = true
        handler?.post(sampleTask)
    }

    fun onPlaybackStopped() {
        sampling = false
        handler?.removeCallbacks(sampleTask)
    }

    private val sampleTask = object : Runnable {
        override fun run() {
            if (!sampling) return
            val rt = Runtime.getRuntime()
            recorder.record(
                DiagArea.MEMORY, "sample",
                mapOf(
                    "heap_used_mb" to (rt.totalMemory() - rt.freeMemory()) / MB,
                    "heap_max_mb" to rt.maxMemory() / MB,
                    "native_mb" to Debug.getNativeHeapAllocatedSize() / MB,
                ),
            )
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
        return runCatching {
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
                    // The gate attaches the (shorter) error trail; a report carries the longer one.
                    put("report_trail", DiagTrailPolicy.render(recorder.snapshot(), TelemetryGate.TRAIL_ON_REPORT))
                },
            )
            PostHog.flush()
            flushNow()
            ReportResult.Sent(supportCode)
        }.getOrElse { ReportResult.Failed }
    }

    companion object {
        private const val MB = 1024L * 1024L
        private const val RING_WRITE_INTERVAL_MS = 30_000L
        private const val MEMORY_SAMPLE_INTERVAL_MS = 60_000L
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

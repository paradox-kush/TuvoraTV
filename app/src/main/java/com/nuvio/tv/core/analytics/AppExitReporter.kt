package com.nuvio.tv.core.analytics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import com.nuvio.tv.BuildConfig
import com.posthog.PostHog
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports OS process-exit records as PostHog `app_exit` events on the next launch.
 *
 * ApplicationExitInfo covers failures an uncaught-exception handler cannot observe, including
 * native crashes, ANRs, and low-memory kills. Run metadata is kept separately because PostHog's
 * automatic app-version properties describe the launch that uploads the event, not necessarily
 * the process that failed.
 */
object AppExitReporter {

    private const val PREFS = "app_exit_reporter"
    private const val KEY_LAST_SEEN_TS = "last_seen_exit_ts"
    private const val KEY_SEEN_EXIT_IDS = "seen_exit_ids_v2"
    private const val KEY_RUN_STARTS = "run_starts_v2"
    private const val KEY_CURRENT_RUN_START = "current_run_start_v2"
    private const val MAX_EXITS = 16
    private const val MAX_SEEN_EXIT_IDS = 64
    private const val MAX_RUN_CONTEXTS = 32
    private const val MAX_DESCRIPTION_CHARS = 512
    private const val MAX_TRACE_CHARS = 6_000
    private const val PROCESS_SAMPLE_INTERVAL_SECONDS = 30L
    private val lock = Any()
    private val diagnosticsSampler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "AppDiagnosticsSampler").apply { isDaemon = true }
    }
    private val processSamplerStarted = AtomicBoolean(false)
    private var previousProcessSample: ProcessInventorySample? = null

    /** Abnormal exit reasons worth reporting; everything else is normal lifecycle noise. */
    private val REPORTED_REASONS = mapOf(
        ApplicationExitInfo.REASON_CRASH to "crash",
        ApplicationExitInfo.REASON_CRASH_NATIVE to "native_crash",
        ApplicationExitInfo.REASON_ANR to "anr",
        ApplicationExitInfo.REASON_LOW_MEMORY to "low_memory_kill",
        ApplicationExitInfo.REASON_SIGNALED to "signaled",
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "excessive_resource_usage",
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "initialization_failure",
    )

    /** Call once from Application.onCreate, after PostHog setup. */
    fun reportPendingExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appContext = context.applicationContext

        // Snapshot the new run immediately, before it can fail. The binder query and trace reads
        // remain off the main thread and can never delay startup.
        runCatching { beginRun(appContext) }
        recordMemorySnapshot(appContext, "startup")
        startProcessSampling(appContext)
        Thread({ runCatching { doReport(appContext) } }, "AppExitReporter").start()
    }

    /**
     * Persist a privacy-safe destination name for attribution after a process death.
     * Navigation arguments and query parameters are intentionally discarded.
     */
    fun recordRoute(context: Context, route: String?) {
        val safeRoute = safeRouteName(route) ?: return
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val runStart = prefs.getLong(KEY_CURRENT_RUN_START, 0L)
            if (runStart <= 0L) return
            prefs.edit()
                .putString(runKey(runStart, "route"), safeRoute)
                .putString(runKey(runStart, "action"), "navigation")
                .putLong(runKey(runStart, "context_at"), System.currentTimeMillis())
                .apply()
        }
        recordMemorySnapshot(context, "route_$safeRoute")
    }

    /**
     * Persist that playback is running. `app_exit` carries this as `was_playing` plus how long
     * the stream had been up — the difference between "crashed browsing" and "crashed streaming",
     * which no same-session event can answer for a next-launch report.
     */
    fun recordPlaybackStarted(context: Context, kind: String, engine: String, surface: String) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val runStart = prefs.getLong(KEY_CURRENT_RUN_START, 0L)
            if (runStart <= 0L) return
            val now = System.currentTimeMillis()
            prefs.edit()
                .putBoolean(runKey(runStart, "playing"), true)
                .putString(runKey(runStart, "pb_kind"), safeRouteName(kind))
                .putString(runKey(runStart, "pb_engine"), safeRouteName(engine))
                .putString(runKey(runStart, "pb_surface"), safeRouteName(surface))
                .putLong(runKey(runStart, "pb_at"), now)
                .putString(runKey(runStart, "action"), "playback")
                .putLong(runKey(runStart, "context_at"), now)
                .apply()
        }
        recordMemorySnapshot(context, "playback_started")
    }

    fun recordPlaybackStopped(context: Context) {
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val runStart = prefs.getLong(KEY_CURRENT_RUN_START, 0L)
            if (runStart <= 0L) return
            prefs.edit()
                .putBoolean(runKey(runStart, "playing"), false)
                .putLong(runKey(runStart, "context_at"), System.currentTimeMillis())
                .apply()
        }
        recordMemorySnapshot(context, "playback_stopped")
    }

    private fun beginRun(context: Context) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val oldStarts = parseRunStarts(prefs.getString(KEY_RUN_STARTS, null))
            val now = System.currentTimeMillis()
            // Avoid a key collision in the extremely unlikely event of two process starts within
            // the same wall-clock millisecond.
            val runStart = maxOf(now, (oldStarts.maxOrNull() ?: 0L) + 1L)
            val retainedStarts = (oldStarts + runStart).distinct().sorted().takeLast(MAX_RUN_CONTEXTS)
            val removedStarts = oldStarts.filterNot { it in retainedStarts }
            val edit = prefs.edit()
                .putString(KEY_RUN_STARTS, retainedStarts.joinToString(","))
                .putLong(KEY_CURRENT_RUN_START, runStart)
                .putString(runKey(runStart, "version"), BuildConfig.VERSION_NAME)
                .putLong(runKey(runStart, "build"), BuildConfig.VERSION_CODE.toLong())
            removedStarts.forEach { removed ->
                RUN_FIELDS.forEach { field -> edit.remove(runKey(removed, field)) }
            }
            edit.apply()
        }
    }

    private fun doReport(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = activityManager.getHistoricalProcessExitReasons(
            context.packageName,
            0,
            MAX_EXITS,
        )
        if (exits.isEmpty()) return

        val lastSeen = prefs.getLong(KEY_LAST_SEEN_TS, 0L)
        val seenIds = prefs.getStringSet(KEY_SEEN_EXIT_IDS, emptySet()).orEmpty().toMutableSet()
        val runContexts = readRunContexts(context)

        exits.asSequence()
            .filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
            .forEach { exit ->
                val reason = REPORTED_REASONS[exit.reason] ?: return@forEach
                val exitId = exitFingerprint(
                    timestamp = exit.timestamp,
                    reason = exit.reason,
                    status = exit.status,
                    processName = exit.processName,
                )
                if (exitId in seenIds) return@forEach

                val failedRun = findRunContext(exit.timestamp, runContexts)
                val properties = buildProperties(context, exit, reason, failedRun)
                PostHog.capture("app_exit", properties = properties)
                if (shouldPromoteProcessExitToIssue(reason, exit.importance)) {
                    captureExitIssue(reason, properties)
                }

                // Persist after each capture rather than once at the end, so another crash during
                // a crash-loop does not replay every earlier event on the next launch.
                seenIds += exitId
                val retainedIds = seenIds.sortedByDescending(::fingerprintTimestamp)
                    .take(MAX_SEEN_EXIT_IDS)
                    .toSet()
                prefs.edit()
                    .putStringSet(KEY_SEEN_EXIT_IDS, retainedIds)
                    .putLong(KEY_LAST_SEEN_TS, exit.timestamp)
                    .commit()
                seenIds.retainAll(retainedIds)
            }

        // Advance past normal lifecycle records too; they will never become reportable later.
        val newestTimestamp = exits.maxOf { it.timestamp }
        if (newestTimestamp > prefs.getLong(KEY_LAST_SEEN_TS, 0L)) {
            prefs.edit().putLong(KEY_LAST_SEEN_TS, newestTimestamp).commit()
        }
    }

    private fun buildProperties(
        context: Context,
        exit: ApplicationExitInfo,
        reason: String,
        failedRun: AppRunContext?,
    ): Map<String, Any> = buildMap {
        put("reason", reason)
        put("reason_code", exit.reason)
        put("status", exit.status)
        put("importance", exit.importance)
        put("importance_name", importanceName(exit.importance))
        put("pss_kb", exit.pss)
        put("rss_kb", exit.rss)
        put("package_name", context.packageName)
        put("package_uid", exit.packageUid)
        put("process_name", exit.processName)
        put("process_id", exit.pid)
        put("exit_timestamp_ms", exit.timestamp)

        sanitizeDiagnosticText(exit.description, MAX_DESCRIPTION_CHARS)?.let {
            put("description", it)
        }
        readTraceExcerpt(exit)?.let { put("trace_excerpt", it) }

        failedRun?.let { run ->
            put("failed_app_version", run.versionName)
            put("failed_app_build", run.versionCode)
            put("failed_run_started_at_ms", run.startedAtMs)
            run.lastRoute?.let { put("last_route", it) }
            run.lastAction?.let { put("last_action", it) }
            put("was_playing", run.wasPlaying)
            if (run.wasPlaying) {
                run.playbackKind?.let { put("playback_kind", it) }
                run.playbackEngine?.let { put("playback_engine", it) }
                run.playbackSurface?.let { put("playback_surface", it) }
                run.playbackStartedAtMs?.let {
                    put("playback_age_ms", (exit.timestamp - it).coerceAtLeast(0L))
                }
            }
            run.contextUpdatedAtMs?.let { updatedAt ->
                put("last_context_age_ms", (exit.timestamp - updatedAt).coerceAtLeast(0L))
            }
            run.memorySampledAtMs?.let { put("memory_sampled_at_ms", it) }
            run.memoryTrigger?.let { put("memory_sample_trigger", it) }
            run.memoryTrimLevel?.let { put("memory_trim_level", it) }
            run.lastPssKb?.let { put("last_sampled_pss_kb", it) }
            run.maxPssKb?.let { put("max_sampled_pss_kb", it) }
            run.lastJavaHeapKb?.let { put("last_java_heap_kb", it) }
            run.maxJavaHeapKb?.let { put("max_java_heap_kb", it) }
            run.lastNativeHeapKb?.let { put("last_native_heap_kb", it) }
            run.maxNativeHeapKb?.let { put("max_native_heap_kb", it) }
            run.processSampledAtMs?.let { put("process_sampled_at_ms", it) }
            run.processSampleTrigger?.let { put("process_sample_trigger", it) }
            run.processImportance?.let { put("sampled_app_importance", it) }
            run.processInventory?.let { put("uid_process_inventory", it) }
            run.processCount?.let { put("uid_process_count", it) }
            run.topCpuProcess?.let { put("top_cpu_process", it) }
            run.topCpuTicksDelta?.let { put("top_cpu_ticks_delta", it) }
            run.processSampleIntervalMs?.let { put("process_sample_interval_ms", it) }
            run.selfCpuTimeMs?.let { put("self_cpu_time_ms", it) }
            run.selfCpuDeltaMs?.let { put("self_cpu_delta_ms", it) }
            run.fdSampledAtMs?.let { put("fd_sampled_at_ms", it) }
            run.fdSampleTrigger?.let { put("fd_sample_trigger", it) }
            run.lastFdCount?.let { put("last_fd_count", it) }
            run.maxFdCount?.let { put("max_fd_count", it) }
            run.fdSoftLimit?.let { put("fd_soft_limit", it) }
            run.fdUtilizationPercent?.let { put("fd_utilization_percent", it) }
            run.fdInventory?.let { put("fd_inventory", it) }
            run.mpvInstanceId?.let { put("mpv_instance_id", it) }
            run.mpvLifecycleStage?.let { put("mpv_lifecycle_stage", it) }
            run.mpvLifecycleStageAtMs?.let { put("mpv_lifecycle_stage_at_ms", it) }
            run.mpvActiveInstances?.let { put("mpv_active_instances", it) }
            run.mpvWaitingInstances?.let { put("mpv_waiting_instances", it) }
            run.maxMpvActiveInstances?.let { put("max_mpv_active_instances", it) }
            run.mpvDestroyWaitMs?.let { put("mpv_destroy_wait_ms", it) }
            run.mpvLifecycleHistory?.let { put("mpv_lifecycle_history", it) }
        }
    }

    private fun readTraceExcerpt(exit: ApplicationExitInfo): String? = runCatching {
        exit.traceInputStream?.use { input ->
            sanitizeDiagnosticText(readDiagnosticExcerpt(input, MAX_TRACE_CHARS), MAX_TRACE_CHARS)
        }
    }.getOrNull()

    /**
     * ApplicationExitInfo failures are ordinary analytics events by default, so they never
     * populate PostHog's Error Tracking Issues page. Mirror each abnormal OS exit as a synthetic
     * exception with a stable fingerprint while retaining the richer `app_exit` event above.
     */
    private fun captureExitIssue(reason: String, properties: Map<String, Any>) {
        runCatching {
            PostHog.captureException(
                throwable = AndroidProcessExitException(reason),
                properties = properties + mapOf(
                    "\$exception_fingerprint" to processExitIssueFingerprint(reason),
                    "\$exception_level" to processExitIssueLevel(reason),
                    "diagnostic_source" to "application_exit_info",
                    "synthetic_process_exit" to true,
                ),
            )
        }
    }

    private fun readRunContexts(context: Context): List<AppRunContext> {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return parseRunStarts(prefs.getString(KEY_RUN_STARTS, null)).mapNotNull { startedAt ->
                val versionName = prefs.getString(runKey(startedAt, "version"), null)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                AppRunContext(
                    startedAtMs = startedAt,
                    versionName = versionName,
                    versionCode = prefs.getLong(runKey(startedAt, "build"), 0L),
                    lastRoute = prefs.getString(runKey(startedAt, "route"), null),
                    lastAction = prefs.getString(runKey(startedAt, "action"), null),
                    contextUpdatedAtMs = prefs.getLong(runKey(startedAt, "context_at"), 0L)
                        .takeIf { it > 0L },
                    wasPlaying = prefs.getBoolean(runKey(startedAt, "playing"), false),
                    playbackKind = prefs.getString(runKey(startedAt, "pb_kind"), null),
                    playbackEngine = prefs.getString(runKey(startedAt, "pb_engine"), null),
                    playbackSurface = prefs.getString(runKey(startedAt, "pb_surface"), null),
                    playbackStartedAtMs = prefs.getLong(runKey(startedAt, "pb_at"), 0L)
                        .takeIf { it > 0L },
                    memorySampledAtMs = prefs.getLong(runKey(startedAt, "mem_at"), 0L)
                        .takeIf { it > 0L },
                    memoryTrigger = prefs.getString(runKey(startedAt, "mem_trigger"), null),
                    memoryTrimLevel = prefs.getInt(runKey(startedAt, "mem_trim"), -1)
                        .takeIf { it >= 0 },
                    lastPssKb = prefs.getLong(runKey(startedAt, "mem_pss"), 0L)
                        .takeIf { it > 0L },
                    maxPssKb = prefs.getLong(runKey(startedAt, "mem_pss_max"), 0L)
                        .takeIf { it > 0L },
                    lastJavaHeapKb = prefs.getLong(runKey(startedAt, "mem_java"), 0L)
                        .takeIf { it > 0L },
                    maxJavaHeapKb = prefs.getLong(runKey(startedAt, "mem_java_max"), 0L)
                        .takeIf { it > 0L },
                    lastNativeHeapKb = prefs.getLong(runKey(startedAt, "mem_native"), 0L)
                        .takeIf { it > 0L },
                    maxNativeHeapKb = prefs.getLong(runKey(startedAt, "mem_native_max"), 0L)
                        .takeIf { it > 0L },
                    processSampledAtMs = prefs.getLong(runKey(startedAt, "proc_at"), 0L)
                        .takeIf { it > 0L },
                    processSampleTrigger = prefs.getString(runKey(startedAt, "proc_trigger"), null),
                    processImportance = prefs.getString(runKey(startedAt, "proc_importance"), null),
                    processInventory = prefs.getString(runKey(startedAt, "proc_inventory"), null),
                    processCount = prefs.getInt(runKey(startedAt, "proc_count"), -1)
                        .takeIf { it >= 0 },
                    topCpuProcess = prefs.getString(runKey(startedAt, "proc_top"), null),
                    topCpuTicksDelta = prefs.getLong(runKey(startedAt, "proc_top_delta"), -1L)
                        .takeIf { it >= 0L },
                    processSampleIntervalMs = prefs.getLong(runKey(startedAt, "proc_interval"), 0L)
                        .takeIf { it > 0L },
                    selfCpuTimeMs = prefs.getLong(runKey(startedAt, "self_cpu"), 0L)
                        .takeIf { it > 0L },
                    selfCpuDeltaMs = prefs.getLong(runKey(startedAt, "self_cpu_delta"), -1L)
                        .takeIf { it >= 0L },
                    fdSampledAtMs = prefs.getLong(runKey(startedAt, "fd_at"), 0L)
                        .takeIf { it > 0L },
                    fdSampleTrigger = prefs.getString(runKey(startedAt, "fd_trigger"), null),
                    lastFdCount = prefs.getInt(runKey(startedAt, "fd_total"), -1)
                        .takeIf { it >= 0 },
                    maxFdCount = prefs.getInt(runKey(startedAt, "fd_max"), -1)
                        .takeIf { it >= 0 },
                    fdSoftLimit = prefs.getLong(runKey(startedAt, "fd_limit"), 0L)
                        .takeIf { it > 0L },
                    fdUtilizationPercent = prefs.getFloat(runKey(startedAt, "fd_pct"), -1f)
                        .takeIf { it >= 0f }
                        ?.toDouble(),
                    fdInventory = prefs.getString(runKey(startedAt, "fd_inventory"), null),
                    mpvInstanceId = prefs.getLong(runKey(startedAt, "mpv_id"), 0L)
                        .takeIf { it > 0L },
                    mpvLifecycleStage = prefs.getString(runKey(startedAt, "mpv_stage"), null),
                    mpvLifecycleStageAtMs = prefs.getLong(runKey(startedAt, "mpv_stage_at"), 0L)
                        .takeIf { it > 0L },
                    mpvActiveInstances = prefs.getInt(runKey(startedAt, "mpv_active"), -1)
                        .takeIf { it >= 0 },
                    mpvWaitingInstances = prefs.getInt(runKey(startedAt, "mpv_waiting"), -1)
                        .takeIf { it >= 0 },
                    maxMpvActiveInstances = prefs.getInt(runKey(startedAt, "mpv_active_max"), -1)
                        .takeIf { it >= 0 },
                    mpvDestroyWaitMs = prefs.getLong(runKey(startedAt, "mpv_destroy_wait"), -1L)
                        .takeIf { it >= 0L },
                    mpvLifecycleHistory = prefs.getString(runKey(startedAt, "mpv_history"), null),
                )
            }
        }
    }

    /** Samples off the main thread and keeps only the latest and peak values for this run. */
    fun recordMemorySnapshot(context: Context, trigger: String, trimLevel: Int? = null) {
        val appContext = context.applicationContext
        diagnosticsSampler.execute {
            runCatching {
                val runtime = Runtime.getRuntime()
                val javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1_024L
                val nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1_024L
                val pssKb = Debug.getPss()
                synchronized(lock) {
                    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val runStart = prefs.getLong(KEY_CURRENT_RUN_START, 0L)
                    if (runStart <= 0L) return@runCatching
                    prefs.edit()
                        .putLong(runKey(runStart, "mem_at"), System.currentTimeMillis())
                        .putString(runKey(runStart, "mem_trigger"), safeRouteName(trigger))
                        .putInt(runKey(runStart, "mem_trim"), trimLevel ?: -1)
                        .putLong(runKey(runStart, "mem_pss"), pssKb)
                        .putLong(
                            runKey(runStart, "mem_pss_max"),
                            maxOf(pssKb, prefs.getLong(runKey(runStart, "mem_pss_max"), 0L)),
                        )
                        .putLong(runKey(runStart, "mem_java"), javaHeapKb)
                        .putLong(
                            runKey(runStart, "mem_java_max"),
                            maxOf(javaHeapKb, prefs.getLong(runKey(runStart, "mem_java_max"), 0L)),
                        )
                        .putLong(runKey(runStart, "mem_native"), nativeHeapKb)
                        .putLong(
                            runKey(runStart, "mem_native_max"),
                            maxOf(nativeHeapKb, prefs.getLong(runKey(runStart, "mem_native_max"), 0L)),
                        )
                        .apply()
                }
            }
        }
    }

    /**
     * Persists a lifecycle breadcrumb together with a privacy-safe descriptor inventory. Normal
     * stages stay local and are attached to a later `app_exit`; teardown failures are also emitted
     * immediately because they prove a native core may still own resources after its view is gone.
     */
    fun recordMpvLifecycle(
        context: Context,
        instanceId: Long,
        stage: String,
        activeInstances: Int,
        waitingInstances: Int,
        peakActiveInstances: Int,
        destroyWaitMs: Long? = null,
    ) {
        val appContext = context.applicationContext
        val safeStage = safeRouteName(stage) ?: "unknown"
        diagnosticsSampler.execute {
            runCatching {
                val now = System.currentTimeMillis()
                val fdSnapshot = readFileDescriptorSnapshot()
                synchronized(lock) {
                    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val runStart = prefs.getLong(KEY_CURRENT_RUN_START, 0L)
                    if (runStart <= 0L) return@runCatching
                    val edit = prefs.edit()
                        .putLong(runKey(runStart, "mpv_id"), instanceId)
                        .putString(runKey(runStart, "mpv_stage"), safeStage)
                        .putLong(runKey(runStart, "mpv_stage_at"), now)
                        .putInt(runKey(runStart, "mpv_active"), activeInstances)
                        .putInt(runKey(runStart, "mpv_waiting"), waitingInstances)
                        .putInt(
                            runKey(runStart, "mpv_active_max"),
                            maxOf(
                                peakActiveInstances,
                                prefs.getInt(runKey(runStart, "mpv_active_max"), 0),
                            ),
                        )
                        .putLong(runKey(runStart, "mpv_destroy_wait"), destroyWaitMs ?: -1L)
                        .putString(
                            runKey(runStart, "mpv_history"),
                            appendMpvLifecycleHistory(
                                existing = prefs.getString(runKey(runStart, "mpv_history"), null),
                                timestampMs = now,
                                instanceId = instanceId,
                                stage = safeStage,
                                activeInstances = activeInstances,
                            ),
                        )
                    fdSnapshot?.let { snapshot ->
                        edit.putFileDescriptorSnapshot(
                            runStart = runStart,
                            trigger = "mpv_$safeStage",
                            snapshot = snapshot,
                            previousMax = prefs.getInt(runKey(runStart, "fd_max"), 0),
                            sampledAtMs = now,
                        )
                    }
                    edit.apply()
                }
                LogMpvLifecycle.write(
                    instanceId = instanceId,
                    stage = safeStage,
                    activeInstances = activeInstances,
                    waitingInstances = waitingInstances,
                    fdSnapshot = fdSnapshot,
                    destroyWaitMs = destroyWaitMs,
                )
                if (safeStage in MPV_WARNING_STAGES) {
                    PostHog.capture(
                        "mpv_lifecycle_warning",
                        properties = buildMap {
                            put("mpv_instance_id", instanceId)
                            put("mpv_lifecycle_stage", safeStage)
                            put("mpv_active_instances", activeInstances)
                            put("mpv_waiting_instances", waitingInstances)
                            destroyWaitMs?.let { put("mpv_destroy_wait_ms", it) }
                            fdSnapshot?.let { snapshot ->
                                put("fd_count", snapshot.total)
                                put("fd_inventory", snapshot.inventory())
                                snapshot.softLimit?.let { put("fd_soft_limit", it) }
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Keeps a tiny rolling inventory of this app UID's processes. Android's excessive-CPU exit
     * description says only "child process"; this breadcrumb identifies which named process was
     * alive and accumulating the most CPU immediately before the OS killed the app.
     */
    private fun startProcessSampling(context: Context) {
        if (!processSamplerStarted.compareAndSet(false, true)) return
        diagnosticsSampler.scheduleAtFixedRate(
            { runCatching { recordProcessSnapshot(context, "periodic") } },
            0L,
            PROCESS_SAMPLE_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun recordProcessSnapshot(context: Context, trigger: String) {
        val now = System.currentTimeMillis()
        val fdSnapshot = readFileDescriptorSnapshot()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = activityManager.runningAppProcesses.orEmpty()
            .asSequence()
            .filter { it.uid == android.os.Process.myUid() }
            .map { process ->
                ProcessCpuSample(
                    pid = process.pid,
                    name = safeProcessName(context.packageName, process.processName),
                    importance = process.importance,
                    cpuTicks = readProcessCpuTicks(process.pid),
                )
            }
            .sortedBy { it.name }
            .toList()
        val selfCpuMs = android.os.Process.getElapsedCpuTime()
        val current = ProcessInventorySample(now, selfCpuMs, processes)
        val previous = previousProcessSample
        previousProcessSample = current
        val deltas = processCpuDeltas(previous, current)
        val top = deltas.maxByOrNull { it.second }
        val selfDelta = previous?.let { (selfCpuMs - it.selfCpuTimeMs).coerceAtLeast(0L) }
        val interval = previous?.let { (now - it.sampledAtMs).coerceAtLeast(0L) }
        val ownImportance = processes.firstOrNull { it.pid == android.os.Process.myPid() }?.importance

        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val runStart = prefs.getLong(KEY_CURRENT_RUN_START, 0L)
            if (runStart <= 0L) return
            val edit = prefs.edit()
                .putLong(runKey(runStart, "proc_at"), now)
                .putString(runKey(runStart, "proc_trigger"), safeRouteName(trigger))
                .putString(runKey(runStart, "proc_importance"), ownImportance?.let(::importanceName))
                .putString(runKey(runStart, "proc_inventory"), formatProcessInventory(processes))
                .putInt(runKey(runStart, "proc_count"), processes.size)
                .putString(runKey(runStart, "proc_top"), top?.first)
                .putLong(runKey(runStart, "proc_top_delta"), top?.second ?: -1L)
                .putLong(runKey(runStart, "proc_interval"), interval ?: 0L)
                .putLong(runKey(runStart, "self_cpu"), selfCpuMs)
                .putLong(runKey(runStart, "self_cpu_delta"), selfDelta ?: -1L)
            fdSnapshot?.let { snapshot ->
                edit.putFileDescriptorSnapshot(
                    runStart = runStart,
                    trigger = trigger,
                    snapshot = snapshot,
                    previousMax = prefs.getInt(runKey(runStart, "fd_max"), 0),
                    sampledAtMs = now,
                )
            }
            edit.apply()
        }
    }

    private fun android.content.SharedPreferences.Editor.putFileDescriptorSnapshot(
        runStart: Long,
        trigger: String,
        snapshot: FileDescriptorSnapshot,
        previousMax: Int,
        sampledAtMs: Long,
    ): android.content.SharedPreferences.Editor = this
        .putLong(runKey(runStart, "fd_at"), sampledAtMs)
        .putString(runKey(runStart, "fd_trigger"), safeRouteName(trigger))
        .putInt(runKey(runStart, "fd_total"), snapshot.total)
        .putInt(runKey(runStart, "fd_max"), maxOf(snapshot.total, previousMax))
        .putLong(runKey(runStart, "fd_limit"), snapshot.softLimit ?: 0L)
        .putFloat(runKey(runStart, "fd_pct"), snapshot.utilizationPercent?.toFloat() ?: -1f)
        .putString(runKey(runStart, "fd_inventory"), snapshot.inventory())

    private fun runKey(startedAt: Long, field: String): String = "run.$startedAt.$field"

    private val RUN_FIELDS = listOf(
        "version", "build", "route", "action", "context_at",
        "playing", "pb_kind", "pb_engine", "pb_surface", "pb_at",
        "mem_at", "mem_trigger", "mem_trim", "mem_pss", "mem_pss_max",
        "mem_java", "mem_java_max", "mem_native", "mem_native_max",
        "proc_at", "proc_trigger", "proc_importance", "proc_inventory", "proc_count",
        "proc_top", "proc_top_delta", "proc_interval", "self_cpu", "self_cpu_delta",
        "fd_at", "fd_trigger", "fd_total", "fd_max", "fd_limit", "fd_pct", "fd_inventory",
        "mpv_id", "mpv_stage", "mpv_stage_at", "mpv_active", "mpv_waiting",
        "mpv_active_max", "mpv_destroy_wait", "mpv_history",
    )

    private val MPV_WARNING_STAGES = setOf(
        "destroy_timeout",
        "destroy_failed",
        "destroy_queue_failed",
    )
}

internal data class AppRunContext(
    val startedAtMs: Long,
    val versionName: String,
    val versionCode: Long,
    val lastRoute: String?,
    val lastAction: String?,
    val contextUpdatedAtMs: Long?,
    val wasPlaying: Boolean = false,
    val playbackKind: String? = null,
    val playbackEngine: String? = null,
    val playbackSurface: String? = null,
    val playbackStartedAtMs: Long? = null,
    val memorySampledAtMs: Long? = null,
    val memoryTrigger: String? = null,
    val memoryTrimLevel: Int? = null,
    val lastPssKb: Long? = null,
    val maxPssKb: Long? = null,
    val lastJavaHeapKb: Long? = null,
    val maxJavaHeapKb: Long? = null,
    val lastNativeHeapKb: Long? = null,
    val maxNativeHeapKb: Long? = null,
    val processSampledAtMs: Long? = null,
    val processSampleTrigger: String? = null,
    val processImportance: String? = null,
    val processInventory: String? = null,
    val processCount: Int? = null,
    val topCpuProcess: String? = null,
    val topCpuTicksDelta: Long? = null,
    val processSampleIntervalMs: Long? = null,
    val selfCpuTimeMs: Long? = null,
    val selfCpuDeltaMs: Long? = null,
    val fdSampledAtMs: Long? = null,
    val fdSampleTrigger: String? = null,
    val lastFdCount: Int? = null,
    val maxFdCount: Int? = null,
    val fdSoftLimit: Long? = null,
    val fdUtilizationPercent: Double? = null,
    val fdInventory: String? = null,
    val mpvInstanceId: Long? = null,
    val mpvLifecycleStage: String? = null,
    val mpvLifecycleStageAtMs: Long? = null,
    val mpvActiveInstances: Int? = null,
    val mpvWaitingInstances: Int? = null,
    val maxMpvActiveInstances: Int? = null,
    val mpvDestroyWaitMs: Long? = null,
    val mpvLifecycleHistory: String? = null,
)

private object LogMpvLifecycle {
    fun write(
        instanceId: Long,
        stage: String,
        activeInstances: Int,
        waitingInstances: Int,
        fdSnapshot: FileDescriptorSnapshot?,
        destroyWaitMs: Long?,
    ) {
        android.util.Log.i(
            "MpvLifecycle",
            "id=$instanceId stage=$stage active=$activeInstances waiting=$waitingInstances " +
                "fds=${fdSnapshot?.total ?: -1}/${fdSnapshot?.softLimit ?: -1} " +
                "inventory=${fdSnapshot?.inventory().orEmpty()} waitMs=${destroyWaitMs ?: -1}",
        )
    }
}

internal data class ProcessCpuSample(
    val pid: Int,
    val name: String,
    val importance: Int,
    val cpuTicks: Long?,
)

private data class ProcessInventorySample(
    val sampledAtMs: Long,
    val selfCpuTimeMs: Long,
    val processes: List<ProcessCpuSample>,
)

internal fun safeProcessName(packageName: String, processName: String): String =
    processName.removePrefix(packageName).ifBlank { "main" }.take(64)

internal fun formatProcessInventory(processes: List<ProcessCpuSample>): String = processes
    .joinToString(",") { "${it.name}:${importanceName(it.importance)}:${it.pid}" }
    .take(512)

private fun processCpuDeltas(
    previous: ProcessInventorySample?,
    current: ProcessInventorySample,
): List<Pair<String, Long>> {
    if (previous == null) return emptyList()
    val oldTicks = previous.processes.associate { it.pid to it.cpuTicks }
    return current.processes.mapNotNull { process ->
        val before = oldTicks[process.pid] ?: return@mapNotNull null
        val after = process.cpuTicks ?: return@mapNotNull null
        process.name to (after - before).coerceAtLeast(0L)
    }
}

/** Reads Linux /proc stat fields 14+15 (user and kernel CPU ticks) for an app-owned process. */
internal fun parseProcessCpuTicks(stat: String): Long? {
    val fields = stat.substringAfterLast(')', missingDelimiterValue = "").trim().split(Regex("\\s+"))
    if (fields.size <= 12) return null
    return fields[11].toLongOrNull()?.plus(fields[12].toLongOrNull() ?: return null)
}

private fun readProcessCpuTicks(pid: Int): Long? = runCatching {
    java.io.File("/proc/$pid/stat").bufferedReader().use { parseProcessCpuTicks(it.readLine()) }
}.getOrNull()

internal fun findRunContext(exitTimestamp: Long, runs: List<AppRunContext>): AppRunContext? =
    runs.asSequence()
        .filter { it.startedAtMs <= exitTimestamp }
        .maxByOrNull { it.startedAtMs }

internal fun parseRunStarts(value: String?): List<Long> = value.orEmpty()
    .split(',')
    .mapNotNull { it.toLongOrNull() }
    .filter { it > 0L }
    .distinct()
    .sorted()

internal fun safeRouteName(route: String?): String? = route
    ?.substringBefore('?')
    ?.substringBefore('/')
    ?.trim()
    ?.lowercase(Locale.US)
    ?.replace(Regex("[^a-z0-9_-]"), "_")
    ?.take(64)
    ?.takeIf { it.isNotBlank() }

internal fun exitFingerprint(
    timestamp: Long,
    reason: Int,
    status: Int,
    processName: String,
): String = "$timestamp|$reason|$status|$processName"

private fun fingerprintTimestamp(value: String): Long = value.substringBefore('|').toLongOrNull() ?: 0L

internal fun readBoundedText(input: InputStream, maxChars: Int): String {
    if (maxChars <= 0) return ""
    return readBoundedBytes(input, maxChars).toString(Charsets.UTF_8).take(maxChars)
}

private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
    if (maxBytes <= 0) return ByteArray(0)
    val result = java.io.ByteArrayOutputStream(minOf(maxBytes, 1_024))
    val buffer = ByteArray(minOf(1_024, maxBytes))
    while (result.size() < maxBytes) {
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - result.size()))
        if (count < 0) break
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

/** Native tombstones are protobuf on newer Android versions; retain their printable strings. */
internal fun readDiagnosticExcerpt(input: InputStream, maxChars: Int): String {
    val bytes = readBoundedBytes(input, maxChars * 4)
    if (bytes.none { it == 0.toByte() }) return bytes.toString(Charsets.UTF_8).take(maxChars)
    return Regex("[\\x20-\\x7E]{4,}")
        .findAll(bytes.toString(Charsets.ISO_8859_1))
        .joinToString("\n") { it.value }
        .take(maxChars)
}

private val URL_PATTERN = Regex("(?i)\\b(?:https?|rtsp|rtmp|udp|file)://[^\\s\\\"'<>]+")
private val EMAIL_PATTERN = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
private val SECRET_ASSIGNMENT_PATTERN = Regex(
    "(?i)\\b(authorization|password|passwd|token|api[_-]?key|secret|username|mac)" +
        "\\s*[:=]\\s*[^\\r\\n,;]+"
)
private val LONG_TOKEN_PATTERN = Regex("(?<![A-Za-z0-9_])[A-Za-z0-9_=-]{40,}(?![A-Za-z0-9_])")

internal fun sanitizeDiagnosticText(value: String?, maxChars: Int): String? {
    if (value.isNullOrBlank() || maxChars <= 0) return null
    val withoutControls = buildString(value.length) {
        value.forEach { char ->
            if (char == '\n' || char == '\t' || !char.isISOControl()) append(char)
        }
    }
    return withoutControls
        .replace(URL_PATTERN, "[redacted_url]")
        .replace(EMAIL_PATTERN, "[redacted_email]")
        .replace(SECRET_ASSIGNMENT_PATTERN) { match ->
            "${match.groupValues[1]}=[redacted]"
        }
        .replace(LONG_TOKEN_PATTERN, "[redacted_token]")
        .take(maxChars)
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun importanceName(importance: Int): String = when (importance) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground_service"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
    else -> "unknown"
}

internal fun processExitIssueFingerprint(reason: String): String = "android_process_exit:$reason"

internal fun shouldPromoteProcessExitToIssue(reason: String, importance: Int): Boolean =
    !(reason == "low_memory_kill" &&
        importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)

internal fun processExitIssueLevel(reason: String): String = when (reason) {
    "low_memory_kill", "excessive_resource_usage" -> "warning"
    else -> "fatal"
}

private class AndroidProcessExitException(reason: String) :
    RuntimeException("Android process exited abnormally: $reason")

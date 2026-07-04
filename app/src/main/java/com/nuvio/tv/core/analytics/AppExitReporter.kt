package com.nuvio.tv.core.analytics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.posthog.PostHog

/**
 * Reports why the app last died, from the OS's own exit records (API 30+), as
 * PostHog `app_exit` events. Covers what the SDK's uncaught-exception handler
 * can never see — native crashes, ANRs, OOM kills — and survives crash-loops,
 * because the report goes out on the next launch that lives long enough to
 * flush.
 */
object AppExitReporter {

    private const val PREFS = "app_exit_reporter"
    private const val KEY_LAST_SEEN_TS = "last_seen_exit_ts"
    private const val MAX_EXITS = 16

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
        // Binder call + prefs off the main thread; must never affect startup.
        Thread({ runCatching { doReport(context.applicationContext) } }, "AppExitReporter").start()
    }

    private fun doReport(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN_TS, 0L)
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits =
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
        if (exits.isEmpty()) return
        exits.filter { it.timestamp > lastSeen }.forEach { exit ->
            val reason = REPORTED_REASONS[exit.reason] ?: return@forEach
            PostHog.capture(
                "app_exit",
                properties = mapOf(
                    "reason" to reason,
                    "reason_code" to exit.reason,
                    "description" to (exit.description ?: ""),
                    "process_name" to exit.processName,
                    "exit_timestamp_ms" to exit.timestamp,
                )
            )
        }
        prefs.edit().putLong(KEY_LAST_SEEN_TS, exits.maxOf { it.timestamp }).apply()
    }
}

package com.nuvio.tv.core.analytics

/**
 * Decides whether an [android.app.ApplicationExitInfo] record is worth reporting to analytics, or is
 * normal OS lifecycle that would skew crash metrics.
 *
 * The problem this fixes: on low-RAM Android TV boxes, the OS constantly SIGKILLs Tuvora's *cached*
 * (backgrounded) process to reclaim memory when the user switches to another app. Android surfaces
 * that as `REASON_SIGNALED` + `SIGKILL` (status 9) with a background importance — and on devices that
 * don't report `REASON_LOW_MEMORY` it's the ONLY way a memory reclaim shows up. Per Android's docs
 * this is normal resource management, not a crash, yet it was ~89% of our `app_exit` volume, drowning
 * the real signal.
 *
 * Rule (mirrors Google Play vitals' "user-perceived crash" concept):
 *  - App faults — crash / native_crash / anr / initialization_failure — ALWAYS report: a code fault
 *    matters regardless of whether the app was visible.
 *  - OS-initiated resource reclaim — signaled / low_memory_kill / excessive_resource_usage — is only
 *    user-perceived (and thus worth reporting) when the process was still USER-VISIBLE when it died.
 *    A cached/background kill is normal and is dropped.
 *
 * Pure + unit-tested: no Android types, just the reason string + the raw importance int.
 */
object AppExitReportPolicy {
    /**
     * Max `ActivityManager.RunningAppProcessInfo` importance that still counts as user-visible.
     * Foreground=100, foreground-service=125, top-sleeping=150, visible=200, perceptible=230,
     * cant-save-state=270 are user-visible; service=300, cached=400, gone=1000 are background.
     * Lower = more foreground. Kept as a literal so it compiles on every API level.
     */
    const val USER_VISIBLE_IMPORTANCE_MAX = 270

    private val APP_FAULT = setOf("crash", "native_crash", "anr", "initialization_failure")
    private val OS_RECLAIM = setOf("signaled", "low_memory_kill", "excessive_resource_usage")

    /** @param importance the raw `ApplicationExitInfo.getImportance()` value (0/unknown when unset). */
    fun shouldReport(reason: String, importance: Int): Boolean = when (reason) {
        in APP_FAULT -> true
        in OS_RECLAIM -> importance in 1..USER_VISIBLE_IMPORTANCE_MAX
        else -> false
    }
}

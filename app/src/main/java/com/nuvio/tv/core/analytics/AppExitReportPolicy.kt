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

    /** What the reporter does with one exit record. */
    sealed interface Outcome {
        /** Emit `app_exit`; [promote] also files a PostHog issue. */
        data class Report(val promote: Boolean) : Outcome

        /** Our own deliberate exit: counted in session_health under [tag], never an issue. */
        data class CountOnly(val tag: String) : Outcome

        /** Normal OS lifecycle (cached reclaim): counted by the send policy, nothing else. */
        data object Drop : Outcome
    }

    /** `ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED`, as a literal so this stays Android-free. */
    private const val IMPORTANCE_CACHED = 400

    /**
     * @param selfExitTag the tag of a self-exit marker already matched to THIS exit record (pid and time), or null.
     */
    fun classify(reason: String, importance: Int, selfExitTag: String?): Outcome {
        // Only a SIGKILL record can be our own kill; a tag never excuses a crash, ANR or OS reclaim.
        if (reason == "signaled" && selfExitTag != null) return Outcome.CountOnly(selfExitTag)
        if (!shouldReport(reason, importance)) return Outcome.Drop
        val cachedLowMemory = reason == "low_memory_kill" && importance == IMPORTANCE_CACHED
        return Outcome.Report(promote = !cachedLowMemory)
    }
}

/**
 * A durable note, written right before the process kills ITSELF on purpose (user Exit, restart, icon change), that
 * lets the next launch tell our own SIGKILL apart from the OS killing us. Pure: no Android types.
 *
 * Wire form `tag|pid|atMs`. The match is by the dead process's pid AND a short time window, so a stale marker can
 * never excuse a later real kill (a reused pid after reboot, or a kill hours later, fails the window).
 */
data class SelfExitMarker(val tag: String, val pid: Int, val atMs: Long) {
    companion object {
        const val USER_EXIT = "user_exit"
        const val RESTART = "restart"
        const val ICON_CHANGE = "icon_change"
        private val ALLOWED = setOf(USER_EXIT, RESTART, ICON_CHANGE)

        /** Max gap between writing the marker and the OS stamping the exit record. */
        const val MATCH_WINDOW_MS = 15_000L

        fun encode(tag: String, pid: Int, atMs: Long): String? =
            if (tag in ALLOWED && pid > 0 && atMs > 0L) "$tag|$pid|$atMs" else null

        fun decode(raw: String?): SelfExitMarker? {
            val parts = raw?.takeIf { it.length <= 64 }?.split('|') ?: return null
            if (parts.size != 3 || parts[0] !in ALLOWED) return null
            val pid = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val at = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
            return SelfExitMarker(parts[0], pid, at)
        }

        /**
         * Whether the exit reporter may delete the stored marker once its pass ends.
         *
         * @param readAtStart the raw marker read when the report pass began
         * @param current the raw marker stored now
         * @param reportCompleted false when the exit-history query threw (nothing was consumed)
         */
        fun shouldClearAfterReport(readAtStart: String?, current: String?, reportCompleted: Boolean, myPid: Int): Boolean =
            // Compare-and-clear: only the exact value this pass read (and consumed) may go. A marker written meanwhile
            // (Exit pressed mid-report) or one this very process wrote must survive for the next launch.
            reportCompleted && current != null && current == readAtStart && decode(readAtStart)?.pid != myPid

        /** The tag when [marker] explains an exit of [exitPid] stamped [exitTimestampMs], else null. */
        fun tagFor(marker: SelfExitMarker?, exitPid: Int, exitTimestampMs: Long): String? {
            if (marker == null || marker.pid != exitPid) return null
            val gap = exitTimestampMs - marker.atMs
            // Small negative slack: the exit record is stamped by system_server, the marker by this process.
            return if (gap in -1_000L..MATCH_WINDOW_MS) marker.tag else null
        }
    }
}

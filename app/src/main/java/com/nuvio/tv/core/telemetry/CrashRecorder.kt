package com.nuvio.tv.core.telemetry

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.posthog.PostHog
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * Writes a [CrashRecord] synchronously when the process is about to die from an uncaught
 * exception, then hands the crash on unchanged. The next launch sends it as `app_crash_report`
 * (with the crash time).
 *
 * The handler uses no coroutines, DI or locks that other threads could hold, and never throws.
 *
 * Privacy: exception MESSAGE text is never recorded or sent (a failed parse of a provider reply can
 * embed credentials or hostnames in it). Only the exception type per cause and the stack frames go out.
 *
 * Delivery: at least once. The record is moved to `sending.json` while it is sent and deleted only
 * after the app has kept running [CONFIRM_AFTER_MS] and flushed again, because PostHog writes its
 * queue to disk asynchronously. A kill in between leaves the file, and the next launch resends the
 * same `crash_id`, which queries dedupe.
 */
object CrashRecorder {
    private const val TAG = "CrashRecorder"
    private const val DIR = "telemetry"
    private const val PENDING = "pending_crash.json"
    private const val SENDING = "sending.json"
    private const val CONFIRM_AFTER_MS = 90_000L
    const val EVENT = "app_crash_report"

    fun install(context: Context, appVersion: String) {
        val dir = File(context.filesDir, DIR)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Consent is checked when the crash happens, not only when it is sent: a crash from
                // while reports were off must not be sent after they are turned on.
                if (TelemetryHolder.consentEnabled) write(dir, record(thread, error, appVersion))
            } catch (_: Throwable) {
                // Never let crash capture replace the real crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun record(thread: Thread, error: Throwable, appVersion: String) = CrashRecord(
        crashId = UUID.randomUUID().toString(),
        crashedAtMs = System.currentTimeMillis(),
        appVersion = appVersion,
        thread = thread.name,
        exceptionType = error.javaClass.name,
        message = null,
        frames = CrashRecord.framesOf(error),
        // Never blocks the dying process: an empty trail beats a hang.
        trail = runCatching {
            DiagTrailPolicy.render(TelemetryHolder.recorder?.snapshotOrEmpty(TelemetryGate.TRAIL_ON_REPORT).orEmpty(), TelemetryGate.TRAIL_ON_REPORT)
        }.getOrDefault(""),
    )

    /** Atomic: a crash mid-write leaves the previous file or none, never half a file. */
    internal fun write(dir: File, record: CrashRecord) {
        dir.mkdirs()
        val tmp = File(dir, "$PENDING.tmp")
        tmp.writeText(record.toJson())
        tmp.renameTo(File(dir, PENDING))
    }

    /** Call off Main, after PostHog setup and only with consent. Sends at most one record. */
    fun sendPending(context: Context, supportCode: String) {
        val dir = File(context.filesDir, DIR)
        val sending = File(dir, SENDING)
        val pending = File(dir, PENDING)
        // A leftover `sending.json` is a crash we are not sure arrived: resend it first (same id).
        val file = when {
            sending.exists() -> sending
            pending.exists() -> if (pending.renameTo(sending)) sending else pending
            else -> return
        }
        val record = runCatching { CrashRecord.fromJson(file.readText()) }.getOrNull()
        if (record == null) {
            file.delete()
            return
        }
        capture(record, supportCode)
        Log.i(TAG, "sent crash ${record.crashId}; confirming in ${CONFIRM_AFTER_MS / 1000}s")
        Handler(Looper.getMainLooper()).postDelayed({
            Thread({
                runCatching { PostHog.flush() }
                file.delete()
            }, "crash-confirm").start()
        }, CONFIRM_AFTER_MS)
    }

    private fun capture(record: CrashRecord, supportCode: String) {
        PostHog.capture(
            event = EVENT,
            properties = mapOf(
                "schema_version" to CrashRecord.SCHEMA_VERSION,
                "crash_id" to record.crashId,
                "support_code" to supportCode,
                "crashed_at_ms" to record.crashedAtMs,
                "crashed_app_version" to record.appVersion,
                "thread" to record.thread,
                "exception_type" to record.exceptionType,
                "stack" to record.frames.joinToString("\n"),
                "diag_trail" to record.trail,
            ),
            timestamp = Date(record.crashedAtMs),
        )
        PostHog.flush()
    }
}

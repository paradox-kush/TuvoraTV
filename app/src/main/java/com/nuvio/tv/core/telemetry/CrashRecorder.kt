package com.nuvio.tv.core.telemetry

import android.content.Context
import android.util.Log
import com.posthog.PostHog
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * Writes a [CrashRecord] synchronously when the process is about to die from an uncaught
 * exception, then hands the crash on unchanged. The next launch sends it once as
 * `app_crash_report` (with the crash time) and deletes the file.
 *
 * The handler uses no coroutines, DI or locks that other threads could hold, and never throws.
 */
object CrashRecorder {
    private const val TAG = "CrashRecorder"
    private const val DIR = "telemetry"
    private const val PENDING = "pending_crash.json"
    const val EVENT = "app_crash_report"

    fun install(context: Context, appVersion: String) {
        val dir = File(context.filesDir, DIR)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(dir, record(thread, error, appVersion))
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
        message = error.message?.take(CrashRecord.MAX_MESSAGE),
        frames = CrashRecord.framesOf(error),
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
        val file = File(File(context.filesDir, DIR), PENDING)
        if (!file.exists()) return
        val record = runCatching { CrashRecord.fromJson(file.readText()) }.getOrNull()
        if (record == null) {
            file.delete()
            return
        }
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
                "message" to (record.message ?: ""),
                "stack" to record.frames.joinToString("\n"),
            ),
            timestamp = Date(record.crashedAtMs),
        )
        PostHog.flush()
        // PostHog's queue is file-backed; the capture is persisted by the SDK, so the record
        // can go. A rare double send is deduped by crash_id in queries.
        file.delete()
        Log.i(TAG, "sent pending crash ${record.crashId}")
    }
}

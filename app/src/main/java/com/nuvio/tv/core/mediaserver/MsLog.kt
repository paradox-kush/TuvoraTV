package com.nuvio.tv.core.mediaserver

import android.util.Log
import com.nuvio.tv.core.diagnostics.LogRedaction

/**
 * The media-server code's logger (the KMP twin uses Kermit): `MsLog.withTag("X")` then `log.w { "..." }`.
 * Every message passes through [LogRedaction] (server addresses, tokens, MediaBrowser headers never reach logcat
 * or crash breadcrumbs in clear), and the lazy lambda is only evaluated when the level is loggable.
 */
internal class MsLog private constructor(private val tag: String) {
    inline fun d(message: () -> String) = emit(Log.DEBUG, null, message)
    inline fun i(message: () -> String) = emit(Log.INFO, null, message)
    inline fun w(message: () -> String) = emit(Log.WARN, null, message)
    inline fun w(throwable: Throwable, message: () -> String) = emit(Log.WARN, throwable, message)
    inline fun e(message: () -> String) = emit(Log.ERROR, null, message)
    inline fun e(throwable: Throwable, message: () -> String) = emit(Log.ERROR, throwable, message)

    @PublishedApi
    internal inline fun emit(level: Int, throwable: Throwable?, message: () -> String) {
        if (!safeLoggable(level)) return
        write(level, LogRedaction.text(message()), throwable)
    }

    @PublishedApi
    internal fun safeLoggable(level: Int): Boolean = try { Log.isLoggable(tag, level) || level >= Log.INFO } catch (_: Throwable) { false }

    @PublishedApi
    internal fun write(level: Int, text: String, throwable: Throwable?) {
        try {
            // Only the exception CLASS: an engine exception message can embed the URL it was fetching.
            val line = if (throwable != null) "$text (${throwable::class.simpleName})" else text
            Log.println(level, tag, line)
        } catch (_: Throwable) {
            // android.util.Log is not mocked in plain JVM unit tests: logging never fails a caller
        }
    }

    companion object {
        fun withTag(tag: String): MsLog = MsLog("Ms$tag".take(23))
    }
}

package com.nuvio.tv.core.recordings.internal

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Who is handing a schedule to [RecordingService]. */
internal enum class RecordingStartOrigin {
    /** An alarm fired while the app may be in the background (the exact-alarm FGS exemption applies). */
    ALARM,
    /** App start from the first onActivityStarted: an Activity is visible, so a plain start is allowed. */
    VISIBLE_APP,
}

/**
 * How the recording service is started (B286). startForegroundService obliges the service to reach
 * startForeground within seconds or Android kills the whole app (ForegroundServiceDidNotStartInTimeException)
 * — and stopping it before then crashes too. A slow cold start (budget box, main thread busy composing)
 * missed that window at every launch, and the overdue recording that triggered it stayed overdue, so the
 * app crash-looped. Only the background alarm path needs the foreground start; the visible app uses a
 * plain start, and the service still promotes itself to the foreground first thing. Twin: NuvioMobile.
 */
internal object RecordingServiceLaunch {
    fun needsForegroundStart(origin: RecordingStartOrigin): Boolean = origin == RecordingStartOrigin.ALARM

    fun start(context: Context, intent: Intent, origin: RecordingStartOrigin) {
        if (needsForegroundStart(origin)) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
    }
}

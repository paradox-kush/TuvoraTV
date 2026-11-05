package com.nuvio.tv.core.recordings.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * An alarm went off: hand the schedule id to the foreground service. An EXACT alarm may start a
 * foreground service from the background; an INEXACT one can be refused (Android 12+), in which case
 * the schedule stays and the next app start sweeps it (and the user is told).
 */
class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(AndroidRecordingAlarms.EXTRA_SCHEDULE_ID)?.takeIf { it.isNotBlank() } ?: return
        try {
            ContextCompat.startForegroundService(context, RecordingService.fireIntent(context, id))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) and the pre-31 background-start limit both land here.
            RecordingNotifications.couldNotStart(context)
        } catch (e: SecurityException) {
            RecordingNotifications.couldNotStart(context)
        }
    }
}

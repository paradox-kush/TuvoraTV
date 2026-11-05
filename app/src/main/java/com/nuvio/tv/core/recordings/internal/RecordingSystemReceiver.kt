package com.nuvio.tv.core.recordings.internal

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nuvio.tv.core.recordings.api.RecordingsAccess
import com.nuvio.tv.core.recordings.api.RecordingsPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Boot, app update, clock/timezone change and exact-alarm permission change: the OS dropped or
 * invalidated our alarms, so arm them all again from their wall-clock fire times.
 *
 * It re-arms ONLY. Android 15 forbids starting a mediaPlayback foreground service from
 * BOOT_COMPLETED, so an overdue schedule gets an alarm a few seconds out instead of a direct start.
 */
class RecordingSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in HANDLED) return
        val port = RecordingsAccess.current() ?: return
        val pending = goAsync()
        scope.launch { rearmAndFinish(port, System.currentTimeMillis()) { pending.finish() } }
    }

    companion object {
        /** Re-arms, never lets an I/O failure escape the (unsupervised-by-the-OS) scope, and always releases the broadcast. */
        internal suspend fun rearmAndFinish(port: RecordingsPort, nowMs: Long, finish: () -> Unit) {
            try {
                port.rearmAll(nowMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Recordings", "re-arming alarms failed", e)
            } finally {
                finish()
            }
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

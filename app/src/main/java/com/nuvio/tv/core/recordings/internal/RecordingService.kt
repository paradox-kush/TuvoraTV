package com.nuvio.tv.core.recordings.internal

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.RecordingsAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (foreground, mediaPlayback — owner decision, all builds) while a recording
 * runs. Each fire runs the repository's `fire()` in a service-owned scope; the service stops itself
 * when none is left. START_NOT_STICKY: if the system kills it, the repository's startup recovery
 * (not a sticky restart) decides what to retry.
 */
open class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Touched on the main thread only (onStartCommand and completion posts).
    private val active = HashMap<String, Job>()
    private var notifier: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val id = intent?.getStringExtra(AndroidRecordingAlarms.EXTRA_SCHEDULE_ID)?.takeIf { it.isNotBlank() }
        val port = RecordingsAccess.current()
        if (id == null || port == null) {
            // startForegroundService() obliges us to call startForeground even to quit.
            promote(RecordingNotifications.ongoing(this, "Recording", "Nothing to record", null, null))
            finish()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CANCEL) {
            scope.launch {
                port.cancel(id)
                // A stale Cancel (nothing running) must not leave a started service behind.
                android.os.Handler(mainLooper).post { if (active.isEmpty()) finish() }
            }
            return START_NOT_STICKY
        }
        if (!promote(RecordingNotifications.ongoing(this, "Recording", "Starting…", null, id))) {
            finish()
            return START_NOT_STICKY
        }
        if (active.containsKey(id)) return START_NOT_STICKY
        active[id] = scope.launch {
            val now = System.currentTimeMillis()
            port.start(now)
            port.fire(id, now)
        }.also { job ->
            job.invokeOnCompletion {
                android.os.Handler(mainLooper).post {
                    active.remove(id)
                    if (active.isEmpty()) finish()
                }
            }
        }
        startNotifier()
        return START_NOT_STICKY
    }

    /** Refreshes the ongoing notification from the repository: title, bytes so far, a percent against the estimate. */
    private fun startNotifier() {
        if (notifier?.isActive == true) return
        val port = RecordingsAccess.current() ?: return
        notifier = scope.launch {
            combine(port.state, port.progress) { state, progress -> state to progress }
                .sample(notificationIntervalMs)
                .collect { (state, progress) ->
                    val live = state.recordings.filter { it.status == RecordingStatus.RECORDING }
                    val first = live.firstOrNull() ?: return@collect
                    val bytes = progress[first.id] ?: first.sizeBytes
                    val estimate = RecordingSpacePolicy.estimateBytes(first.programmeEndMs - first.programmeStartMs)
                    val percent = if (estimate > 0) (bytes * 100 / estimate).toInt().coerceAtMost(99) else null
                    val title = if (live.size > 1) "Recording ${live.size} shows" else first.title
                    val text = "${bytes / (1024 * 1024)} MB saved"
                    first.scheduleId?.let { sid ->
                        refresh(RecordingNotifications.ongoing(this@RecordingService, title, text, percent, sid))
                    }
                }
        }
    }

    /**
     * Enters the foreground once per start command with its first notification. Re-calling
     * startForeground on every progress sample is a background-start policy risk on Android 14+
     * (and logs a warning each time); progress goes through [refresh] instead.
     * False when Android refuses to make us foreground (started from the background without an exemption).
     */
    private fun promote(notification: Notification): Boolean = try {
        enterForeground(notification)
        true
    } catch (e: IllegalStateException) {
        RecordingNotifications.couldNotStart(this)
        false
    } catch (e: SecurityException) {
        RecordingNotifications.couldNotStart(this)
        false
    }

    /** Seam: the single startForeground call (tests count it). */
    internal open fun enterForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this, RecordingNotifications.ONGOING_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /** Seam: progress update of the SAME ongoing notification id, no startForeground. */
    internal open fun refresh(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(RecordingNotifications.ONGOING_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted: the foreground notification itself is unaffected.
        }
    }

    /** Seam: progress refresh cadence. */
    internal open val notificationIntervalMs: Long get() = NOTIFICATION_INTERVAL_MS

    private fun finish() {
        notifier?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // The latest start id: a newer start that arrived meanwhile keeps the service alive.
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        // Anything still running is cancelled by the system, not the user: the repository records each
        // as STALLED and re-arms it (only an explicit cancel() is CANCELLED_BY_USER).
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_FIRE = "com.nuvio.tv.recordings.action.SERVICE_FIRE"
        const val ACTION_CANCEL = "com.nuvio.tv.recordings.action.SERVICE_CANCEL"
        private const val NOTIFICATION_INTERVAL_MS = 2_000L

        fun fireIntent(context: Context, scheduleId: String): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_FIRE)
                .putExtra(AndroidRecordingAlarms.EXTRA_SCHEDULE_ID, scheduleId)

        fun cancelIntent(context: Context, scheduleId: String): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_CANCEL)
                .putExtra(AndroidRecordingAlarms.EXTRA_SCHEDULE_ID, scheduleId)
    }
}

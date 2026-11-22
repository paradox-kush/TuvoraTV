package com.nuvio.tv.core.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R

/**
 * Foreground service that prevents the system from killing Nuvio while an
 * external video player is active.
 *
 * Contract (see [ForegroundStartPolicy]): the service is started with startForegroundService(),
 * so it must call startForeground() before anything stops it. Stopping it first crashes the app
 * with ForegroundServiceDidNotStartInTimeException (AOSP ActiveServices.bringDownServiceLocked).
 * onStartCommand therefore always promotes first, and a stop() that arrives while a start is
 * still unanswered is deferred until the service reports the promotion.
 */
class ExternalPlaybackKeepAliveService : Service() {

    companion object {
        private const val TAG = "ExtPlaybackKeepAlive"
        private const val CHANNEL_ID = "external_playback_channel"
        private const val NOTIFICATION_ID = 9529 // Zidoo port number as a nod :)
        private const val MAX_ALIVE_MS = 8L * 60 * 60 * 1000 // 8 hours safety limit
        private const val EXTRA_REQUEST_ID = "com.nuvio.tv.keepalive.REQUEST_ID"
        private const val NO_REQUEST_ID = -1L

        // Guards [policy] and [wanted]; start/stop run on callers' threads, reports on main.
        private val lock = Any()
        private val policy = ForegroundStartPolicy()
        private var wanted = false

        @Volatile
        private var lastStartRequestAtMs = 0L

        fun start(context: Context) {
            if (!BuildConfig.FEATURE_EXTERNAL_PLAYBACK_KEEP_ALIVE_ENABLED) return
            lastStartRequestAtMs = SystemClock.elapsedRealtime()
            synchronized(lock) {
                wanted = true
                // A start already in flight or running covers this request; a second
                // startForegroundService() would only add another start to answer.
                val requestId = policy.onPublish() ?: return
                val intent = Intent(context, ExternalPlaybackKeepAliveService::class.java)
                    .putExtra(EXTRA_REQUEST_ID, requestId)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    Log.d(TAG, "Service start requested")
                } catch (e: Exception) {
                    policy.onStartFailed(requestId)
                    Log.w(TAG, "Failed to start keep-alive service", e)
                }
            }
        }

        fun stop(context: Context) {
            if (!BuildConfig.FEATURE_EXTERNAL_PLAYBACK_KEEP_ALIVE_ENABLED) return
            synchronized(lock) {
                wanted = false
                if (!policy.onHide()) {
                    Log.d(TAG, "Stop deferred until the pending start is promoted")
                    return
                }
                try {
                    val intent = Intent(context, ExternalPlaybackKeepAliveService::class.java)
                    context.stopService(intent)
                    Log.d(TAG, "Service stop requested")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop keep-alive service", e)
                }
            }
        }

        /** Returns true when the service should stop itself now (a stop was deferred). */
        private fun onPromoted(requestId: Long): Boolean =
            synchronized(lock) { policy.onPromoted(requestId, stillWanted = wanted) }

        private fun onPromoteFailed(requestId: Long) {
            synchronized(lock) { policy.onPromoteFailed(requestId) }
        }

        private fun onServiceDestroyed(lastRequestId: Long?) {
            // A destroyed keep-alive is not restarted here (the safety timeout must win).
            synchronized(lock) { policy.onServiceDestroyed(lastRequestId, stillWanted = false) }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeoutRunnable = object : Runnable {
        override fun run() {
            // A later start() re-arms the limit, as a fresh onStartCommand did before.
            val sinceLastStart = SystemClock.elapsedRealtime() - lastStartRequestAtMs
            if (sinceLastStart < MAX_ALIVE_MS) {
                handler.postDelayed(this, MAX_ALIVE_MS - sinceLastStart)
                return
            }
            Log.d(TAG, "Safety timeout reached, stopping service")
            stopSelf()
        }
    }
    private var lastRequestId: Long? = null
    private var promoted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getLongExtra(EXTRA_REQUEST_ID, NO_REQUEST_ID)?.takeIf { it != NO_REQUEST_ID }
        if (requestId != null) lastRequestId = requestId

        if (!promoted) {
            promoted = try {
                startForeground(NOTIFICATION_ID, buildNotification())
                true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException on Android 12+ when the app is no
                // longer allowed to run a foreground service. Stopping is safe here: AOSP clears
                // the foreground-required flag before every throw inside startForeground().
                Log.w(TAG, "startForeground() not allowed, stopping: ${e.message}")
                false
            }
        }
        if (!promoted) {
            requestId?.let(::onPromoteFailed)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (requestId != null && onPromoted(requestId)) {
            Log.d(TAG, "Promoted after a deferred stop, stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Safety timeout - auto-stop after 8 hours in case stop() is never called
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, MAX_ALIVE_MS)
        Log.d(TAG, "Foreground service started")

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed, keeping service alive")
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        onServiceDestroyed(lastRequestId)
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.external_playback_channel_description)
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.external_playback_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

package com.nuvio.tv.core.recordings.api

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.nuvio.tv.core.recordings.internal.AndroidRecordingAlarms
import com.nuvio.tv.core.recordings.internal.AndroidRecordingFiles
import com.nuvio.tv.core.recordings.internal.AndroidRecordingSaver
import com.nuvio.tv.core.recordings.internal.RecordingNotifications
import com.nuvio.tv.core.recordings.internal.RecordingService
import com.nuvio.tv.core.recordings.internal.RecordingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** TV capabilities. One constant, so wave 4 (live capture) flips it in one place. */
private object TvRecordingCapabilities {
    /** TODO(live capture pending live-pause branch): wave 4 sets this true. */
    const val CAN_RECORD_LIVE = false
    val value = RecordingCapabilities(canRecordLive = CAN_RECORD_LIVE, canSaveCatchUp = true)
}

/**
 * The Android composition of the recordings feature on TV. Hilt builds the one repository ([create],
 * from `RecordingsModule`); NuvioApplication publishes it to [RecordingsAccess] (alarm receivers and the
 * foreground service are not injected) and calls [installAppStartHook].
 */
object RecordingsAndroid {
    private const val TAG = "Recordings"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cheap (no I/O). [baseClient] is the app's IPTV client; the channel's own headers carry the provider UA. */
    fun create(context: Context, baseClient: OkHttpClient): RecordingsPort {
        val app = context.applicationContext
        val files = AndroidRecordingFiles(
            stateDir = File(app.filesDir, "recordings-state"),
            recordingsDir = File(app.filesDir, "recordings"),
        )
        return RecordingsRepository(
            files = files,
            alarms = AndroidRecordingAlarms(app),
            saver = AndroidRecordingSaver(isOnline = { isOnline(app) }, baseClient = baseClient),
            capabilities = TvRecordingCapabilities.value,
            lowSpacePolicy = { LowSpacePolicy.STOP }, // TODO(UI wave): read the user's setting
        )
    }

    /**
     * Where the user can allow exact alarms, for the UI to open. Null on Android 11 and below (no
     * permission needed) AND where the device has no such settings screen (unverified on Android TV /
     * Fire TV): treat null as "stay inexact, say it may start late".
     */
    fun exactAlarmSettingsIntent(context: Context): Intent? = AndroidRecordingAlarms.exactAlarmSettingsIntent(context)

    /**
     * Once per process, when the first Activity starts (a visible app may start a foreground service):
     * load, recover what a dead process left running, then hand each overdue schedule to the service.
     * A headless start (alarm, boot) never reaches here, so Android 15's BOOT_COMPLETED rule cannot bite.
     */
    fun installAppStartHook(app: Application, port: RecordingsPort) {
        val done = AtomicBoolean(false)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (done.compareAndSet(false, true)) onAppStart(app, port)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Off the main thread. */
    internal fun onAppStart(app: Context, port: RecordingsPort) {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                port.start(now)
                for (id in port.overdueIds(now)) {
                    try {
                        ContextCompat.startForegroundService(app, RecordingService.fireIntent(app, id))
                    } catch (e: IllegalStateException) {
                        // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
                        RecordingNotifications.couldNotStart(app)
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "app-start recovery failed", e)
            }
        }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

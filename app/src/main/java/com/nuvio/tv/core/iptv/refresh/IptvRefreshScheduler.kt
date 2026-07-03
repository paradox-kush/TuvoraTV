package com.nuvio.tv.core.iptv.refresh

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the single periodic [IptvRefreshWorker]. It observes the playlist list and (re)schedules
 * the worker whenever the shortest enabled `autoRefreshHours` changes, so the run cadence tracks the
 * most-frequent playlist. When no playlist opts into auto-refresh, the worker is cancelled.
 *
 * A single periodic worker (rather than per-playlist workers) keeps scheduling simple; the worker
 * itself picks which playlists are actually due each run via [IptvRefreshDue].
 */
@Singleton
class IptvRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountStore: XtreamAccountStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Last interval we scheduled (minutes), so we only UPDATE (reset the clock) when it changes. */
    private val scheduledIntervalMinutes = AtomicLong(-1L)

    /** Start observing playlists and keep the periodic worker's schedule in sync. Called once at app start. */
    fun start() {
        accountStore.accounts
            .onEach { accounts -> reschedule(accounts) }
            .launchIn(scope)
    }

    private fun reschedule(accounts: List<XtreamAccount>) {
        val candidates = accounts.map {
            RefreshCandidate(it.id, it.enabled, it.autoRefreshHours, lastRefreshMs = null)
        }
        val shortestHours = IptvRefreshDue.shortestIntervalHours(candidates)
        val wm = WorkManager.getInstance(context)

        if (shortestHours == null) {
            wm.cancelUniqueWork(WORK_NAME)
            scheduledIntervalMinutes.set(-1L)
            Log.i(TAG, "no auto-refresh playlists — worker cancelled")
            return
        }

        // WorkManager's minimum periodic interval is 15 minutes; our options start at 6h, so the
        // shortest-hours value always clears the floor, but clamp defensively.
        val intervalMinutes = (shortestHours.toLong() * 60L).coerceAtLeast(15L)
        val request = PeriodicWorkRequestBuilder<IptvRefreshWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        // Only UPDATE (which restarts the interval clock) when the interval actually changed; KEEP
        // otherwise so an unrelated playlist edit doesn't reset the timer every emission.
        val policy = if (scheduledIntervalMinutes.getAndSet(intervalMinutes) == intervalMinutes) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            ExistingPeriodicWorkPolicy.UPDATE
        }
        wm.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        Log.i(TAG, "scheduled IPTV refresh every ${intervalMinutes}min (shortest=${shortestHours}h, policy=$policy)")
    }

    companion object {
        private const val TAG = "IptvRefreshScheduler"
        private const val WORK_NAME = "iptv_auto_refresh"
    }
}

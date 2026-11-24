package com.nuvio.tv.core.radar.alerts

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.lifecycle.AppForegroundTracker
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.radar.RadarStore
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SportsAlertSubscription"
/** Local status check only (no network): how often a joined channel's health is looked at. */
private const val HEALTH_CHECK_MS = 5_000L

/**
 * Binds the alert channels to what the user is doing: joined only while the app is in the foreground (the
 * `AppForegroundTracker` is the process ON_START / ON_STOP signal), only for signed-in users, only for the sports of
 * non-muted follows. Re-derives the topic set whenever follows, settings, auth or the profile change. The client makes
 * no HTTP request for this feature and holds no timer while there is nothing to join.
 */
@Singleton
class SportsAlertSubscription @Inject constructor(
    private val channels: SportsAlertChannels,
    private val controller: SportsAlertController,
    private val store: RadarStore,
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val foreground: AppForegroundTracker,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "alert subscription failed", e) },
    )
    private var job: Job? = null
    private var profileJob: Job? = null

    fun start() {
        if (job?.isActive == true) return
        controller.start()
        profileJob = scope.launch {
            // Held alerts, seen keys and lookups belong to one profile.
            profileManager.activeProfileId.drop(1).collect { controller.onProfileChanged() }
        }
        job = scope.launch {
            combine(
                foreground.isForeground,
                authManager.authState.map { it is AuthState.FullAccount }.distinctUntilChanged(),
                store.state,
            ) { isForeground, signedIn, local ->
                if (!isForeground) emptySet<String>() else {
                    val follows = local.follows.map { f ->
                        if (f.sport.isBlank()) f.copy(sport = BundledLeagues.sportOf(f.leagueId).orEmpty()) else f
                    }
                    SportsAlertTopicPolicy.topics(local.prefs.alerts, follows, local.teams, signedIn)
                }
            }.distinctUntilChanged().collectLatest { topics ->
                if (topics.isEmpty()) {
                    channels.leaveAll()
                    return@collectLatest
                }
                while (isActive) {
                    // The alert flows must outlive this block (it is cancelled on every topic change): the long-lived scope owns them.
                    channels.reconcile(topics, scope, controller::onPayload)
                    delay(HEALTH_CHECK_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        profileJob?.cancel(); profileJob = null
        scope.launch { channels.leaveAll() }
    }
}

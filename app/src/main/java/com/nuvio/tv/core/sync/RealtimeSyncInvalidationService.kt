package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "RealtimeSyncInvalidation"
private const val REALTIME_INVALIDATION_COALESCE_MS = 500L
private const val REALTIME_SUBSCRIBE_TIMEOUT_MS = 15_000L
private const val REALTIME_RETRY_BASE_DELAY_MS = 1_000L
private const val REALTIME_RETRY_MAX_DELAY_MS = 10_000L

@Singleton
class RealtimeSyncInvalidationService @Inject constructor(
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val supabaseClient: SupabaseClient,
    private val syncClientIdentity: SyncClientIdentity,
    private val startupSyncService: StartupSyncService
) {
    // Invalidation pulls are a convenience; nothing here is worth taking the process down for.
    private val crashGuard = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Realtime sync invalidation failed", error)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private val pendingMutex = Mutex()
    private val pendingSurfaces = mutableSetOf<String>()

    /**
     * Channel names must never repeat for the lifetime of the process. `supabaseClient.channel(name)`
     * hands back the *existing* channel when the name is already known to the realtime client, and a
     * channel that has (re)joined refuses `postgresChangeFlow`. Restarting the subscription used to
     * reset the attempt counter, so a restart could collide with a channel the client was still
     * holding — and the throw crashed the app.
     */
    private val channelSequence = AtomicLong()

    private var observerJob: Job? = null
    private var subscriptionJob: Job? = null
    private var drainJob: Job? = null
    private var activeUserId: String? = null
    private var activeProfileId: Int? = null

    fun start() {
        if (observerJob?.isActive == true) return

        observerJob = scope.launch {
            combine(authManager.authState, profileManager.activeProfileId) { authState, profileId ->
                authState to profileId
            }
                .distinctUntilChanged()
                .collect { (authState, profileId) ->
                    if (authState is AuthState.FullAccount) {
                        val syncUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = true)
                            ?: authState.userId
                        startSubscription(syncUserId, profileId)
                    } else {
                        stopSubscription()
                    }
                }
        }
    }

    fun stop() {
        observerJob?.cancel()
        observerJob = null
        stopSubscription()
    }

    private fun startSubscription(userId: String, profileId: Int) {
        if (
            subscriptionJob?.isActive == true &&
            activeUserId == userId &&
            activeProfileId == profileId
        ) {
            Log.d(TAG, "Realtime sync already active for profile $profileId")
            return
        }

        stopSubscription()
        activeUserId = userId
        activeProfileId = profileId
        subscriptionJob = scope.launch {
            var attempt = 1
            while (isActive) {
                val channelName = "sync-invalidations:$userId:$profileId:${channelSequence.incrementAndGet()}"
                var channel: RealtimeChannel? = null
                var changesJob: Job? = null
                var realtimeStatusJob: Job? = null
                var channelStatusJob: Job? = null

                try {
                    val newChannel = supabaseClient.channel(channelName)
                    channel = newChannel
                    val realtime = newChannel.realtime
                    realtimeStatusJob = launch {
                        realtime.status.collect { status ->
                            Log.i(TAG, "Realtime client status=$status channel=$channelName")
                        }
                    }
                    channelStatusJob = launch {
                        newChannel.status.collect { status ->
                            Log.i(TAG, "Realtime channel status=$status channel=$channelName")
                        }
                    }
                    changesJob = newChannel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                        table = "sync_invalidations"
                        filter("user_id", FilterOperator.EQ, userId)
                    }.onEach { action ->
                        handleInsert(profileId, action.record)
                    }.launchIn(this)

                    Log.i(TAG, "Subscribing to sync invalidations channel=$channelName attempt=$attempt user=$userId profile=$profileId")
                    withTimeout(REALTIME_SUBSCRIBE_TIMEOUT_MS) {
                        newChannel.subscribe(blockUntilSubscribed = true)
                    }
                    Log.i(TAG, "Subscribed to sync invalidations channel=$channelName profile=$profileId")
                    awaitCancellation()
                } catch (error: TimeoutCancellationException) {
                    Log.e(
                        TAG,
                        "Timed out subscribing to sync invalidations channel=$channelName realtimeStatus=${channel?.realtime?.status?.value} channelStatus=${channel?.status?.value}",
                        error
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(
                        TAG,
                        "Failed to subscribe to sync invalidations channel=$channelName realtimeStatus=${channel?.realtime?.status?.value} channelStatus=${channel?.status?.value}",
                        error
                    )
                } finally {
                    changesJob?.cancel()
                    realtimeStatusJob?.cancel()
                    channelStatusJob?.cancel()
                    channel?.let { doomed ->
                        // removeChannel, not unsubscribe: unsubscribe leaves the channel registered
                        // with the realtime client, which then rejoins it on every reconnect.
                        // NonCancellable so a stop() still hands the channel back.
                        withContext(NonCancellable) {
                            runCatching { doomed.realtime.removeChannel(doomed) }
                                .onSuccess { Log.i(TAG, "Removed sync invalidations channel=$channelName") }
                                .onFailure { error -> Log.w(TAG, "Failed to remove sync invalidations channel=$channelName", error) }
                        }
                    }
                }

                if (isActive) {
                    val retryDelay = (REALTIME_RETRY_BASE_DELAY_MS * attempt)
                        .coerceAtMost(REALTIME_RETRY_MAX_DELAY_MS)
                    Log.w(TAG, "Retrying sync invalidations subscription in ${retryDelay}ms profile=$profileId nextAttempt=${attempt + 1}")
                    delay(retryDelay)
                    attempt += 1
                }
            }
        }
    }

    private fun stopSubscription() {
        if (subscriptionJob != null || drainJob != null) {
            Log.i(TAG, "Stopping realtime sync invalidation service for profile $activeProfileId")
        }
        subscriptionJob?.cancel()
        drainJob?.cancel()
        subscriptionJob = null
        drainJob = null
        activeUserId = null
        activeProfileId = null
        pendingSurfaces.clear()
    }

    private fun handleInsert(profileId: Int, record: JsonObject) {
        val eventId = record["id"]?.jsonPrimitive?.contentOrNull
        val createdAt = record["created_at"]?.jsonPrimitive?.contentOrNull
        val originClientId = record["origin_client_id"]?.jsonPrimitive?.contentOrNull
        if (originClientId != null && originClientId == syncClientIdentity.currentClientId()) {
            Log.d(TAG, "Ignoring self-originated sync invalidation id=$eventId originClientId=$originClientId")
            return
        }

        val surface = record["surface"]?.jsonPrimitive?.contentOrNull
        if (surface == null) {
            Log.w(TAG, "Received sync invalidation without surface id=$eventId createdAt=$createdAt keys=${record.keys}")
            return
        }

        val eventProfileId = record["profile_id"]?.jsonPrimitive?.intOrNull
        Log.i(
            TAG,
            "Received sync invalidation id=$eventId surface=$surface eventProfile=$eventProfileId activeProfile=$profileId originClientId=$originClientId createdAt=$createdAt"
        )
        if (surface != "profiles" && eventProfileId != null && eventProfileId != profileId) {
            Log.d(TAG, "Ignoring sync invalidation id=$eventId for inactive profile $eventProfileId")
            return
        }

        enqueue(surface, profileId)
    }

    private fun enqueue(surface: String, profileId: Int) {
        scope.launch {
            pendingMutex.withLock {
                pendingSurfaces += surface
                Log.d(TAG, "Queued realtime surface pull surface=$surface profile=$profileId pending=${pendingSurfaces.size}")
                if (drainJob?.isActive == true) return@withLock
                drainJob = scope.launch {
                    delay(REALTIME_INVALIDATION_COALESCE_MS)
                    val surfaces = pendingMutex.withLock {
                        pendingSurfaces.toList().also {
                            pendingSurfaces.clear()
                        }
                    }
                    Log.i(TAG, "Draining realtime surface pulls profile=$profileId surfaces=$surfaces")
                    surfaces.forEach { pendingSurface ->
                        startupSyncService.requestRealtimeSurfacePull(profileId, pendingSurface)
                    }
                }
            }
        }
    }
}

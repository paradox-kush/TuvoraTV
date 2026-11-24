package com.nuvio.tv.core.radar.alerts

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.sync.RealtimeChannelHealthPolicy
import com.nuvio.tv.core.sync.RealtimeRetryPolicy
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "SportsAlertChannels"

/**
 * The Realtime side of sports alerts: up to five private, receive-only broadcast channels
 * (`sports-alerts:v1:{sport}`) on the socket the sync invalidations and watch party already use. No new socket.
 *
 * Rules this class exists to keep (the reused-channel crash class, see RealtimeSyncInvalidationService):
 *  - the broadcast flow is registered BEFORE `subscribe`;
 *  - a channel is torn down with `removeChannel` in [NonCancellable], never `unsubscribe`;
 *  - EVERY join and leave runs under one [Mutex], so a foreground/background flap can never re-get a channel (same
 *    name = same channel for the Realtime client) that is still being removed.
 */
@Singleton
class SportsAlertChannels @Inject constructor(
    private val realtime: Realtime,
) {
    private class Joined(val channel: RealtimeChannel, val jobs: List<Job>, var notSubscribedSinceMs: Long?, var subscribedSinceMs: Long?)
    private class Backoff(val attempt: Int, val notBeforeMs: Long)

    private val mutex = Mutex()
    private val joined = HashMap<String, Joined>()
    private val backoff = HashMap<String, Backoff>()

    /**
     * Makes the joined set equal to [topics]: leaves the unwanted, leaves the ones that stayed unhealthy past the
     * grace (they rejoin after a jittered backoff), joins the missing. Cheap and local: it reads channel status only.
     * Call it again on a short interval while [topics] is non-empty.
     */
    suspend fun reconcile(topics: Set<String>, scope: CoroutineScope, onPayload: (JsonObject) -> Unit) = mutex.withLock {
        val now = SystemClock.elapsedRealtime()
        joined.keys.toList().filter { it !in topics }.forEach { leave(it) }
        backoff.keys.retainAll(topics)
        joined.entries.toList().forEach { (topic, j) ->
            val subscribed = j.channel.status.value == RealtimeChannel.Status.SUBSCRIBED
            if (subscribed) {
                j.notSubscribedSinceMs = null
                if (j.subscribedSinceMs == null) j.subscribedSinceMs = now
                // A connection that held for a minute starts the retry ladder over.
                if (now - j.subscribedSinceMs!! >= RealtimeRetryPolicy.STABLE_CONNECTION_MS) backoff.remove(topic)
            } else {
                j.subscribedSinceMs = null
                if (j.notSubscribedSinceMs == null) j.notSubscribedSinceMs = now
                if (RealtimeChannelHealthPolicy.decide(false, j.notSubscribedSinceMs, now) == RealtimeChannelHealthPolicy.Action.Resubscribe) {
                    val attempt = (backoff[topic]?.attempt ?: 0) + 1
                    backoff[topic] = Backoff(attempt, now + RealtimeRetryPolicy.delayMs(attempt, Random.nextDouble()))
                    Log.w(TAG, "Alert channel left SUBSCRIBED; rejoining after backoff attempt=$attempt")
                    leave(topic)
                }
            }
        }
        topics.filter { it !in joined }.forEach { topic ->
            val wait = backoff[topic]
            if (wait == null || now >= wait.notBeforeMs) join(topic, scope, onPayload, now)
        }
    }

    /** Leaves every channel (app went to the background, signed out, alerts off). */
    suspend fun leaveAll() = mutex.withLock {
        joined.keys.toList().forEach { leave(it) }
        backoff.clear()
    }

    /** Topics currently joined (diagnostics and tests). */
    suspend fun joinedTopics(): Set<String> = mutex.withLock { joined.keys.toSet() }

    private suspend fun join(topic: String, scope: CoroutineScope, onPayload: (JsonObject) -> Unit, now: Long) {
        var created: RealtimeChannel? = null
        var flow: Job? = null
        try {
            val channel = realtime.channel(topic) {
                isPrivate = true
                broadcast {
                    receiveOwnBroadcasts = false
                    acknowledgeBroadcasts = false
                }
            }
            created = channel
            // Register the flow before subscribe: UNDISPATCHED runs the collector up to its first suspension (the
            // callback registration) right here, and the yield lets the session dispatcher process it.
            flow = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    channel.broadcastFlow<JsonObject>("alert").collect { payload -> onPayload(payload) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Alert flow ended", e)
                }
            }
            yield()
            channel.subscribe(blockUntilSubscribed = false)
            joined[topic] = Joined(channel, listOf(flow), notSubscribedSinceMs = now, subscribedSinceMs = null)
        } catch (e: CancellationException) {
            flow?.cancel()
            created?.let { removeQuietly(it) }
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to join alert channel", e)
            flow?.cancel()
            created?.let { removeQuietly(it) }
            val attempt = (backoff[topic]?.attempt ?: 0) + 1
            backoff[topic] = Backoff(attempt, now + RealtimeRetryPolicy.delayMs(attempt, Random.nextDouble()))
        }
    }

    private suspend fun leave(topic: String) {
        val j = joined.remove(topic) ?: return
        j.jobs.forEach { it.cancel() }
        removeQuietly(j.channel)
    }

    // removeChannel, not unsubscribe: unsubscribe leaves the channel registered, and the client rejoins it on every
    // reconnect. NonCancellable so a stop still hands the channel back.
    private suspend fun removeQuietly(channel: RealtimeChannel) {
        withContext(NonCancellable) {
            runCatching { realtime.removeChannel(channel) }
                .onFailure { Log.w(TAG, "Failed to remove alert channel", it) }
        }
    }
}

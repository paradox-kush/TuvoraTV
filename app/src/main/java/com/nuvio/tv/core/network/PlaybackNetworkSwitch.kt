package com.nuvio.tv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.nuvio.tv.core.lifecycle.Disposable

/**
 * What playback does when the device's DEFAULT network changes mid-stream (Wi-Fi -> Ethernet, Wi-Fi -> a phone's
 * hotspot, and back). Hand-port of NuvioMobile's `PlaybackNetworkSwitchPolicy` (same rules, same tests).
 *
 * Android never moves an open socket: once a new network is the default, only NEW connections use it ("Read network
 * state", developer.android.com); a connection on a Wi-Fi that is still associated but weak keeps using it. A weak but
 * alive connection never fails by itself either — OkHttp's readTimeout is per read and resets on every trickle of
 * bytes, FFmpeg reconnects only on a read error or early EOF — and the pooled keep-alive connections carry the old
 * network into the next segment request. So playback glitched once at the switch and then stayed slow for good.
 *
 * [Action.RELEASE_IDLE] drops pooled idle connections; [Action.RECONNECT] also re-opens what is playing (Media3:
 * cancel the in-flight reads, which its load retry re-opens on the new network; libmpv: reload at the playhead).
 * A single-use link (Stalker `SESSION_ONLY`, a single-use catch-up replay) is never re-opened here. One reconnect per
 * [MIN_RECONNECT_INTERVAL_MS] at most, so a flapping network cannot loop reloads.
 */
object PlaybackNetworkSwitchPolicy {
    const val MIN_RECONNECT_INTERVAL_MS = 10_000L

    enum class Action { NONE, RELEASE_IDLE, RECONNECT }

    fun decide(
        lastNetwork: String?,
        currentNetwork: String?,
        playbackActive: Boolean,
        anySingleUseLink: Boolean,
        lastReconnectAtMs: Long?,
        nowMs: Long,
    ): Action = when {
        // Offline: nothing to move to; the engine's own error/reconnect path handles the loss.
        currentNetwork == null -> Action.NONE
        // The first report after listening starts is the baseline, not a change.
        lastNetwork == null -> Action.NONE
        currentNetwork == lastNetwork -> Action.NONE
        !playbackActive || anySingleUseLink -> Action.RELEASE_IDLE
        lastReconnectAtMs != null && nowMs - lastReconnectAtMs < MIN_RECONNECT_INTERVAL_MS -> Action.RELEASE_IDLE
        else -> Action.RECONNECT
    }
}

/** Port: the default network's opaque id (null = none), the current one first, then each change, until disposed. */
fun interface DefaultNetworkMonitor {
    fun start(onDefaultNetwork: (String?) -> Unit): Disposable
}

/** Port: the shared playback HTTP stack. */
interface PlaybackConnectionPool {
    /** Close pooled idle keep-alive connections, so the next request opens on the current network. */
    fun releaseIdle()

    /** Abort shared-stack reads in flight; an engine with a load retry re-opens them on the current network. */
    fun cancelInFlight()
}

/**
 * One per process. Each player [register]s while it holds a stream; the default-network listener runs only while at
 * least one is registered, and each change is decided by [PlaybackNetworkSwitchPolicy]. On RECONNECT the shared HTTP
 * stack is flushed once, then every player's own re-open runs. Thread-safe; callbacks run outside the lock.
 */
class PlaybackNetworkSwitchCoordinator(
    private val monitor: DefaultNetworkMonitor,
    private val pool: PlaybackConnectionPool,
    private val nowMs: () -> Long,
) {
    private class Client(val singleUseLink: Boolean, val onReconnect: () -> Unit)

    private val lock = Any()
    private val clients = mutableListOf<Client>()
    private var generation = 0
    private var monitoring = false
    private var monitorHandle: Disposable? = null
    private var lastNetwork: String? = null
    private var lastReconnectAtMs: Long? = null

    /** [onReconnect] re-opens the stream on the current network keeping position; it must not block. */
    fun register(singleUseLink: Boolean, onReconnect: () -> Unit): Disposable {
        val client = Client(singleUseLink, onReconnect)
        val startGeneration = synchronized(lock) {
            clients += client
            if (monitoring) {
                null
            } else {
                monitoring = true
                lastNetwork = null
                lastReconnectAtMs = null
                ++generation
            }
        }
        if (startGeneration != null) {
            val handle = monitor.start { network -> onDefaultNetwork(startGeneration, network) }
            val keep = synchronized(lock) {
                (monitoring && generation == startGeneration).also { if (it) monitorHandle = handle }
            }
            if (!keep) handle.dispose()
        }
        return Disposable { unregister(client) }
    }

    private fun unregister(client: Client) {
        val handle = synchronized(lock) {
            if (clients.remove(client) && clients.isEmpty()) {
                monitoring = false
                generation++
                monitorHandle.also { monitorHandle = null }
            } else {
                null
            }
        }
        handle?.dispose()
    }

    private fun onDefaultNetwork(fromGeneration: Int, network: String?) {
        val now = nowMs()
        val decided = synchronized(lock) {
            if (!monitoring || generation != fromGeneration) return@synchronized null
            val action = PlaybackNetworkSwitchPolicy.decide(
                lastNetwork = lastNetwork,
                currentNetwork = network,
                playbackActive = clients.isNotEmpty(),
                anySingleUseLink = clients.any { it.singleUseLink },
                lastReconnectAtMs = lastReconnectAtMs,
                nowMs = now,
            )
            if (network != null) lastNetwork = network
            if (action == PlaybackNetworkSwitchPolicy.Action.RECONNECT) lastReconnectAtMs = now
            action to clients.toList()
        } ?: return
        val (action, targets) = decided
        when (action) {
            PlaybackNetworkSwitchPolicy.Action.NONE -> Unit
            PlaybackNetworkSwitchPolicy.Action.RELEASE_IDLE -> runCatching { pool.releaseIdle() }
            PlaybackNetworkSwitchPolicy.Action.RECONNECT -> {
                runCatching { pool.releaseIdle() }
                runCatching { pool.cancelInFlight() }
                // A player torn down mid-switch must not stop the others moving.
                targets.forEach { runCatching { it.onReconnect() } }
            }
        }
    }
}

/**
 * The process coordinator: the default network from `registerDefaultNetworkCallback`, and the VOD player's shared
 * OkHttp stack ([PlayerPlaybackNetworking][com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking]) plus any client
 * handed to [trackIdlePool] (the live session factory's) as the pool. Live Media3 sessions cancel their own tracked
 * calls in their [PlaybackNetworkSwitchCoordinator.register] callback, so a single-use tile is never touched globally.
 */
object PlaybackNetworkSwitch {
    @Volatile
    private var instance: PlaybackNetworkSwitchCoordinator? = null
    private val extraPools = java.util.concurrent.CopyOnWriteArraySet<okhttp3.ConnectionPool>()

    fun trackIdlePool(pool: okhttp3.ConnectionPool) {
        extraPools += pool
    }

    fun coordinator(context: Context): PlaybackNetworkSwitchCoordinator =
        instance ?: synchronized(this) {
            instance ?: PlaybackNetworkSwitchCoordinator(
                monitor = ConnectivityDefaultNetworkMonitor(context.applicationContext),
                pool = object : PlaybackConnectionPool {
                    override fun releaseIdle() {
                        com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking.releaseIdleConnections()
                        extraPools.forEach { runCatching { it.evictAll() } }
                    }

                    override fun cancelInFlight() {
                        com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking.cancelInFlightCalls()
                    }
                },
                nowMs = { SystemClock.elapsedRealtime() },
            ).also { instance = it }
        }
}

/**
 * The default network's id from `ConnectivityManager.registerDefaultNetworkCallback`: the current default on
 * registration, then every change; `onLost` of the default means none. Callbacks on a private thread, never Main.
 */
internal class ConnectivityDefaultNetworkMonitor(private val context: Context) : DefaultNetworkMonitor {
    override fun start(onDefaultNetwork: (String?) -> Unit): Disposable {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return Disposable {}
        val thread = HandlerThread("playback-net-switch").apply { start() }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onDefaultNetwork(network.networkHandle.toString())
            override fun onLost(network: Network) = onDefaultNetwork(null)
            // Some OEM builds skip onAvailable on a quick switch; the new default's first capabilities carry it.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                onDefaultNetwork(network.networkHandle.toString())
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.registerDefaultNetworkCallback(callback, Handler(thread.looper))
            } else {
                manager.registerDefaultNetworkCallback(callback)
            }
        }.isSuccess
        if (!registered) {
            thread.quitSafely()
            return Disposable {}
        }
        return Disposable {
            runCatching { manager.unregisterNetworkCallback(callback) }
            thread.quitSafely()
        }
    }
}

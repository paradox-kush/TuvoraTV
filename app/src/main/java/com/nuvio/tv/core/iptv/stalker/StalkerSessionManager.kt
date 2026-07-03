package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.nuvio.tv.core.iptv.XtreamAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Owns one [StalkerSession] per Stalker playlist (keyed by account id + a config fingerprint, so an
 * edited portal/MAC gets a fresh session) and drives the watchdog keep-alive.
 *
 * Watchdog: a portal expires an idle session after `watchdog_timeout` (~100–120s). We only poll
 * while the IPTV UI/playback is active — [retain]/[release] ref-count that window (the hub/guide
 * calls retain on enter, release on leave). No polling = no keep-alive when the app is idle, which
 * is exactly what a real STB does when it sleeps.
 */
@Singleton
class StalkerSessionManager @Inject constructor(
    @Named("stalker") private val http: OkHttpClient
) {
    private data class Entry(val session: StalkerSession, val fingerprint: String)

    private val sessions = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob())
    private var watchdogJob: Job? = null
    private var retainCount = 0
    private val lock = Any()

    /** The session for [account], recreated if the account's Stalker config changed since last time. */
    fun sessionFor(account: XtreamAccount): StalkerSession {
        val fp = fingerprint(account)
        val existing = sessions[account.id]
        if (existing != null && existing.fingerprint == fp) return existing.session
        val fresh = StalkerSession(account, http)
        sessions[account.id] = Entry(fresh, fp)
        return fresh
    }

    /** Drop a session (playlist removed/edited) so the next access re-handshakes. */
    fun evict(accountId: String) { sessions.remove(accountId) }

    fun clear() { sessions.clear() }

    /** IPTV UI/playback became active — start the watchdog if it isn't already running. */
    fun retain() {
        synchronized(lock) {
            retainCount++
            if (watchdogJob?.isActive != true) startWatchdog()
        }
    }

    /** IPTV UI/playback left — stop the watchdog when nothing is holding it. */
    fun release() {
        synchronized(lock) {
            retainCount = (retainCount - 1).coerceAtLeast(0)
            if (retainCount == 0) {
                watchdogJob?.cancel()
                watchdogJob = null
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            while (isActive) {
                val entries = sessions.values.toList()
                // Cadence = the shortest live session's watchdog timeout (default 120s), a bit early.
                val nextMs = entries.minOfOrNull { it.session.watchdogTimeoutMs }?.takeIf { it > 0 }
                    ?: 120_000L
                delay((nextMs * 3 / 4).coerceAtLeast(30_000L))
                if (!isActive) break
                for (entry in sessions.values.toList()) {
                    runCatching { entry.session.watchdogTick() }
                        .onFailure { Log.d(TAG, "watchdog tick error", it) }
                }
            }
        }
    }

    /** Any change to these invalidates the session (new handshake/device identity needed). */
    private fun fingerprint(a: XtreamAccount): String =
        listOf(a.portalUrl, a.macAddress, a.serialNumber, a.deviceId, a.sendDeviceId.toString(),
            a.stalkerUsername, a.stalkerPassword).joinToString("|")

    companion object { private const val TAG = "StalkerSessionMgr" }
}

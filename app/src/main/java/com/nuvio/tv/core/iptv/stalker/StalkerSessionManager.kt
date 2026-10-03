package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.dns.PlaylistDns
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Owns one [StalkerSession] per Stalker playlist, keyed by account id + a config fingerprint so an
 * edited portal/MAC gets a fresh session.
 *
 * Each session runs its own `get_events` keep-alive once authenticated (see
 * [StalkerWatchdogPolicy]) — the ping is log-only and never re-handshakes on its own, so on-demand
 * single-flight re-auth (see [StalkerSession.reauthenticate]) is still what recovers a stale
 * token, exactly like a real STB after it sleeps. Replacing or evicting a session shuts its
 * watchdog down.
 */
@Singleton
class StalkerSessionManager @Inject constructor(
    @Named("stalker") private val http: OkHttpClient,
    private val playlistDns: PlaylistDns,
) {
    /**
     * One playlist's sessions: one per PORTAL it has been reached on (main / a backup — Step 0.3), all
     * under one [fingerprint] of the playlist's config. A failover walk visits portals in turn; a
     * shared per-playlist slot would swap (and re-handshake) the session on every visit, rotating the
     * MAC token and defeating the per-session single-flight re-auth.
     */
    private class Entry(val fingerprint: String, val byPortal: MutableMap<String, StalkerSession> = HashMap())

    private val sessions = ConcurrentHashMap<String, Entry>()

    /**
     * The session for playlist [account] on portal [on] (the playlist itself, or a copy re-pointed at
     * one of its backup portals by PlaylistServerFailover), recreated if the playlist's Stalker config
     * changed since last time. Synchronized: check-then-create must not race into two sessions (two
     * handshakes rotating one MAC token).
     */
    @Synchronized
    fun sessionFor(account: XtreamAccount, on: XtreamAccount = account): StalkerSession {
        val fp = fingerprint(account)
        val existing = sessions[account.id]
        val entry = if (existing != null && existing.fingerprint == fp) existing else {
            // Config changed: the replaced sessions' watchdogs must not keep pinging the old identity.
            existing?.byPortal?.values?.forEach { it.shutdown() }
            Entry(fp).also { sessions[account.id] = it }
        }
        // Portal calls ride the playlist's own resolver, exactly like the Xtream/M3U/XMLTV clients.
        // Without this the one setting that can route around a broken/filtered system resolver had
        // no effect on the handshake — the very request that fails when a DNS block is the problem.
        return entry.byPortal.getOrPut(on.portalUrl) {
            StalkerSession(on, playlistDns.clientFor(http, on.dnsProvider))
        }
    }

    /** Drop a playlist's sessions (removed/edited) so the next access re-handshakes. */
    @Synchronized
    fun evict(accountId: String) { sessions.remove(accountId)?.byPortal?.values?.forEach { it.shutdown() } }

    @Synchronized
    fun clear() {
        sessions.values.forEach { entry -> entry.byPortal.values.forEach { it.shutdown() } }
        sessions.clear()
    }

    /** Any change to these invalidates the session (new handshake/device identity needed).
     *  dnsProvider is in here because the session holds a resolver-bound client: without it an
     *  edited DNS choice wouldn't reach the portal until the process restarted. */
    private fun fingerprint(a: XtreamAccount): String =
        listOf(a.portalUrl, a.macAddress, a.serialNumber, a.deviceId, a.sendDeviceId.toString(),
            a.deviceId2, a.signature, a.stbModel, a.hwVersion,
            a.stalkerUsername, a.stalkerPassword, a.dnsProvider, a.backupUrls.orEmpty().joinToString(",")).joinToString("|")
}

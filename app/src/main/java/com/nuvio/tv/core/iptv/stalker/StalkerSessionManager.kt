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
 * No keep-alive/watchdog: a portal expires an idle session after `watchdog_timeout` (~100–120s), but
 * the session re-handshakes on demand (single-flight — see [StalkerSession.reauthenticate]) the next
 * time it's used, which is exactly how a real STB behaves after it sleeps.
 */
@Singleton
class StalkerSessionManager @Inject constructor(
    @Named("stalker") private val http: OkHttpClient,
    private val playlistDns: PlaylistDns,
) {
    private data class Entry(val session: StalkerSession, val fingerprint: String)

    private val sessions = ConcurrentHashMap<String, Entry>()

    /** The session for [account], recreated if the account's Stalker config changed since last time. */
    fun sessionFor(account: XtreamAccount): StalkerSession {
        val fp = fingerprint(account)
        val existing = sessions[account.id]
        if (existing != null && existing.fingerprint == fp) return existing.session
        // Portal calls ride the playlist's own resolver, exactly like the Xtream/M3U/XMLTV clients.
        // Without this the one setting that can route around a broken/filtered system resolver had
        // no effect on the handshake — the very request that fails when a DNS block is the problem.
        val fresh = StalkerSession(account, playlistDns.clientFor(http, account.dnsProvider))
        sessions[account.id] = Entry(fresh, fp)
        return fresh
    }

    /** Drop a session (playlist removed/edited) so the next access re-handshakes. */
    fun evict(accountId: String) { sessions.remove(accountId) }

    fun clear() { sessions.clear() }

    /** Any change to these invalidates the session (new handshake/device identity needed).
     *  dnsProvider is in here because the session holds a resolver-bound client: without it an
     *  edited DNS choice wouldn't reach the portal until the process restarted. */
    private fun fingerprint(a: XtreamAccount): String =
        listOf(a.portalUrl, a.macAddress, a.serialNumber, a.deviceId, a.sendDeviceId.toString(),
            a.stalkerUsername, a.stalkerPassword, a.dnsProvider).joinToString("|")
}

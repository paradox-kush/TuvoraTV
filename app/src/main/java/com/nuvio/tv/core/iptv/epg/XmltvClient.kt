package com.nuvio.tv.core.iptv.epg

import android.util.Log
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.content.IptvContentDb
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Fetches + stores the XMLTV EPG for an M3U (URL or file) playlist so its live channels get real
 * now/next. There's no per-channel API like Xtream's get_short_epg — the whole guide is one big
 * XML document (often `.xml.gz`, 50–100MB+), so this:
 *
 *  1. Resolves the source: [XtreamAccount.epgUrl] (explicit) → else the M3U's `url-tvg`/`x-tvg-url`
 *     header captured during ingest (from [IptvContentDb.tvgUrl]).
 *  2. Fetches it via the shared m3u-ingest OkHttp client (follows redirects, transparent gzip,
 *     long read timeout, trust-all TLS — IPTV EPG hosts have the same bad certs as the playlists).
 *  3. Bounds size by querying the playlist's channel tvg-ids FIRST and STREAM-parsing (pull, never
 *     DOM) only programmes for those channels into `epg_programmes`, chunked, meta-stamped last.
 *
 * Refresh is throttled to ~[REFRESH_INTERVAL_MS] (2×/day): served from the DB otherwise. Fetch is
 * single-flight per playlist (a burst of shortEpg calls triggers one fetch, not N).
 */
@Singleton
class XmltvClient @Inject constructor(
    private val db: IptvContentDb,
    @Named("m3uIngest") private val http: OkHttpClient,
    private val playlistDns: com.nuvio.tv.core.iptv.dns.PlaylistDns,
) {

    private val lock = Mutex()
    private val inFlight = mutableSetOf<String>()      // playlist ids fetching now
    private val lastFailedMs = mutableMapOf<String, Long>()

    /**
     * Refresh this playlist's EPG if stale (or [force]). No-op when there's no resolvable EPG source
     * or no channels with tvg-ids. Single-flight + throttled + backed-off; NEVER throws (a failed
     * EPG just leaves the guide on "No information"). Suspends only long enough to claim the slot —
     * the actual fetch runs on the caller's IO context but is guarded so concurrent callers return
     * immediately.
     */
    suspend fun refreshIfStale(acc: XtreamAccount, force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val id = acc.id
        if (!force) {
            val builtAt = db.epgBuiltAt(id)
            if (builtAt != null && System.currentTimeMillis() - builtAt < REFRESH_INTERVAL_MS) return@withContext
        }

        val claimed = lock.withLock {
            if (id in inFlight) return@withLock false
            if (!force && System.currentTimeMillis() - (lastFailedMs[id] ?: 0L) < FAIL_BACKOFF_MS) return@withLock false
            inFlight.add(id); true
        }
        if (!claimed) return@withContext

        try {
            val source = resolveEpgUrl(acc)
            if (source == null) {
                Log.d(TAG, "No EPG source for ${acc.name} (no epgUrl, no url-tvg header)")
                return@withContext
            }
            val channelIds = db.channelTvgIds(id)
            if (channelIds.isEmpty()) {
                Log.d(TAG, "No tvg-ids in ${acc.name}'s channels — skipping EPG")
                return@withContext
            }
            fetchAndStore(acc, source, channelIds)
            lock.withLock { lastFailedMs.remove(id) }
        } catch (t: Throwable) {
            Log.w(TAG, "EPG fetch failed for ${acc.name}", t)
            lock.withLock { lastFailedMs[id] = System.currentTimeMillis() }
        } finally {
            lock.withLock { inFlight.remove(id) }
        }
    }

    /** Explicit epgUrl wins; otherwise the M3U header's url-tvg captured at ingest. */
    private suspend fun resolveEpgUrl(acc: XtreamAccount): String? =
        acc.epgUrl?.trim()?.takeIf { it.isNotEmpty() } ?: db.tvgUrl(acc.id)?.trim()?.takeIf { it.isNotEmpty() }

    /** Fetch the XMLTV document and stream-parse it (filtered to [channelIds]) into the DB. */
    private suspend fun fetchAndStore(acc: XtreamAccount, url: String, channelIds: Set<String>) {
        val request = Request.Builder()
            .url(url)
            .apply { acc.username.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) } }
            .build()
        // XMLTV fetch honours the playlist's DoH resolver (shares the ingest pool).
        playlistDns.clientFor(http, acc.dnsProvider).newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            // charStream() decodes the (possibly gunzipped) body incrementally — never fully buffered.
            val reader = resp.body.charStream().buffered()
            val parser = android.util.Xml.newPullParser().apply {
                setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(reader)
            }
            var count = 0
            db.replaceEpg(acc.id, System.currentTimeMillis()) { w ->
                XmltvParser.parseProgrammes(parser, channelIds) { programme ->
                    w.add(programme); count++
                }
            }
            Log.i(TAG, "EPG for ${acc.name}: stored $count programmes across ${channelIds.size} channels")
        }
    }

    companion object {
        private const val TAG = "XmltvClient"
        /** At most ~2×/day (spec) — served from the DB in between. */
        private const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L
        /** Don't hammer a failing EPG host on every browse. */
        private const val FAIL_BACKOFF_MS = 60 * 60 * 1000L
    }
}

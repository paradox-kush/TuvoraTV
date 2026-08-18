package com.nuvio.tv.core.iptv.epg

import android.util.Log
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.content.IptvContentDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import com.nuvio.tv.core.iptv.isXtream
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
    // Xtream lineups live here, not in IptvContentDb — the whole-guide allow-set needs whichever
    // store owns the account (see channelIdsFor).
    private val matchIndex: com.nuvio.tv.core.iptv.match.XtreamMatchIndex,
) {

    /**
     * The ingest's own scope. A whole-guide download outlives any screen that asks for it — the
     * 2026-08-18 mirror bug was exactly this mistake (viewModelScope cancelled a 76-second sync and
     * the completion stamp is written last, so it repeated forever). Never launch this from a
     * ViewModel's scope.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget [refreshIfStale] on the ingest's own scope. Safe to call on every visit. */
    fun warm(acc: XtreamAccount) {
        scope.launch { runCatching { refreshIfStale(acc) } }
    }

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
            val channelIds = channelIdsFor(acc)
            if (channelIds.isEmpty()) {
                Log.d(TAG, "No guide channel ids for ${acc.name} — skipping EPG")
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

    /**
     * Explicit epgUrl wins, then an Xtream account's own derived `xmltv.php`, then the M3U header's
     * url-tvg captured at ingest.
     *
     * The derived rung is what makes the whole-guide lane real for Xtream. Before it this answered
     * null for every Xtream playlist, so nothing was ever stored and the guide asked the panel once
     * per channel forever. A panel that does not serve xmltv.php fails the fetch once and the
     * ladder falls through to the per-channel rung — i.e. exactly the old behaviour.
     */
    private suspend fun resolveEpgUrl(acc: XtreamAccount): String? =
        acc.epgUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: derivedXmltvUrl(acc)
            ?: db.tvgUrl(acc.id)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * `{base}/xmltv.php?username=…&password=…` for an Xtream account, else null. Credentials are
     * encoded — panels do issue passwords containing `&` and `+`.
     */
    internal fun derivedXmltvUrl(acc: XtreamAccount): String? {
        if (!acc.isXtream()) return null
        val base = acc.baseUrl.trim().trimEnd('/').ifEmpty { return null }
        if (acc.username.isBlank() || acc.password.isBlank()) return null
        return "$base/xmltv.php?username=${acc.username.urlEncoded()}&password=${acc.password.urlEncoded()}"
    }

    private fun String.urlEncoded(): String = buildString(length) {
        for (ch in this@urlEncoded) {
            if (ch.isLetterOrDigit() || ch in "-_.~") append(ch)
            else for (b in ch.toString().toByteArray()) {
                append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
        }
    }

    /**
     * This playlist's channel ids, from whichever store owns its lineup. Empty is a normal answer:
     * a panel that leaves `epg_channel_id` blank cannot be matched by id at all.
     */
    private suspend fun channelIdsFor(acc: XtreamAccount): Set<String> =
        if (acc.isXtream()) matchIndex.liveEpgIds(acc.id)
        else db.channelTvgIds(acc.id)

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
            // checkNotNull: body is nullable on OkHttp 4 (playstore flavor) but not on 5 (full).
            val reader = checkNotNull(resp.body) { "empty response body" }.charStream().buffered()
            val parser = android.util.Xml.newPullParser().apply {
                setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(reader)
            }
            var count = 0
            // Bounded on the way IN ([XmltvIngestWindow]), not cleaned up afterwards: a feed
            // carrying a week of schedule for thousands of channels must never reach the disk in
            // the first place on a 1 GB box. The parse is streaming, so a refused row costs
            // nothing beyond the parse it already did.
            val nowMs = System.currentTimeMillis()
            db.replaceEpg(acc.id, nowMs) { w ->
                XmltvParser.parseProgrammes(parser, channelIds) { programme ->
                    if (XmltvIngestWindow.keeps(programme.startMs, programme.endMs, nowMs)) {
                        w.add(programme); count++
                    }
                }
            }
            Log.i(TAG, "EPG for ${acc.name}: stored $count programmes across ${channelIds.size} channels")
            com.nuvio.tv.core.epg.EpgTelemetry.ingestFinished(
                source = com.nuvio.tv.core.epg.EpgTelemetry.Source.PLAYLIST_XMLTV,
                outcome = if (count > 0) com.nuvio.tv.core.epg.EpgTelemetry.Outcome.OK
                else com.nuvio.tv.core.epg.EpgTelemetry.Outcome.EMPTY,
                programmes = count,
                channels = channelIds.size,
                durationMs = System.currentTimeMillis() - nowMs,
            )
        }
    }

    companion object {
        private const val TAG = "XmltvClient"
        private const val HEX = "0123456789ABCDEF"
        /** At most ~2×/day (spec) — served from the DB in between. */
        private const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L
        /** Don't hammer a failing EPG host on every browse. */
        private const val FAIL_BACKOFF_MS = 60 * 60 * 1000L
    }
}

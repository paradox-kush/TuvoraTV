package com.nuvio.tv.core.iptv.epg

import android.util.Log
import com.nuvio.tv.core.iptv.StreamUserAgentPolicy
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.epg.ChannelNameCleaner
import com.nuvio.tv.core.epg.GuideChannelMatcher
import com.nuvio.tv.core.iptv.content.EpgCensusRow
import com.nuvio.tv.core.iptv.content.EpgGuideChannelRow
import com.nuvio.tv.core.iptv.content.EpgMappingWrite
import com.nuvio.tv.core.iptv.content.EpgProgramme
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.executeCancellable
import com.nuvio.tv.core.iptv.forFailoverAttempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import com.nuvio.tv.core.iptv.isXtream
import okhttp3.Request
import okio.GzipSource
import okio.buffer
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
    /** Step 0.3: the panel's own derived xmltv.php is a catalog call and walks the backup servers. */
    private val failover: com.nuvio.tv.core.iptv.PlaylistServerFailover,
    /**
     * Step 0.3b: the failover race validates a backup with the Xtream login probe before the guide is
     * downloaded from it (null only in hand-built test graphs without an Xtream client: the walk is then
     * sequential).
     */
    private val xtream: com.nuvio.tv.core.iptv.XtreamClient? = null,
    /**
     * F14: the user's manual guide-channel picks (overlay kind "epg"). Null only in hand-built test
     * graphs, where no pick exists.
     */
    private val overlay: com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository? = null,
) {

    /**
     * The ingest's own scope. A whole-guide download outlives any screen that asks for it — the
     * 2026-08-18 mirror bug was exactly this mistake (viewModelScope cancelled a 76-second sync and
     * the completion stamp is written last, so it repeated forever). Never launch this from a
     * ViewModel's scope.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // A pick (here or pulled from another device / the web) whose programmes the last ingest did
        // not keep re-ingests that playlist once — a user action, never a timer.
        overlay?.let { o ->
            scope.launch {
                o.epgOverrideChanges.collect { ids -> for (id in ids) runCatching { reingestIfPickMissing(id) } }
            }
        }
    }

    /**
     * The accounts this client has been asked about, by id — a pick change names only a playlist id,
     * and the screen that opened the picker always warmed its account first.
     */
    private val knownAccounts = java.util.concurrent.ConcurrentHashMap<String, XtreamAccount>()

    /** Fire-and-forget [refreshIfStale] on the ingest's own scope. Safe to call on every visit. */
    fun warm(acc: XtreamAccount) {
        scope.launch { runCatching { refreshIfStale(acc) } }
    }

    /**
     * Emits the playlist id each time an ingest has swapped new programmes into the store. An open
     * guide that asked its rows before the ingest landed (and was told "nothing stored") re-asks on
     * this — see GuideDataRefreshPolicy. `DROP_OLDEST` + a buffer of 8: a collector that is gone
     * never blocks the ingest, and a burst keeps the latest ids.
     */
    private val _guideCommitted = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val guideCommitted: SharedFlow<String> = _guideCommitted.asSharedFlow()

    private val lock = Mutex()
    private val inFlight = mutableSetOf<String>()      // playlist ids fetching now
    private val lastFailedMs = mutableMapOf<String, Long>()

    /**
     * Refresh this playlist's EPG if stale (or [force]). No-op when there's no resolvable EPG source
     * or the lineup isn't known yet. Single-flight + throttled + backed-off; NEVER throws (a failed
     * EPG just leaves the guide on "No information"). Suspends only long enough to claim the slot —
     * the actual fetch runs on the caller's IO context but is guarded so concurrent callers return
     * immediately.
     */
    suspend fun refreshIfStale(acc: XtreamAccount, force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val id = acc.id
        knownAccounts[id] = acc
        val partition = partitionOf(acc)
        if (!force) {
            val builtAt = db.epgBuiltAt(partition)
            if (builtAt != null && System.currentTimeMillis() - builtAt < REFRESH_INTERVAL_MS) return@withContext
        }

        val claimed = lock.withLock {
            if (id in inFlight) return@withLock false
            val now = System.currentTimeMillis()
            if (!force && now - (lastFailedMs[id] ?: 0L) < FAIL_BACKOFF_MS) return@withLock false
            if (!force && now - (lineupNotReadyMs[id] ?: 0L) < LINEUP_RETRY_MS) return@withLock false
            inFlight.add(id); true
        }
        if (!claimed) return@withContext

        try {
            val sources = resolveSources(acc)
            if (sources.isEmpty()) {
                Log.d(TAG, "No EPG source for ${acc.name} (no epgUrl, no url-tvg header)")
                return@withContext
            }
            val lineup = lineupFor(acc)
            val picked = overlay?.epgOverrideGuideIds(id).orEmpty().map { normalizeChannelId(it) }.toHashSet()
            if (lineup.isEmpty() && picked.isEmpty()) {
                // The lineup isn't known YET (Xtream index still building, M3U not ingested): retry
                // shortly rather than stamping the guide "empty" for 12 h.
                Log.d(TAG, "No lineup yet for ${acc.name} — EPG retried in ${LINEUP_RETRY_MS / 1000}s")
                lock.withLock { lineupNotReadyMs[id] = System.currentTimeMillis() }
                return@withContext
            }
            lock.withLock { lineupNotReadyMs.remove(id) }
            fetchAndStore(acc, sources, lineup, picked)
            lock.withLock { lastFailedMs.remove(id) }
            // The swap committed (fetchAndStore throws before it when every source failed): tell any
            // open guide its "nothing stored" verdicts are stale.
            _guideCommitted.tryEmit(id)
        } catch (t: Throwable) {
            Log.w(TAG, "EPG fetch failed for ${acc.name}", t)
            lock.withLock { lastFailedMs[id] = System.currentTimeMillis() }
        } finally {
            lock.withLock { inFlight.remove(id) }
        }
    }

    private val lineupNotReadyMs = mutableMapOf<String, Long>()

    /**
     * The playlist's guide sources in priority order (F14, [EpgSourcePlan]): every URL the user typed,
     * then the Xtream account's own derived `xmltv.php`, then the M3U header's url-tvg list.
     *
     * The derived rung is what makes the whole-guide lane real for Xtream. Before it this answered
     * null for every Xtream playlist, so nothing was ever stored and the guide asked the panel once
     * per channel forever. A panel that does not serve xmltv.php fails the fetch once and the
     * ladder falls through to the per-channel rung — i.e. exactly the old behaviour.
     */
    internal suspend fun resolveSources(acc: XtreamAccount): List<EpgSource> =
        EpgSourcePlan.plan(
            explicit = acc.epgUrl,
            derivedXtream = derivedXmltvUrl(acc),
            urlTvg = if (acc.isXtream()) null else db.tvgUrl(acc.id),
        )

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

    /** The lineup the matcher maps, from whichever store owns it. */
    private suspend fun lineupFor(acc: XtreamAccount): List<GuideChannelMatcher.LineupChannel> =
        if (acc.isXtream()) {
            matchIndex.liveLineup(acc.id).map { GuideChannelMatcher.LineupChannel(it.sid, it.name, it.epgId) }
        } else {
            db.liveLineup(acc.id).map { (sid, name, tvg) -> GuideChannelMatcher.LineupChannel(sid, name, tvg) }
        }

    /**
     * Every source in priority order: stream it, match the lineup at the end of its channel list
     * ([GuideChannelMatcher], B10), keep the matched (and manually picked) channels' programmes, and
     * swap programmes + channel map + census in ONE transaction. A later source is only downloaded
     * while eligible channels are still unmatched; one source failing does not lose the others.
     */
    private suspend fun fetchAndStore(
        acc: XtreamAccount,
        sources: List<EpgSource>,
        lineup: List<GuideChannelMatcher.LineupChannel>,
        picked: Set<String>,
    ) {
        val startedAt = System.currentTimeMillis()
        val rules = acc.channelNameRules()
        val assignments = HashMap<Int, Pair<String, String>>()
        val guideRows = ArrayList<EpgGuideChannelRow>()
        var byId = 0; var byName = 0; var fuzzy = 0
        var attempted = 0; var failed = 0
        var lastError: Throwable? = null
        var count = 0
        var census: EpgCensusRow? = null
        db.replaceEpg(partitionOf(acc), startedAt, mapping = { EpgMappingWrite(assignments, guideRows, census!!) }) { writer ->
            var remaining = lineup
            for ((index, source) in sources.withIndex()) {
                if (index > 0 && remaining.none { GuideChannelMatcher.isEligible(it.name) }) break
                attempted++
                val result = try {
                    fetchSource(acc, index, source, remaining, picked, rules, guideRows) { p ->
                        writer.add(p); count++
                    }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    failed++; lastError = t
                    Log.w(TAG, "EPG source ${index + 1}/${sources.size} (${source.kind}) failed for ${acc.name}", t)
                    null
                } ?: continue
                val matched = HashSet<Int>(result.assignments.size)
                for (a in result.assignments) {
                    assignments[a.streamId] = EpgSourcePlan.storedKey(index, a.guideId) to a.tier.slug
                    matched.add(a.streamId)
                }
                byId += result.census.id; byName += result.census.name; fuzzy += result.census.fuzzy
                remaining = remaining.filter { it.streamId !in matched }
            }
            // Every attempted source failed: throw BEFORE the swap so the prior guide keeps serving.
            if (attempted > 0 && failed == attempted) throw lastError ?: IllegalStateException("every EPG source failed")
            census = EpgCensusRow(
                lineup = lineup.size,
                eligible = lineup.count { GuideChannelMatcher.isEligible(it.name) },
                manual = 0, byId = byId, byName = byName, fuzzy = fuzzy,
                sources = attempted, sourcesFailed = failed, builtAtMs = System.currentTimeMillis(),
            )
        }
        Log.i(TAG, "EPG for ${acc.name}: sources=$attempted failed=$failed stored $count programmes; lineup=${lineup.size} matched=${assignments.size} (id=$byId name=$byName)")
        com.nuvio.tv.core.epg.EpgTelemetry.ingestFinished(
            source = com.nuvio.tv.core.epg.EpgTelemetry.Source.PLAYLIST_XMLTV,
            outcome = if (count > 0) com.nuvio.tv.core.epg.EpgTelemetry.Outcome.OK
            else com.nuvio.tv.core.epg.EpgTelemetry.Outcome.EMPTY,
            programmes = count,
            channels = lineup.size,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /** One source: fetch (with failover for the derived panel guide), parse, match, emit kept rows. */
    private suspend fun fetchSource(
        acc: XtreamAccount,
        index: Int,
        source: EpgSource,
        lineup: List<GuideChannelMatcher.LineupChannel>,
        picked: Set<String>,
        rules: ChannelNameCleaner.Rules,
        guideRows: MutableList<EpgGuideChannelRow>,
        emit: (EpgProgramme) -> Unit,
    ): GuideChannelMatcher.Result {
        var result: GuideChannelMatcher.Result? = null
        val parse: (java.io.Reader) -> Unit = { reader ->
            val guide = ArrayList<GuideChannelMatcher.GuideChannel>()
            val parser = android.util.Xml.newPullParser().apply {
                setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(reader)
            }
            // Bounded on the way IN ([XmltvIngestWindow]): a week of schedule for thousands of
            // channels must never reach the disk on a 1 GB box.
            val nowMs = System.currentTimeMillis()
            XmltvParser.parseGuide(
                parser,
                onChannel = { id, names -> if (guide.size < MAX_GUIDE_CHANNELS) guide.add(GuideChannelMatcher.GuideChannel(id, names)) },
                // The DTD puts every <channel> before the first <programme>: match here, once.
                onChannelsDone = {
                    val r = GuideChannelMatcher.match(lineup, guide, rules)
                    result = r
                    val keep = HashSet<String>(r.assignments.size + picked.size)
                    r.assignments.forEach { keep.add(it.guideId) }
                    if (picked.isNotEmpty()) guide.forEach { g -> normalizeChannelId(g.id).let { if (it in picked) keep.add(it) } }
                    keep
                },
                onProgramme = { p ->
                    if (XmltvIngestWindow.keeps(p.startMs, p.endMs, nowMs)) {
                        emit(p.copy(channelId = EpgSourcePlan.storedKey(index, p.channelId)))
                    }
                },
            )
            for (g in guide) {
                val gid = normalizeChannelId(g.id)
                if (gid.isEmpty()) continue
                guideRows.add(EpgGuideChannelRow(EpgSourcePlan.storedKey(index, gid), gid, g.names.firstOrNull() ?: g.id, index))
            }
        }
        if (source.kind == EpgSourceKind.XTREAM_DERIVED) {
            // The panel's own xmltv.php is an EPG catalog call: it fails over with the playlist's
            // servers (Step 0.3) — until the first bytes reached the parser, never mid-guide.
            var delivered = false
            failover.run(acc, canRetry = { !delivered }, probe = xtream?.let { x -> { a -> x.failoverProbe(a) } }) { a ->
                fetchInto(acc, derivedXmltvUrl(a) ?: source.url, { delivered = true }, parse)
            }
        } else {
            // A custom EPG URL or the playlist's url-tvg lives on its own host — nothing to fail over to.
            fetchInto(acc, source.url, {}, parse)
        }
        return result ?: GuideChannelMatcher.Result(emptyList(), GuideChannelMatcher.Census(lineup.size, 0, 0, 0, 0))
    }

    private suspend fun fetchInto(acc: XtreamAccount, url: String, onFirstBytes: () -> Unit, parse: (java.io.Reader) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .apply { userAgentFor(acc)?.let { header("User-Agent", it) } }
            .build()
        // XMLTV fetch honours the playlist's DoH resolver (shares the ingest pool).
        // Step 0.3b: inside a failover race — 8 s connect timeout, header signal, cancel closes the socket.
        playlistDns.clientFor(http, acc.dnsProvider).forFailoverAttempt().newCall(request).executeCancellable { resp ->
            // Typed (message unchanged) so the failover walk can tell a dead panel (5xx/404) from a refusal.
            if (!resp.isSuccessful) throw com.nuvio.tv.core.iptv.HttpStatusException(resp.code, "HTTP ${resp.code}")
            // HTTP Content-Encoding is handled by OkHttp. A raw .xml.gz download has no
            // encoding header: inspect the decoded body's magic, without buffering the feed.
            val body = checkNotNull(resp.body) { "empty response body" }
            val raw = body.source()
            val gzipped = raw.request(2) && raw.buffer[0] == 0x1f.toByte() && raw.buffer[1] == 0x8b.toByte()
            val source = if (gzipped) GzipSource(raw).buffer() else raw
            source.use {
                val reader = com.nuvio.tv.core.iptv.FirstReadFlagReader(
                    it.inputStream().reader(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8),
                    onFirstBytes,
                ).buffered()
                parse(reader)
            }
        }
    }

    // --- store reads (B10 / F14) ---------------------------------------------------------------

    /**
     * Where this playlist's XMLTV lane is stored. Stalker's own bulk guide swaps the playlist's main
     * partition, so an explicit EPG URL on a Stalker playlist gets its own; Xtream/M3U keep the main
     * one (every existing reader — catch-up, sports search — reads it).
     */
    fun partitionOf(acc: XtreamAccount): String =
        if (acc.sourceType == XtreamAccount.SOURCE_STALKER) acc.id + IptvContentDb.XMLTV_PARTITION_SUFFIX else acc.id

    /** The stored key one channel reads by the ingest's match, else its provider id (pre-B10 rows). */
    suspend fun storedKeyFor(acc: XtreamAccount, streamId: Int): String? =
        EpgGuideKeyPolicy.resolve(
            manualGuideId = null,
            keyForGuideId = { null },
            mappedKey = db.epgGuideKey(partitionOf(acc), streamId),
            providerId = providerIdFor(acc, streamId),
        )

    /** The key of the active profile's manual pick for this channel (F14), or null. */
    suspend fun manualKeyFor(acc: XtreamAccount, streamId: Int): String? {
        val picks = overlay?.epgOverridesFor(acc.id).orEmpty()
        if (picks.isEmpty()) return null
        val entity = entityIdFor(acc, streamId) ?: return null
        val gid = picks[entity] ?: return null
        return db.epgGuideKeyForGuideId(partitionOf(acc), normalizeChannelId(gid))
    }

    /** The store rung for one channel by STREAM id: [] when it has no stored guide. */
    suspend fun storedNowNext(acc: XtreamAccount, streamId: Int, nowMs: Long): List<EpgProgramme> {
        val key = storedKeyFor(acc, streamId) ?: return emptyList()
        return db.epgNowNext(partitionOf(acc), key, nowMs)
    }

    /** The MANUAL rung (F14): null when the user has no pick for this channel. */
    suspend fun manualNowNext(acc: XtreamAccount, streamId: Int, nowMs: Long): List<EpgProgramme>? {
        val key = manualKeyFor(acc, streamId) ?: return null
        return db.epgNowNext(partitionOf(acc), key, nowMs)
    }

    /** Guide-window rows for one channel from the XMLTV lane: the pick first, then the match. */
    suspend fun storedWindow(acc: XtreamAccount, streamId: Int, fromMs: Long, toMs: Long): List<EpgProgramme> {
        val partition = partitionOf(acc)
        manualKeyFor(acc, streamId)?.let { key ->
            db.epgWindow(partition, key, fromMs, toMs).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val key = storedKeyFor(acc, streamId) ?: return emptyList()
        return db.epgWindow(partition, key, fromMs, toMs)
    }

    /** The guide channels this playlist's sources offer, for the manual picker (F14). */
    suspend fun guideChannels(acc: XtreamAccount, query: String, limit: Int = 200): List<EpgGuideChannelRow> =
        db.epgGuideChannels(partitionOf(acc), query, limit)

    /** The last ingest's coverage census (B10), or null before the first matched ingest. */
    suspend fun census(acc: XtreamAccount): EpgCensusRow? = db.epgCensus(partitionOf(acc))

    /** canon-v1 entity id of one live channel — the key a manual pick is stored under. */
    suspend fun entityIdFor(acc: XtreamAccount, streamId: Int): String? =
        if (acc.isXtream()) matchIndex.liveEntityIdFor(acc.id, streamId)
        else db.channelRow(acc.id, streamId)?.let {
            com.nuvio.tv.core.iptv.identity.IptvIdentity.entityId(acc.id, it.name, it.tvgId)
        }

    private suspend fun providerIdFor(acc: XtreamAccount, streamId: Int): String? =
        if (acc.isXtream()) matchIndex.liveEpgIdFor(acc.id, streamId)
        else db.channelRow(acc.id, streamId)?.tvgId

    /** Re-match + re-ingest now (the user changed something the match depends on). Ingest scope. */
    fun refreshNow(acc: XtreamAccount) {
        scope.launch { runCatching { refreshIfStale(acc, force = true) } }
    }

    /** Re-ingest a playlist only when one of its picks points at a guide channel the store lacks. */
    private suspend fun reingestIfPickMissing(playlistId: String) {
        val acc = knownAccounts[playlistId] ?: return
        val partition = partitionOf(acc)
        val now = System.currentTimeMillis()
        val missing = overlay?.epgOverridesFor(playlistId).orEmpty().values.toSet().any { gid ->
            val key = db.epgGuideKeyForGuideId(partition, normalizeChannelId(gid)) ?: return@any false
            db.epgNowNext(partition, key, now).isEmpty()
        }
        if (missing) refreshIfStale(acc, force = true)
    }

    companion object {
        /**
         * The playlist's own User-Agent for the guide request, or null for the client default (what the
         * Xtream API calls send). M3U playlists keep theirs in the username slot; an Xtream username is a
         * credential and was wrongly sent as the UA, so UA-gated panels refused the guide.
         */
        internal fun userAgentFor(acc: XtreamAccount): String? = StreamUserAgentPolicy.resolve(acc)

        private const val TAG = "XmltvClient"
        private const val HEX = "0123456789ABCDEF"
        /** At most ~2×/day (spec) — served from the DB in between. */
        private const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L
        /** Don't hammer a failing EPG host on every browse. */
        private const val FAIL_BACKOFF_MS = 60 * 60 * 1000L
        /** Lineup not built yet (new playlist): a short retry, not a 12 h "empty" stamp. */
        private const val LINEUP_RETRY_MS = 2 * 60 * 1000L
        /** Guide `<channel>` entries harvested per source; a bigger guide is parsed, just not all offered. */
        private const val MAX_GUIDE_CHANNELS = 100_000
    }
}

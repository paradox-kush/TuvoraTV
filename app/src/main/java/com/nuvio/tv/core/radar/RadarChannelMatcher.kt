package com.nuvio.tv.core.radar

import com.nuvio.tv.core.epg.EpgLang
import com.nuvio.tv.core.epg.EpgMirrorRepository
import com.nuvio.tv.core.epg.EpgNorm
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Which of MY channels is showing this match?" — TV twin of NuvioMobile's matcher.
 * Three signals, strongest first (see research/epg-matching in the project workspace):
 *
 *  1. Canonical-EPG programme search — the mirror's programme window is searched for the
 *     event (team/event tokens) and hits join back to provider channels through the
 *     persisted channel mappings. This is what finds "BBC One" for a World Cup match:
 *     the event study resolved 50-64 channels/event vs ≤10 for name matching alone.
 *  2. TheSportsDB broadcaster listings (via the edge function's tv action, premium key:
 *     61 stations for a WC quarter-final) — matched to channel names, carries the country.
 *  3. Channel-NAME matching (the original path) — still the only signal for panels whose
 *     channels map onto nothing and for league-branded 24/7 channels.
 *
 * Core scoring is source-agnostic over [CandidateChannel]s, and so is assembly: every enabled
 * playlist contributes its live lineup through [IptvClientFactory], so Xtream panels, M3U
 * playlists and Stalker portals all get matched (see radar-feature-requirements.md §5). The two
 * paths that stay Xtream-only are [replayFor] (timeshift has no Stalker/M3U equivalent) and
 * [findRecordings] (the TMDB match index only ever indexes Xtream catalogs).
 */
@Singleton
class RadarChannelMatcher @Inject constructor(
    private val xtreamClient: XtreamClient,
    private val accountStore: XtreamAccountStore,
    private val registry: XtreamItemRegistry,
    private val matchIndex: com.nuvio.tv.core.iptv.match.XtreamMatchIndex,
    private val resolver: com.nuvio.tv.core.iptv.match.XtreamTmdbResolver,
    private val epgMirror: EpgMirrorRepository,
    private val contentDb: com.nuvio.tv.core.iptv.content.IptvContentDb,
    private val clientFactory: IptvClientFactory,
) {
    data class CandidateChannel(
        val playlistId: String,
        val playlistName: String,
        val contentId: String,
        val name: String,
        val logo: String?,
        val streamId: Int,
        val streamUrl: String,
        /** The provider's own guide id for this channel — joins provider-EPG hits back. */
        val epgChannelId: String? = null,
        /** Channel offers catch-up (Xtream tv_archive) — enables Replay for past fixtures. */
        val hasArchive: Boolean = false,
    )

    /** A provider VOD entry that looks like a recording of the fixture. */
    data class RecordingHit(
        val contentId: String,
        val name: String,
        val poster: String?,
        val playlistName: String,
    )

    /** How a channel earned its place in the sheet (drives the "via EPG"/country chips). */
    enum class MatchVia { NAME, EPG, LISTING }

    data class ChannelMatch(
        val channel: CandidateChannel,
        val programme: XtreamProgram?,
        val score: Int,
        val via: MatchVia = MatchVia.NAME,
        /** Short language/region tag ("FR", "AR") or the broadcaster country ("France"). */
        val language: String? = null,
    )

    // Live lists once per account per session (26k channels on real panels).
    private val channelCache = ConcurrentHashMap<String, List<XtreamChannel>>()
    private val cacheMutex = Mutex()

    suspend fun match(
        fixture: RadarFixture,
        league: RadarLeague?,
        stations: List<RadarTvStation> = emptyList(),
        onPartial: (List<ChannelMatch>) -> Unit = {},
    ): List<ChannelMatch> {
        val keywords = buildList {
            league?.keywords?.forEach { add(normalize(it)) }
            fixture.league?.let { add(normalize(it)) }
        }.filter { it.isNotBlank() }.distinct()
        val homeTokens = teamTokens(fixture.home)
        val awayTokens = teamTokens(fixture.away)
        val eventTokens = if (homeTokens.isEmpty() && awayTokens.isEmpty()) teamTokens(fixture.event) else emptyList()

        val candidates = assembleCandidates()

        val named = candidates.mapNotNull { c ->
            val score = nameScore(normalize(c.name), keywords, homeTokens, awayTokens, eventTokens)
            if (score > 0) ChannelMatch(c, programme = null, score = score) else null
        }.sortedByDescending { it.score }.take(NAME_POOL_CAP)

        // Same rule for the early partial: flashing a list of guesses and then clearing it
        // when the guide tiers land is worse than showing the spinner a moment longer.
        if (named.any { it.score > GENERIC_NAME_SCORE }) onPartial(named.take(RESULT_CAP))

        val start = fixture.startEpochMs
        val probed = if (start == null) named else coroutineScope {
            val semaphore = Semaphore(EPG_CONCURRENCY)
            named.take(EPG_PROBE_CAP).map { m ->
                async {
                    semaphore.withPermit {
                        val programmes = epgFor(m.channel)
                        val hit = bestProgramme(programmes, start, keywords, homeTokens, awayTokens, eventTokens)
                        if (hit != null) m.copy(programme = hit.first, score = m.score / 10 + hit.second) else m
                    }
                }
            }.awaitAll() + named.drop(EPG_PROBE_CAP)
        }

        // Canonical-EPG event hits joined back through the persisted channel mappings —
        // finds every mapped channel whose guide says it airs this event, regardless of name.
        val mirrorMatches = mirrorMatches(candidates, start, keywords, homeTokens, awayTokens, eventTokens)
        // The provider's OWN guide, searched in bulk. Same name-independence as the mirror but
        // without needing a mapping, so it reaches channels the mirror's public feeds don't
        // cover — which is most of them outside the UK/EU.
        val providerEpg = providerEpgMatches(candidates, start, keywords, homeTokens, awayTokens, eventTokens)
        // TheSportsDB broadcasters matched to channel names (carries the country label).
        val stationMatches = stationMatches(candidates, stations)

        // Merge, best score per channel; keep any programme/language a weaker signal found.
        val merged = LinkedHashMap<String, ChannelMatch>()
        for (m in mirrorMatches + providerEpg + stationMatches + probed) {
            merged.merge(m.channel.contentId, m) { old, new ->
                val best = if (new.score > old.score) new else old
                best.copy(
                    programme = best.programme ?: new.programme ?: old.programme,
                    language = best.language ?: new.language ?: old.language,
                )
            }
        }
        val ranked = merged.values.sortedByDescending { it.score }
        // A generic hit is a guess, not an answer: it means the channel merely has "sport" in
        // its name — no league keyword, no team, no guide entry. A list of those reads as
        // "here's where the match is" and sends someone into a Bulgarian feed for a Mexican
        // fixture. When that's ALL we have, report nothing so the sheet can say so honestly.
        if (ranked.none { it.score > GENERIC_NAME_SCORE }) return emptyList()
        // Generic sports-channel name hits (score <= GENERIC_NAME_SCORE) don't earn slots
        // beyond the classic list length — EPG/listing hits do.
        return ranked
            .filterIndexed { i, m -> i < NAME_RESULT_CAP || m.score > GENERIC_NAME_SCORE }
            .take(RESULT_CAP)
    }

    /**
     * Tier-1b: the provider's own ingested XMLTV, searched in bulk for the fixture and joined
     * back by the channel's own guide id.
     *
     * Costs one local query per playlist and no network, so unlike the get_short_epg probe it
     * doesn't need a channel-name filter in front of it. That filter is what made a Liga MX
     * fixture unmatchable: a Mexican channel scored 0 on name, was dropped before any guide
     * was consulted, and the sheet fell through to generic "has the word sport in it" hits.
     */
    private suspend fun providerEpgMatches(
        candidates: List<CandidateChannel>,
        startMs: Long?,
        keywords: List<String>,
        homeTokens: List<String>,
        awayTokens: List<String>,
        eventTokens: List<String>,
    ): List<ChannelMatch> {
        if (startMs == null) return emptyList()
        // Same token discipline as the mirror: short tokens match too much to be worth a scan.
        val tokens = (homeTokens + awayTokens + eventTokens).filter { it.length > 3 }.distinct().take(8)
        if (tokens.isEmpty()) return emptyList()
        val from = startMs - PROGRAMME_WINDOW_BACK_MS
        val to = startMs + PROGRAMME_WINDOW_AHEAD_MS

        return buildList {
            for ((playlistId, chans) in candidates.groupBy { it.playlistId }) {
                val byEpgId = chans.mapNotNull { c -> c.epgChannelId?.takeIf { it.isNotBlank() }?.let { it to c } }.toMap()
                if (byEpgId.isEmpty()) continue
                val hits = runCatching { contentDb.epgSearch(playlistId, tokens, from, to) }
                    .getOrDefault(emptyList())
                for (p in hits) {
                    val channel = byEpgId[p.channelId] ?: continue
                    val score = programmeScore(
                        normalize("${'$'}{p.title} ${'$'}{p.desc.orEmpty()}"),
                        keywords, homeTokens, awayTokens, eventTokens,
                    )
                    if (score <= 0) continue
                    add(
                        ChannelMatch(
                            channel,
                            programme = XtreamProgram(p.title, p.desc.orEmpty(), p.startMs, p.endMs, false),
                            score = MIRROR_BASE_SCORE + score / 10,
                            via = MatchVia.EPG,
                        )
                    )
                }
            }
        }
    }

    /** Tier-1: search the mirrored programme window for the event, join via mappings. */
    private suspend fun mirrorMatches(
        candidates: List<CandidateChannel>,
        startMs: Long?,
        keywords: List<String>,
        homeTokens: List<String>,
        awayTokens: List<String>,
        eventTokens: List<String>,
    ): List<ChannelMatch> {
        if (startMs == null) return emptyList()
        val sqlTokens = (homeTokens + awayTokens + eventTokens).filter { it.length > 3 }.distinct().take(8)
        if (sqlTokens.isEmpty()) return emptyList()
        val hits = runCatching {
            epgMirror.programmesInWindow(sqlTokens, startMs - PROGRAMME_WINDOW_BACK_MS, startMs + PROGRAMME_WINDOW_AHEAD_MS)
        }.getOrDefault(emptyList())
            .mapNotNull { p ->
                val score = programmeScore(normalize("${p.title} ${p.desc.orEmpty()}"), keywords, homeTokens, awayTokens, eventTokens)
                if (score > 0) Triple(p.channelId, p, score) else null
            }
            .groupBy { it.first }
            .mapValues { (_, l) -> l.maxBy { it.third } }
        if (hits.isEmpty()) return emptyList()

        return buildList {
            for ((playlistId, chans) in candidates.groupBy { it.playlistId }) {
                val mapping = runCatching { epgMirror.mappingFor(playlistId) }.getOrDefault(emptyMap())
                if (mapping.isEmpty()) continue
                for (c in chans) {
                    val epgId = mapping[c.streamId] ?: continue
                    val (_, p, score) = hits[epgId] ?: continue
                    add(
                        ChannelMatch(
                            channel = c,
                            programme = XtreamProgram(p.title, p.desc.orEmpty(), p.startMs, p.endMs, nowPlaying = false),
                            score = MIRROR_BASE_SCORE + score / 10,
                            via = MatchVia.EPG,
                            language = EpgLang.of(epgId, c.name, p.title),
                        )
                    )
                }
            }
        }
    }

    /** Tier-2: broadcaster names from TheSportsDB matched against candidate channel names. */
    private fun stationMatches(
        candidates: List<CandidateChannel>,
        stations: List<RadarTvStation>,
    ): List<ChannelMatch> {
        if (stations.isEmpty()) return emptyList()
        val byCore = HashMap<String, MutableList<CandidateChannel>>()
        val bySquash = HashMap<String, MutableList<CandidateChannel>>()
        for (c in candidates) {
            val core = EpgNorm.coreNorm(c.name)
            if (core.isEmpty()) continue
            byCore.getOrPut(core) { mutableListOf() }.add(c)
            bySquash.getOrPut(EpgNorm.squash(core)) { mutableListOf() }.add(c)
        }
        return buildList {
            for (st in stations) {
                val raw = st.channel ?: continue
                val core = EpgNorm.coreNorm(raw)
                if (core.isEmpty()) continue
                val tries = LinkedHashSet<String>()
                tries.add(core)
                tries.add(dropStationCountryTail(core))
                for (t in tries) {
                    if (t.isEmpty()) continue
                    val found = byCore[t] ?: bySquash[EpgNorm.squash(t)] ?: continue
                    for (c in found) {
                        add(ChannelMatch(c, programme = null, score = LISTING_SCORE, via = MatchVia.LISTING, language = st.country))
                    }
                    break
                }
            }
        }
    }

    /** "bein sports 1 france" -> "bein sports 1" (listing names often carry the country). */
    private fun dropStationCountryTail(core: String): String {
        val toks = core.split(" ")
        return if (toks.size > 1 && toks.last() in STATION_COUNTRY_TAILS) toks.dropLast(1).joinToString(" ") else core
    }

    /**
     * Catch-up Replay for a started/finished fixture on an archived channel: registers a
     * synthetic live item carrying the timeshift URL and returns (contentId, url, title) —
     * plays through the same live route as everything else. Null when no archive/not started.
     */
    suspend fun replayFor(match: ChannelMatch, fixture: RadarFixture): Triple<String, String, String>? {
        val start = fixture.startEpochMs ?: return null
        if (!match.channel.hasArchive || start > RadarTime.nowMs()) return null
        val account = accountStore.accounts.first().firstOrNull { it.id == match.channel.playlistId }
            ?: return null
        // Timeshift/catch-up is an Xtream-only feature — there's no interface method for Stalker/M3U.
        // Offering Replay for those would register a bogus fabricated URL, so skip it. (Assembly is
        // Xtream-only today, so this is the guard that keeps Replay honest once it isn't.)
        if (!account.isXtream()) return null
        val programme = match.programme
        val replayStart = programme?.startMs?.takeIf { it > 0 } ?: (start - 15 * 60 * 1000L)
        val durationMin = (((programme?.endMs ?: 0L) - (programme?.startMs ?: 0L)) / 60_000L)
            .toInt().takeIf { it in 30..360 } ?: 165
        val url = xtreamClient.liveTimeshiftUrl(account, match.channel.streamId, replayStart, durationMin)
        val title = "${match.channel.name} · Replay"
        val contentId = "${match.channel.contentId}r${replayStart / 60_000L}"
        registry.register(
            XtreamResolvedItem(
                id = contentId, type = ContentType.TV, name = title, poster = match.channel.logo,
                streamUrl = url, kind = XtreamKind.LIVE, accountId = account.id, streamId = match.channel.streamId,
            )
        )
        return Triple(contentId, url, title)
    }

    /**
     * Provider VOD entries that look like recordings of this fixture, from the SAME SQLite
     * catalog index the TMDB matcher builds. Registered so OK opens the native detail.
     */
    suspend fun findRecordings(fixture: RadarFixture): List<RecordingHit> {
        val start = fixture.startEpochMs ?: return emptyList()
        if (start > RadarTime.nowMs()) return emptyList()
        val homeTokens = teamTokens(fixture.home)
        val awayTokens = teamTokens(fixture.away)
        val eventTokens = teamTokens(fixture.event)
        val queries = buildList {
            homeTokens.firstOrNull()?.let(::add)
            awayTokens.firstOrNull()?.let(::add)
            if (isEmpty()) eventTokens.take(2).forEach(::add)
        }.distinct()
        if (queries.isEmpty()) return emptyList()

        // Only real Xtream panels: the match index + player_api VOD URLs don't exist for M3U/Stalker.
        val accounts = accountStore.accounts.first().filter { it.enabled && it.isXtream() }
        val hits = LinkedHashMap<String, RecordingHit>()
        for (account in accounts) {
            kotlinx.coroutines.withTimeoutOrNull(INDEX_WAIT_MS) {
                resolver.ensureIndexed(account, com.nuvio.tv.core.iptv.match.MatchKind.MOVIE)
            }
            for (q in queries) {
                matchIndex.searchByName(account.id, com.nuvio.tv.core.iptv.match.MatchKind.MOVIE, q, 30).forEach { item ->
                    val text = normalize(item.name)
                    val bothTeams = homeTokens.any { hits(text, it) } && awayTokens.any { hits(text, it) }
                    val eventMatch = eventTokens.isNotEmpty() && eventTokens.count { hits(text, it) } >= 2
                    if (!bothTeams && !eventMatch) return@forEach
                    val contentId = XtreamItemRegistry.vodId(account.id, item.sid)
                    registry.register(
                        XtreamResolvedItem(
                            id = contentId, type = ContentType.MOVIE, name = item.name, poster = item.poster,
                            streamUrl = xtreamClient.buildStreamUrl(account, "movie", item.sid, item.ext ?: "mp4"),
                            accountId = account.id, streamId = item.sid,
                        )
                    )
                    hits.getOrPut(contentId) { RecordingHit(contentId, item.name, item.poster, account.name) }
                }
            }
            if (hits.size >= RECORDING_CAP) break
        }
        return hits.values.take(RECORDING_CAP)
    }

    /**
     * The URL to hand the player for a matched channel. Xtream and M3U carry a browse-time URL;
     * Stalker channels list with a blank one because the portal mints a single-use link per play,
     * so those resolve a FRESH `create_link` here — the same rule the live guide plays by. Null
     * when the source can't produce one (dead portal session, item gone from an M3U catalog).
     */
    suspend fun playbackUrlFor(match: ChannelMatch): String? {
        if (match.channel.streamUrl.isNotBlank()) return match.channel.streamUrl
        val account = accountStore.accounts.first().firstOrNull { it.id == match.channel.playlistId }
            ?: return null
        return clientFactory.clientFor(account)
            .resolveStreamUrl(account, "live", match.channel.streamId)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Registers the match's channel so the player route can resolve it like any live id.
     * [streamUrl] is the resolved URL from [playbackUrlFor] — never `match.channel.streamUrl`,
     * which is blank for Stalker.
     */
    fun ensurePlayable(match: ChannelMatch, streamUrl: String) {
        if (registry.get(match.channel.contentId) != null) return
        registry.register(
            XtreamResolvedItem(
                id = match.channel.contentId,
                type = ContentType.TV,
                name = match.channel.name,
                poster = match.channel.logo,
                streamUrl = streamUrl,
                kind = XtreamKind.LIVE,
                accountId = match.channel.playlistId,
                streamId = match.channel.streamId,
            )
        )
    }

    fun resetForProfile() {
        channelCache.clear()
    }

    // --- source assembly ------------------------------------------------------

    private suspend fun assembleCandidates(): List<CandidateChannel> {
        // Every enabled playlist, whatever its source: the factory hands back the Xtream
        // player_api client, the M3U catalog client, or the Stalker portal session as needed.
        val accounts = accountStore.accounts.first().filter { it.enabled }
        return accounts.flatMap { account ->
            val channels = channelCache[account.id] ?: cacheMutex.withLock {
                channelCache[account.id] ?: clientFactory.clientFor(account).liveChannels(account)
                    .getOrDefault(emptyList())
                    // Only cache success — this is an app-lifetime singleton, and caching a
                    // transient panel failure would leave matching dead until restart.
                    .also { if (it.isNotEmpty()) channelCache[account.id] = it }
            }
            channels.map { ch ->
                CandidateChannel(
                    playlistId = account.id,
                    playlistName = account.name,
                    contentId = XtreamItemRegistry.liveId(account.id, ch.streamId),
                    name = ch.name,
                    logo = ch.logo,
                    streamId = ch.streamId,
                    streamUrl = ch.streamUrl,
                    epgChannelId = ch.epgChannelId,
                    hasArchive = ch.hasArchive,
                )
            }
        }
    }

    private suspend fun epgFor(channel: CandidateChannel): List<XtreamProgram> {
        val account = accountStore.accounts.first().firstOrNull { it.id == channel.playlistId }
            ?: return emptyList()
        // Source-correct: Stalker answers get_short_epg (or its bulk EPG), M3U has no per-channel
        // guide and returns empty — the mirror tier still covers M3U channels that map to an id.
        return clientFactory.clientFor(account).shortEpg(account, channel.streamId, limit = 8)
            .getOrDefault(emptyList())
    }

    // --- scoring (pure) --------------------------------------------------------

    private fun nameScore(
        name: String,
        keywords: List<String>,
        homeTokens: List<String>,
        awayTokens: List<String>,
        eventTokens: List<String>,
    ): Int {
        if (name.isBlank()) return 0
        val homeHit = homeTokens.any { hits(name, it) }
        val awayHit = awayTokens.any { hits(name, it) }
        val keywordHit = keywords.any { hits(name, it) }
        val eventHit = eventTokens.count { hits(name, it) } >= 2
        val genericHit = GENERIC_SPORT_MARKERS.any { name.contains(it) }
        return when {
            homeHit && awayHit -> 50
            keywordHit -> 25
            eventHit -> 20
            homeHit || awayHit -> 12
            genericHit -> 8
            else -> 0
        }
    }

    private fun bestProgramme(
        programmes: List<XtreamProgram>,
        startMs: Long,
        keywords: List<String>,
        homeTokens: List<String>,
        awayTokens: List<String>,
        eventTokens: List<String>,
    ): Pair<XtreamProgram, Int>? {
        val windowStart = startMs - PROGRAMME_WINDOW_BACK_MS
        val windowEnd = startMs + PROGRAMME_WINDOW_AHEAD_MS
        return programmes
            .filter { it.endMs > windowStart && it.startMs < windowEnd }
            .mapNotNull { p ->
                val score = programmeScore(normalize("${p.title} ${p.description}"), keywords, homeTokens, awayTokens, eventTokens)
                if (score > 0) p to score else null
            }
            .maxByOrNull { it.second }
    }

    /** Shared programme-text scoring for panel short_epg and the canonical mirror. */
    private fun programmeScore(
        text: String,
        keywords: List<String>,
        homeTokens: List<String>,
        awayTokens: List<String>,
        eventTokens: List<String>,
    ): Int {
        if (text.isBlank()) return 0
        val home = homeTokens.any { hits(text, it) }
        val away = awayTokens.any { hits(text, it) }
        val keyword = keywords.any { hits(text, it) }
        val event = eventTokens.count { hits(text, it) } >= 2
        return when {
            home && away -> 100
            event -> 90
            (home || away) && keyword -> 70
            keyword -> 35
            home || away -> 25
            else -> 0
        }
    }

    private fun normalize(s: String?): String =
        (s ?: "").lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(" ").filter { it.isNotBlank() }.joinToString(" ")

    /**
     * Short single tokens must match on WORD BOUNDARIES — plain substring makes "epl" hit
     * "replay" and "wc" hit anything — while longer/multi-word keywords keep substring
     * semantics ("premier league" should hit "premier league tv").
     */
    private fun hits(normalizedText: String, keyword: String): Boolean =
        if (keyword.length < 5 && ' ' !in keyword) " $normalizedText ".contains(" $keyword ")
        else normalizedText.contains(keyword)

    private fun teamTokens(team: String?): List<String> =
        normalize(team).split(" ").filter { it.length > 2 && it !in STOP_TOKENS }

    private companion object {
        const val NAME_POOL_CAP = 200
        const val EPG_PROBE_CAP = 40
        const val EPG_CONCURRENCY = 8
        /** Sheet capacity now that EPG/listing tiers surface worldwide airings (was 10). */
        const val RESULT_CAP = 40
        /** Classic list length — name-only generic hits never rank past this. */
        const val NAME_RESULT_CAP = 10
        const val GENERIC_NAME_SCORE = 8
        /** Mirror-EPG hits outrank every name tier; listing hits sit between. */
        const val MIRROR_BASE_SCORE = 100
        const val LISTING_SCORE = 80
        const val PROGRAMME_WINDOW_BACK_MS = 45 * 60 * 1000L
        const val PROGRAMME_WINDOW_AHEAD_MS = 4 * 60 * 60 * 1000L
        const val RECORDING_CAP = 6
        const val INDEX_WAIT_MS = 12_000L

        /** Trailing country words TheSportsDB appends to station names ("M4 Sport HU"). */
        val STATION_COUNTRY_TAILS = setOf(
            "uk", "us", "usa", "ca", "au", "nz", "fr", "france", "de", "germany", "it", "italy",
            "es", "spain", "pt", "portugal", "nl", "netherlands", "be", "mx", "mexico", "br",
            "brazil", "ar", "argentina", "rs", "serbia", "hu", "hr", "si", "sk", "cz", "pl",
            "ro", "bg", "gr", "tr", "il", "za", "ie", "ireland", "is", "iceland", "no", "norway",
            "se", "sweden", "fi", "finland", "dk", "denmark", "ch", "at", "hd",
        )

        // Compared against normalize()d names — punctuation is already stripped.
        val GENERIC_SPORT_MARKERS = listOf(
            "sport", "espn", "bein", "dazn", "eurosport", "supersport", "fox sports",
            "sky sports", "tnt sports", "arena", "setanta", "premier sports",
        )
        val STOP_TOKENS = setOf("fc", "cf", "sc", "afc", "rc", "cd", "ac", "de", "the", "club", "los", "las")
    }
}

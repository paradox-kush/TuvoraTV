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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
    private val catchUp: com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator,
    private val refreshStore: com.nuvio.tv.core.iptv.refresh.IptvRefreshStore,
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
        val catalogRevision: String = "",
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
        /** Evidence tier: time-aligned guide/listing, possible event identity, or competition only. */
        val confidence: MatchConfidence = MatchConfidence.LEAGUE,
        val evidence: SportsMatchEvidence? = null,
    )

    // Live lists once per account per session (26k channels on real panels).
    private val channelCache = ConcurrentHashMap<String, List<XtreamChannel>>()
    private val cacheMutex = Mutex()
    private val matcherGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun match(
        fixture: RadarFixture,
        league: RadarLeague?,
        stations: List<RadarTvStation> = emptyList(),
        stationLookup: (suspend () -> List<RadarTvStation>)? = null,
        onPartial: (List<ChannelMatch>) -> Unit = {},
    ): List<ChannelMatch> = withContext(Dispatchers.Default) {
        val stationTask = stationLookup?.let { lookup -> async {
            try { withTimeoutOrNull(4_000L) { lookup() }.orEmpty() }
            catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
        } }
        val generation = matcherGeneration.get()
        val keywords = league?.keywords.orEmpty()
        val prepared = SportsEventMatchEngine.prepare(fixture, keywords)
        val homeRegion = SportsBroadcastRegionPolicy.regionOfCountry(fixture.country)
        val candidates = assembleCandidates()
        val named = candidates.mapNotNull { c ->
            val evidence = SportsEventMatchEngine.name(prepared, c.name)
            if (evidence.strength == 0) return@mapNotNull null
            ChannelMatch(c, programme = null,
                score = evidence.strength + SportsBroadcastRegionPolicy.homeRegionBoost(c.name, homeRegion),
                confidence = evidence.confidence, evidence = evidence)
        }.sortedWith(matchOrder).take(NAME_POOL_CAP)
        if (generation != matcherGeneration.get()) return@withContext emptyList()
        onPartial(mergeMatches(named))

        // Read the local guides before any provider probes. These queries never fetch a catalog.
        val cached = coroutineScope {
            val mirror = async { mirrorMatches(candidates, prepared) }
            val provider = async { providerEpgMatches(candidates, prepared) }
            mirror.await() + provider.await() + stationMatches(candidates, stations, homeRegion)
        }
        if (generation != matcherGeneration.get()) return@withContext emptyList()
        onPartial(mergeMatches(named + cached))
        val local = cached + stationMatches(candidates, stationTask?.await().orEmpty(), homeRegion)
        if (generation != matcherGeneration.get()) return@withContext emptyList()
        onPartial(mergeMatches(named + local))
        val resolved = local.filter { it.via == MatchVia.EPG && it.confidence == MatchConfidence.CONFIRMED }
            .map { it.channel.contentId }.toSet()
        // Generic sports channels are probe candidates only. They never pad displayed results.
        val probeCandidates = (named.map { it.channel } + candidates.filter { c ->
            GENERIC_SPORT_MARKERS.any { marker -> normalize(c.name).contains(marker) }
        }).distinctBy { it.contentId }.filterNot { it.contentId in resolved }.take(EPG_PROBE_CAP)
        val probed = if (fixture.startEpochMs == null || resolved.isNotEmpty()) emptyList() else coroutineScope {
            val semaphore = Semaphore(EPG_CONCURRENCY)
            probeCandidates.map { c -> async {
                semaphore.withPermit {
                    val programmes = withTimeoutOrNull(3_000L) { cachedProbe(c) }.orEmpty()
                    bestProgramme(programmes, prepared)?.let { hit ->
                        ChannelMatch(c, programme = hit.first,
                            score = MIRROR_BASE_SCORE + hit.second.strength / 10,
                            via = MatchVia.EPG, confidence = hit.second.confidence, evidence = hit.second)
                    }
                }
            } }.awaitAll().filterNotNull()
        }
        if (generation != matcherGeneration.get()) emptyList() else mergeMatches(named + local + probed)
    }

    private val matchOrder = compareByDescending<ChannelMatch> { SportsEventMatchEngine.rank(it.confidence) }
        .thenByDescending { it.evidence?.strength ?: it.score }
        .thenByDescending { it.score }
        .thenBy { it.channel.contentId }

    /** Keep source, confidence and programme together; preferences are only a tie-breaker. */
    internal fun mergeMatches(matches: List<ChannelMatch>): List<ChannelMatch> = matches
        .filter { it.evidence?.strength != 0 }
        .sortedWith(matchOrder).distinctBy { it.channel.contentId }.take(RESULT_CAP)

    private val probeMutex = Mutex()
    private val probeCache = mutableMapOf<String, Pair<Long, List<XtreamProgram>>>()
    private val probeLocks = List(16) { Mutex() }

    private suspend fun cachedProbe(channel: CandidateChannel): List<XtreamProgram> {
        val key = matcherGeneration.get().toString() + ":" + channel.playlistId + ":" + channel.streamId + ":" + channel.name + ":" + channel.epgChannelId + ":" + channel.catalogRevision
        val lock = probeLocks[(key.hashCode() and Int.MAX_VALUE) % probeLocks.size]
        return lock.withLock probe@{
            val now = RadarTime.nowMs()
            val cached = probeMutex.withLock { probeCache[key] }
            if (cached != null && now >= cached.first && now - cached.first < 5 * 60_000L) return@probe cached.second
            val programmes = try { epgFor(channel) }
                catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
            probeMutex.withLock {
                if (probeCache.size >= 128) {
                    val oldest = probeCache.minByOrNull { it.value.first }?.key
                    if (oldest != null) probeCache.remove(oldest)
                }
                probeCache[key] = now to programmes
            }
            programmes
        }
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
        prepared: SportsEventMatchEngine.Prepared,
    ): List<ChannelMatch> {
        val startMs = prepared.fixture.startEpochMs ?: return emptyList()
        val tokens = prepared.searchTokens
        if (tokens.isEmpty()) return emptyList()
        return buildList {
            for ((playlistId, chans) in candidates.groupBy { it.playlistId }) {
                val byEpgId = chans.filter { !it.epgChannelId.isNullOrBlank() }.groupBy { it.epgChannelId }
                if (byEpgId.isEmpty()) continue
                val hits = try { contentDb.epgSearch(playlistId, tokens,
                    startMs - PROGRAMME_WINDOW_BACK_MS, startMs + PROGRAMME_WINDOW_AHEAD_MS)
                } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
                for (p in hits) {
                    val scored = SportsEventMatchEngine.programme(prepared, p.title, p.desc.orEmpty(), p.startMs, p.endMs)
                    if (scored.strength == 0) continue
                    for (channel in byEpgId[p.channelId].orEmpty()) add(ChannelMatch(
                        channel, programme = XtreamProgram(p.title, p.desc.orEmpty(), p.startMs, p.endMs, false),
                        score = MIRROR_BASE_SCORE + scored.strength / 10,
                        via = MatchVia.EPG, confidence = scored.confidence, evidence = scored))
                }
            }
        }
    }

    private suspend fun mirrorMatches(
        candidates: List<CandidateChannel>,
        prepared: SportsEventMatchEngine.Prepared,
    ): List<ChannelMatch> {
        val startMs = prepared.fixture.startEpochMs ?: return emptyList()
        if (prepared.searchTokens.isEmpty()) return emptyList()
        val hits = try { epgMirror.programmesInWindow(prepared.searchTokens,
            startMs - PROGRAMME_WINDOW_BACK_MS, startMs + PROGRAMME_WINDOW_AHEAD_MS)
        } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
        val byGuide = hits.mapNotNull { p ->
            val scored = SportsEventMatchEngine.programme(prepared, p.title, p.desc.orEmpty(), p.startMs, p.endMs)
            if (scored.strength > 0) Triple(p.channelId, p, scored) else null
        }.groupBy { it.first }.mapValues { (_, rows) -> rows.maxBy { SportsEventMatchEngine.rank(it.third.confidence) * 1000 + it.third.strength } }
        if (byGuide.isEmpty()) return emptyList()
        return buildList {
            for ((playlistId, chans) in candidates.groupBy { it.playlistId }) {
                val mapping = try { epgMirror.mappingFor(playlistId) }
                    catch (error: CancellationException) { throw error } catch (_: Exception) { emptyMap() }
                for (c in chans) {
                    val epgId = mapping[c.streamId] ?: continue
                    val (_, p, scored) = byGuide[epgId] ?: continue
                    add(ChannelMatch(c, programme = XtreamProgram(p.title, p.desc.orEmpty(), p.startMs, p.endMs, false),
                        score = MIRROR_BASE_SCORE + scored.strength / 10, via = MatchVia.EPG,
                        language = EpgLang.of(epgId, c.name, p.title), confidence = scored.confidence, evidence = scored))
                }
            }
        }
    }

    private fun stationMatches(
        candidates: List<CandidateChannel>,
        stations: List<RadarTvStation>,
        homeRegion: String?,
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
                // Exact whole-name first, then the country-tail-dropped brand ("NFL Network US" -> "nfl network").
                val brand = dropStationCountryTail(core)
                val found = byCore[core] ?: bySquash[EpgNorm.squash(core)]
                    ?: (if (brand != core) byCore[brand] ?: bySquash[EpgNorm.squash(brand)] else null)
                    ?: continue
                // The station broadcasts to one region; coreNorm strips a channel's country PREFIX, so
                // "USA: ESPN 2" and "NL: ESPN 2" both look like "espn 2" and one country's feed would
                // otherwise confirm every same-brand channel the user owns. Gate on region alignment.
                val stationRegion = SportsBroadcastRegionPolicy.regionOfCountry(st.country)
                    ?: SportsBroadcastRegionPolicy.regionOfChannel(raw)
                val score = LISTING_SCORE + SportsBroadcastRegionPolicy.listingScoreDelta(stationRegion, homeRegion)
                for (c in found) {
                    if (!SportsBroadcastRegionPolicy.listingAccepts(stationRegion, c.name)) continue
                    if (!SportsEventMatchEngine.compatibleStation(c.name, raw)) continue
                    val confidence = SportsBroadcastRegionPolicy.listingConfidence(stationRegion, SportsBroadcastRegionPolicy.regionOfChannel(c.name))
                    // A fixture-specific listing keeps its evidence across regions; unknown feed territory remains possible.
                    add(ChannelMatch(c, programme = null, score = score, via = MatchVia.LISTING, language = st.country, confidence = confidence, evidence = SportsMatchEvidence(SportsEvidenceSource.LISTING, confidence, LISTING_SCORE, listOf("fixture_broadcaster_listing"))))
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
     * A replayable programme on a matched channel — everything the play step needs to start the
     * catch-up walk. Built at sheet time; the walk itself begins only in [beginReplay], because
     * [CatchUpPlaybackCoordinator][com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator] single-flights
     * one walk per account, and a sheet full of archived channels beginning eagerly would each
     * supersede the last.
     */
    data class SportsReplay(
        val playlistId: String,
        val channelContentId: String,
        val channelName: String,
        val logo: String?,
        val streamId: Int,
        /** The player-facing title ("ESPN · Replay"). */
        val title: String,
        val programmeTitle: String,
        val programmeStartMs: Long,
        val programmeEndMs: Long,
    )

    /**
     * Catch-up Replay for a started/finished fixture on an archived channel: the programme bounds
     * the walk will replay, from the matched EPG programme when there is one, else a default
     * window opening 15 minutes before kickoff. Null when no archive/not started/not replayable.
     */
    suspend fun replayFor(match: ChannelMatch, fixture: RadarFixture): SportsReplay? {
        val start = fixture.startEpochMs ?: return null
        if (!match.channel.hasArchive || start > RadarTime.nowMs()) return null
        val account = accountStore.accounts.first().firstOrNull { it.id == match.channel.playlistId }
            ?: return null
        // Xtream-with-credentials only, the same rule the guide's replays play by: a Stalker
        // portal builds archive links server-side (none of the dialects apply) and an M3U playlist
        // has no panel to ask — the coordinator is the one place that knows.
        if (!catchUp.supports(account)) return null
        val programme = match.programme
        val replayStart = programme?.startMs?.takeIf { it > 0 } ?: (start - 15 * 60 * 1000L)
        val durationMin = (((programme?.endMs ?: 0L) - (programme?.startMs ?: 0L)) / 60_000L)
            .toInt().takeIf { it in 30..360 } ?: 165
        return SportsReplay(
            playlistId = account.id,
            channelContentId = match.channel.contentId,
            channelName = match.channel.name,
            logo = match.channel.logo,
            streamId = match.channel.streamId,
            title = "${match.channel.name} · Replay",
            programmeTitle = programme?.title ?: "Replay",
            programmeStartMs = replayStart,
            programmeEndMs = replayStart + durationMin * 60_000L,
        )
    }

    /**
     * Starts the replay's catch-up session — the same coordinator the guide's replays go through
     * (WP5), so the player inherits the flag, the gates and the dialect walk with the account's
     * container preference and winner memory. The session id is registered like every other Sports
     * play so the live route resolves it; the URL it carries is the walk's FIRST attempt, and the
     * player advances the walk in place on transport failure.
     */
    suspend fun beginReplay(replay: SportsReplay): com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator.Session? {
        val account = accountStore.accounts.first().firstOrNull { it.id == replay.playlistId }
            ?: return null
        val session = catchUp.begin(
            account = account,
            channelContentId = replay.channelContentId,
            channelName = replay.channelName,
            streamId = replay.streamId,
            programme = com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator.Programme(
                title = replay.programmeTitle,
                startMs = replay.programmeStartMs,
                endMs = replay.programmeEndMs,
            ),
            nowMs = RadarTime.nowMs(),
        ) ?: return null
        registry.register(
            XtreamResolvedItem(
                id = session.contentId, type = ContentType.TV, name = replay.title, poster = replay.logo,
                streamUrl = session.url, kind = XtreamKind.LIVE, accountId = account.id, streamId = replay.streamId,
            )
        )
        return session
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
                    if (!SportsRecordingMatchPolicy.accepts(homeTokens, awayTokens, eventTokens) { hits(text, it) }) {
                        return@forEach
                    }
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

    fun resetForProfile() {
        matcherGeneration.incrementAndGet()
        channelCache.clear()
    }

    // --- source assembly ------------------------------------------------------

    private suspend fun assembleCandidates(): List<CandidateChannel> {
        // Every enabled playlist, whatever its source: the factory hands back the Xtream
        // player_api client, the M3U catalog client, or the Stalker portal session as needed.
        val accounts = withContext(Dispatchers.IO) {
            accountStore.accounts.first().filter { it.enabled }
        }
        return buildList {
            for (account in accounts) {
                // A panel can return hundreds of thousands of rows. Keep transport and portal
                // work off the computation pool; conversion below resumes on Default with match().
                val catalogKey = account.id + ":" + account.hashCode() + ":" + contentDb.builtAt(account.id) + ":" + refreshStore.lastCheckedMs(account.id) + ":" + matcherGeneration.get()
                val channels = withContext(Dispatchers.IO) {
                    channelCache[catalogKey] ?: cacheMutex.withLock {
                        channelCache[catalogKey] ?: clientFactory.clientFor(account).liveChannels(account)
                            .getOrDefault(emptyList())
                            // Only cache success — this is an app-lifetime singleton, and caching a
                            // transient panel failure would leave matching dead until restart.
                             .also { channels ->
                                if (channels.isNotEmpty()) {
                                    channelCache.keys.removeAll { it.startsWith(account.id + ":") && it != catalogKey }
                                    channelCache[catalogKey] = channels
                                }
                            }
                    }
                }
                for (ch in channels) {
                    add(
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
                            catalogRevision = catalogKey,
                        )
                    )
                }
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

    private fun bestProgramme(
        programmes: List<XtreamProgram>,
        prepared: SportsEventMatchEngine.Prepared,
    ): Pair<XtreamProgram, SportsMatchEvidence>? = programmes.mapNotNull { p ->
        val evidence = SportsEventMatchEngine.programme(prepared, p.title, p.description, p.startMs, p.endMs)
        if (evidence.strength > 0) p to evidence else null
    }.maxByOrNull { SportsEventMatchEngine.rank(it.second.confidence) * 1000 + it.second.strength }

    private fun normalize(s: String?): String = SportsEventMatchEngine.normalize(s)

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
        const val EPG_PROBE_CAP = 6
        const val EPG_CONCURRENCY = 2
        /** Sheet capacity now that EPG/listing tiers surface worldwide airings (was 10). */
        const val RESULT_CAP = 40
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

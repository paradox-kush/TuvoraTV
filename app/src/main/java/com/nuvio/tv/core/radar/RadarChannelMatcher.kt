package com.nuvio.tv.core.radar

import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.iptv.XtreamResolvedItem
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
 * Core scoring is source-agnostic over [CandidateChannel]s; the single assembly function is
 * Xtream-specific today and gains M3U/Stalker when the playlist-manager feature lands
 * (see radar-feature-requirements.md §5). Name-match first because real-panel EPG is sparse.
 */
@Singleton
class RadarChannelMatcher @Inject constructor(
    private val xtreamClient: XtreamClient,
    private val accountStore: XtreamAccountStore,
    private val registry: XtreamItemRegistry,
) {
    data class CandidateChannel(
        val playlistId: String,
        val playlistName: String,
        val contentId: String,
        val name: String,
        val logo: String?,
        val streamId: Int,
        val streamUrl: String,
    )

    data class ChannelMatch(
        val channel: CandidateChannel,
        val programme: XtreamProgram?,
        val score: Int,
    )

    // Live lists once per account per session (26k channels on real panels).
    private val channelCache = ConcurrentHashMap<String, List<XtreamChannel>>()
    private val cacheMutex = Mutex()

    suspend fun match(
        fixture: RadarFixture,
        league: RadarLeague?,
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

        onPartial(named.take(RESULT_CAP))

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

        return probed.sortedByDescending { it.score }.take(RESULT_CAP)
    }

    /** Registers the match's channel so the player route can resolve it like any live id. */
    fun ensurePlayable(match: ChannelMatch) {
        if (registry.get(match.channel.contentId) != null) return
        registry.register(
            XtreamResolvedItem(
                id = match.channel.contentId,
                type = ContentType.TV,
                name = match.channel.name,
                poster = match.channel.logo,
                streamUrl = match.channel.streamUrl,
                kind = XtreamKind.LIVE,
                accountId = match.channel.playlistId,
                streamId = match.channel.streamId,
            )
        )
    }

    fun resetForProfile() {
        channelCache.clear()
    }

    // --- source assembly (the ONLY source-specific part) ----------------------

    private suspend fun assembleCandidates(): List<CandidateChannel> {
        val accounts = accountStore.accounts.first().filter { it.enabled }
        return accounts.flatMap { account ->
            val channels = channelCache[account.id] ?: cacheMutex.withLock {
                channelCache[account.id] ?: xtreamClient.liveChannels(account)
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
                )
            }
        }
    }

    private suspend fun epgFor(channel: CandidateChannel): List<XtreamProgram> {
        val account = accountStore.accounts.first().firstOrNull { it.id == channel.playlistId }
            ?: return emptyList()
        return xtreamClient.shortEpg(account, channel.streamId, limit = 8).getOrDefault(emptyList())
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
        val windowStart = startMs - 45 * 60 * 1000L
        val windowEnd = startMs + 4 * 60 * 60 * 1000L
        return programmes
            .filter { it.endMs > windowStart && it.startMs < windowEnd }
            .mapNotNull { p ->
                val text = normalize("${p.title} ${p.description}")
                if (text.isBlank()) return@mapNotNull null
                val home = homeTokens.any { hits(text, it) }
                val away = awayTokens.any { hits(text, it) }
                val keyword = keywords.any { hits(text, it) }
                val event = eventTokens.count { hits(text, it) } >= 2
                val score = when {
                    home && away -> 100
                    event -> 90
                    (home || away) && keyword -> 70
                    keyword -> 35
                    home || away -> 25
                    else -> 0
                }
                if (score > 0) p to score else null
            }
            .maxByOrNull { it.second }
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
        const val RESULT_CAP = 10

        // Compared against normalize()d names — punctuation is already stripped.
        val GENERIC_SPORT_MARKERS = listOf(
            "sport", "espn", "bein", "dazn", "eurosport", "supersport", "fox sports",
            "sky sports", "tnt sports", "arena", "setanta", "premier sports",
        )
        val STOP_TOKENS = setOf("fc", "cf", "sc", "afc", "rc", "cd", "ac", "de", "the", "club", "los", "las")
    }
}

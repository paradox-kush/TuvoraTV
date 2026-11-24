package com.nuvio.tv.core.radar.alerts

import android.util.Log
import com.nuvio.tv.core.iptv.XtreamLiveChannelIdentity
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.radar.MatchConfidence
import com.nuvio.tv.core.radar.RadarCalendarGames
import com.nuvio.tv.core.radar.RadarChannelMatcher
import com.nuvio.tv.core.radar.RadarRepository
import com.nuvio.tv.core.sports.detail.screen.WatchResolver
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.playback.core.PlaybackProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Which channel carries this game?", asked at most once per game per 15 minutes (negatives cached too). It reuses the
 * game page's own matcher and deadline and never throws; it never fetches fixtures itself. Two consumers:
 *  - gate 3 reads [statesFor] (any matched channel counts: is the viewer ON this game?);
 *  - the banner's Watch action reads [hit] (only a CONFIRMED match earns "Watch").
 */
@Singleton
class SportsAlertWatchLookup @Inject constructor(
    private val repository: RadarRepository,
    private val matcher: RadarChannelMatcher,
    private val livePlaylist: XtreamLivePlaylist,
    private val profileManager: ProfileManager,
    private val accountStore: XtreamAccountStore,
) : AlertWatchPort {
    /** The best CONFIRMED channel plus the matched lineup, so channel up/down works after the player opens. */
    class Hit(val contentId: String, val lineup: List<XtreamLiveChannelIdentity>)

    private val cache = WatchResultCache()
    private val hits = ConcurrentHashMap<String, Hit>()

    override fun statesFor(playing: Set<String>, nowMs: Long): Map<String, WatchState> = cache.statesFor(playing, nowMs)

    override fun watchContentId(eventId: String, nowMs: Long): String? = hit(eventId, nowMs)?.contentId

    /** The Watch target, only while the cached answer is still fresh. */
    fun hit(eventId: String, nowMs: Long): Hit? =
        hits[eventId]?.takeIf { cache.stateFor(eventId, emptySet(), nowMs) != WatchState.Unknown }

    /**
     * Makes sure the game has a cached answer. True = answered (found, or a cached "none": no playlist, finished,
     * nothing matched); false = could not tell (no fixture held, timeout, error): nothing is cached then.
     */
    override suspend fun resolve(eventId: String): Boolean = resolveAt(eventId, System::currentTimeMillis)

    private suspend fun resolveAt(eventId: String, nowMs: () -> Long): Boolean {
        if (cache.stateFor(eventId, emptySet(), nowMs()) != WatchState.Unknown) return true
        return try {
            val state = repository.uiState.value
            val fixture = (state.fixturesByLeague.values.asSequence() + state.fixturesByTeam.values.asSequence())
                .flatten().firstOrNull { RadarCalendarGames.gameId(it) == eventId } ?: return false
            if (fixture.isFinishedOrOff || accountStore.accounts.first().none { it.enabled }) {
                cache.putNone(eventId, nowMs()); return true
            }
            val league = fixture.leagueId?.let { state.leagueById(it) }
            val matches = withTimeoutOrNull(WatchResolver.LOOKUP_DEADLINE_MS) {
                matcher.match(fixture, league, stationLookup = { repository.tvStations(fixture.id) })
            } ?: return false
            val ids = matches.map { it.channel.contentId }.distinct()
            if (ids.isEmpty()) { cache.putNone(eventId, nowMs()); return true }
            cache.putFound(eventId, ids, nowMs())
            val lineup = matches.distinctBy { it.channel.contentId }.mapNotNull {
                XtreamLiveChannelIdentity.from(contentId = it.channel.contentId, title = it.channel.name, logo = it.channel.logo)
            }
            matches.filter { it.confidence == MatchConfidence.CONFIRMED }
                .minWithOrNull(compareBy<RadarChannelMatcher.ChannelMatch> { it.confidence.ordinal }.thenByDescending { it.score })
                ?.takeIf { best -> lineup.any { it.contentId.value == best.channel.contentId } }
                ?.let { hits[eventId] = Hit(it.channel.contentId, lineup) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SportsAlertWatchLookup", "watch lookup failed", e)
            false
        }
    }

    /** Publishes the matched lineup as the current live lineup (the playback layer resolves a channel only through it). */
    fun publishLineup(eventId: String): Boolean {
        val hit = hits[eventId] ?: return false
        return runCatching {
            livePlaylist.set(PlaybackProfileId(profileManager.activeProfileId.value.toString()), hit.lineup)
        }.isSuccess
    }

    /** A profile switch invalidates what was matched under the old profile's playlists. */
    fun clear() {
        hits.clear()
        cache.clear()
    }
}

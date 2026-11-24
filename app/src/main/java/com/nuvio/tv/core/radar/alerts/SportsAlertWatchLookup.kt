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
 * "Does the game page's Watch lookup find a channel for this game?", asked once when a banner appears. It reuses the
 * game page's own matcher and deadline (no new matching logic) and never throws: any failure, a missing fixture or no
 * confirmed channel simply means "Open game" stays the action. It never fetches fixtures itself.
 */
@Singleton
class SportsAlertWatchLookup @Inject constructor(
    private val repository: RadarRepository,
    private val matcher: RadarChannelMatcher,
    private val livePlaylist: XtreamLivePlaylist,
    private val profileManager: ProfileManager,
    private val accountStore: XtreamAccountStore,
) {
    /** A found channel plus the matched lineup, so channel up/down works after the player opens. */
    class Hit(val contentId: String, val lineup: List<XtreamLiveChannelIdentity>)

    /** eventId -> channels this lookup matched. Only ever read (never started) to decide "is the viewer watching it?". */
    private val cache = ConcurrentHashMap<String, Hit>()

    fun cachedContentIds(): Map<String, String> = cache.mapValues { it.value.contentId }

    suspend fun find(eventId: String): Hit? {
        cache[eventId]?.let { return it }
        return try {
            val state = repository.uiState.value
            val fixture = (state.fixturesByLeague.values.asSequence() + state.fixturesByTeam.values.asSequence())
                .flatten().firstOrNull { RadarCalendarGames.gameId(it) == eventId } ?: return null
            if (fixture.isFinishedOrOff) return null
            if (accountStore.accounts.first().none { it.enabled }) return null
            val league = fixture.leagueId?.let { state.leagueById(it) }
            val matches = withTimeoutOrNull(WatchResolver.LOOKUP_DEADLINE_MS) {
                matcher.match(fixture, league, stationLookup = { repository.tvStations(fixture.id) })
            }.orEmpty()
            // Only a confirmed (time-aligned / event-identity) match earns "Watch"; weaker evidence stays on "Open game".
            val best = matches.filter { it.confidence == MatchConfidence.CONFIRMED }
                .minWithOrNull(compareBy<RadarChannelMatcher.ChannelMatch> { it.confidence.ordinal }.thenByDescending { it.score })
                ?: return null
            val lineup = matches.distinctBy { it.channel.contentId }.mapNotNull {
                XtreamLiveChannelIdentity.from(contentId = it.channel.contentId, title = it.channel.name, logo = it.channel.logo)
            }
            if (lineup.none { it.contentId.value == best.channel.contentId }) return null
            Hit(best.channel.contentId, lineup).also { cache[eventId] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SportsAlertWatchLookup", "watch lookup failed", e)
            null
        }
    }

    /** Publishes the matched lineup as the current live lineup (the playback layer resolves a channel only through it). */
    fun publishLineup(eventId: String): Boolean {
        val hit = cache[eventId] ?: return false
        return runCatching {
            livePlaylist.set(PlaybackProfileId(profileManager.activeProfileId.value.toString()), hit.lineup)
        }.isSuccess
    }

    /** A profile switch invalidates what was matched under the old profile's playlists. */
    fun clear() = cache.clear()
}

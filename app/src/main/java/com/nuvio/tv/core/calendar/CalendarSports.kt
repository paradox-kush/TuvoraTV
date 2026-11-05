package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

enum class CalendarGameState { SCHEDULED, LIVE, FINAL, POSTPONED }

/**
 * One game of a followed team or league, neutral of where it came from (shape after the KMP twin's
 * CalendarGameItem). [kickoffMs] is UTC epoch ms; every day and time shown is counted in the Calendar zone.
 */
data class CalendarGameItem(
    val id: String,
    val sport: String? = null,
    val league: String? = null,
    val leagueBadge: String? = null,
    val home: String? = null,
    val away: String? = null,
    val homeBadge: String? = null,
    val awayBadge: String? = null,
    /** Display fallback when there are no two teams (motorsport, golf). */
    val event: String? = null,
    val kickoffMs: Long,
    val state: CalendarGameState = CalendarGameState.SCHEDULED,
    val homeScore: String? = null,
    val awayScore: String? = null,
    /** The minute / period while LIVE ("67'"), as the feed gives it. */
    val progress: String? = null,
    val channelHintId: String? = null
) {
    val title: String
        get() = if (!home.isNullOrBlank() && !away.isNullOrBlank()) "$home v $away" else event ?: league.orEmpty()
}

/**
 * The port the Calendar owns for games (consumer-owned, neutral). Implemented only at the composition root
 * (a Hilt module) from the radar state the app already holds. Contract: [games] makes NO network request and
 * the Calendar never polls it; [changes] emits when the held data changed under the radar's own refresh policy.
 */
interface CalendarSportsSource {
    /** What is already held (in memory, else the on-disk cache). Never a fetch. */
    suspend fun games(): List<CalendarGameItem>
    val changes: Flow<Unit>
    /** Asks the Sports channel-matching flow to open this game; the caller then navigates to Sports. */
    fun requestWatch(gameId: String)
}

/** No Sports on this build or profile: no games. */
object NoCalendarSports : CalendarSportsSource {
    override suspend fun games(): List<CalendarGameItem> = emptyList()
    override val changes: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
    override fun requestWatch(gameId: String) = Unit
}

enum class CalendarFilter { SHOWS, MOVIES, SPORTS }

val CALENDAR_ALL_FILTERS: Set<CalendarFilter> = CalendarFilter.entries.toSet()

/** A game placed on a day in the Calendar zone. */
data class CalendarGamePlacement(val game: CalendarGameItem, val date: LocalDate, val time: LocalTime)

/** Pure decisions about games on the Calendar. */
object CalendarSportsPolicy {
    val WATCH_BEFORE_KICKOFF: Duration = Duration.ofMinutes(15)

    /** Multi-select; the last filter cannot be switched off (an empty Calendar by filter is a dead end). */
    fun toggle(filters: Set<CalendarFilter>, filter: CalendarFilter): Set<CalendarFilter> = when {
        filter !in filters -> filters + filter
        filters.size == 1 -> filters
        else -> filters - filter
    }

    fun keepsTitle(filters: Set<CalendarFilter>, type: CalendarTitleType): Boolean =
        (if (type == CalendarTitleType.MOVIE) CalendarFilter.MOVIES else CalendarFilter.SHOWS) in filters

    fun games(filters: Set<CalendarFilter>, games: List<CalendarGameItem>): List<CalendarGameItem> =
        if (CalendarFilter.SPORTS in filters) games else emptyList()

    /** "Watch now" while LIVE, or from 15 minutes before kick-off of a game not yet over. */
    fun watchable(game: CalendarGameItem, now: Instant): Boolean = when (game.state) {
        CalendarGameState.LIVE -> true
        CalendarGameState.SCHEDULED -> now.toEpochMilli() >= game.kickoffMs - WATCH_BEFORE_KICKOFF.toMillis()
        else -> false
    }

    /**
     * Where a game sits, by its kick-off date in [zone]: a live game that kicked off before today sits on today;
     * a final of the last 7 days sits on its date (Just aired); anything else before today is dropped.
     */
    fun placement(game: CalendarGameItem, now: Instant, zone: ZoneId): CalendarGamePlacement? {
        val at = Instant.ofEpochMilli(game.kickoffMs).atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val date = at.toLocalDate()
        val placed = when {
            date >= today -> date
            game.state == CalendarGameState.LIVE -> today
            game.state == CalendarGameState.FINAL && date >= today.minusDays(7) -> date
            else -> return null
        }
        return CalendarGamePlacement(game, placed, at.toLocalTime())
    }
}

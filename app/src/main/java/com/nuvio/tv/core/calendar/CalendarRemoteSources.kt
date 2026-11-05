// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import android.os.SystemClock
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.CalendarTmdbApi
import com.nuvio.tv.data.remote.api.CalendarTraktApi
import com.nuvio.tv.data.remote.api.TvMazeApi
import com.nuvio.tv.data.remote.dto.calendar.CalendarTmdbEpisodeDto
import com.nuvio.tv.data.remote.dto.calendar.CalendarTmdbReleaseCountryDto
import com.nuvio.tv.data.remote.dto.calendar.TraktCalendarEpisodeDto
import com.nuvio.tv.data.remote.dto.calendar.TraktCalendarShowDto
import com.nuvio.tv.data.remote.dto.calendar.TvMazeEpisodeDto
import com.nuvio.tv.data.remote.dto.calendar.TvMazeShowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.repository.TraktAuthService
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/** Every remote read the Calendar makes, behind one seam so the refresher tests count calls without a network. */
interface CalendarRemoteSources {
    suspend fun tvMazeLookup(ids: CalendarIds): TvMazeLookup
    suspend fun tvMazeShow(id: Int, now: Instant): CalendarShowDates
    /** Null when Trakt does not know the show; throws [TraktUnreachable] when Trakt cannot be reached. */
    suspend fun traktNextEpisode(ids: CalendarIds): CalendarShowDates?
    /** The signed-in viewer's Trakt calendar; null when a call failed. */
    suspend fun traktMyShows(today: LocalDate): List<TraktCalendarItem>?
    suspend fun tmdbFind(imdb: String, type: CalendarTitleType): Long?
    suspend fun tmdbShow(tmdbId: Long): CalendarShowDates
    suspend fun tmdbSeason(tmdbId: Long, season: Int): List<CalendarEntry>
    suspend fun tmdbFilm(tmdbId: Long, region: String): CalendarShowDates
}

@Singleton
class DefaultCalendarRemoteSources @Inject constructor(
    private val tvMaze: TvMazeCalendarClient,
    private val trakt: TraktCalendarClient,
    private val tmdb: TmdbCalendarClient
) : CalendarRemoteSources {
    override suspend fun tvMazeLookup(ids: CalendarIds) = tvMaze.lookup(ids)
    override suspend fun tvMazeShow(id: Int, now: Instant) = tvMaze.show(id, now)
    override suspend fun traktNextEpisode(ids: CalendarIds) = trakt.nextEpisode(ids)
    override suspend fun traktMyShows(today: LocalDate) = trakt.myShows(today)
    override suspend fun tmdbFind(imdb: String, type: CalendarTitleType) = tmdb.findTmdbId(imdb, type)
    override suspend fun tmdbShow(tmdbId: Long) = tmdb.show(tmdbId)
    override suspend fun tmdbSeason(tmdbId: Long, season: Int) = tmdb.season(tmdbId, season)
    override suspend fun tmdbFilm(tmdbId: Long, region: String) = tmdb.film(tmdbId, region)
}

class CalendarFetchException(message: String) : IOException(message)

data class CalendarShowDates(
    val status: CalendarShowStatus = CalendarShowStatus.UNKNOWN,
    val entries: List<CalendarEntry> = emptyList(),
    val name: String? = null,
    val network: String? = null,
    val ids: CalendarIds = CalendarIds(),
    val poster: String? = null
)

// ---------------------------------------------------------------- TVmaze

sealed interface TvMazeLookup {
    data class Found(val id: Int, val ids: CalendarIds) : TvMazeLookup
    data object NotFound : TvMazeLookup
}

@Singleton
class TvMazeCalendarClient internal constructor(
    private val api: TvMazeApi,
    private val limiter: CalendarRateLimiter
) {
    @Inject
    constructor(api: TvMazeApi) : this(api, CalendarRateLimiter(nowMs = SystemClock::elapsedRealtime))

    suspend fun lookup(ids: CalendarIds): TvMazeLookup {
        ids.imdb?.let { imdb -> call(LOOKUP_PERMITS) { api.lookupByImdb(imdb) }.foundOrNull()?.let { return it.toLookup() } }
        ids.tvdb?.let { tvdb -> call(LOOKUP_PERMITS) { api.lookupByTvdb(tvdb) }.foundOrNull()?.let { return it.toLookup() } }
        return TvMazeLookup.NotFound
    }

    /** When the next episode is within [SEASON_FETCH_AHEAD], its whole season is read too. */
    suspend fun show(id: Int, now: Instant): CalendarShowDates {
        val show = call { api.getShow(id) }.foundOrNull() ?: return CalendarShowDates()
        val next = show.embedded?.nextEpisode
        val nextAt = next?.let(::tvMazeEpisodeEntry)?.sortInstant(CALENDAR_STORAGE_ZONE)
        val seasonEpisodes = if (next?.season != null && nextAt != null && nextAt.isBefore(now.plus(SEASON_FETCH_AHEAD))) {
            val seasonId = show.embedded.seasons?.firstOrNull { it.number == next.season }?.id
                ?: call { api.getSeasons(id) }.foundOrNull()?.firstOrNull { it.number == next.season }?.id
            seasonId?.let { call { api.getSeasonEpisodes(it) }.foundOrNull() }.orEmpty()
        } else {
            emptyList()
        }
        return tvMazeShowDates(show, seasonEpisodes)
    }

    private suspend fun <T> call(permits: Int = 1, block: suspend () -> Response<T>): Response<T> {
        repeat(MAX_ATTEMPTS) {
            limiter.acquire(permits)
            val response = block()
            if (response.code() != 429) return response
            limiter.pauseFor(parseRetryAfterMs(response.headers()["Retry-After"]) ?: DEFAULT_RETRY_MS)
        }
        throw CalendarFetchException("TVmaze rate limit")
    }

    private fun <T> Response<T>.foundOrNull(): T? = when {
        isSuccessful -> body()
        code() == 404 -> null
        else -> throw CalendarFetchException("TVmaze ${code()}")
    }

    private fun TvMazeShowDto.toLookup() = TvMazeLookup.Found(
        id,
        CalendarIds(imdb = externals?.imdb?.takeIf { it.startsWith("tt") }, tvdb = externals?.thetvdb)
    )

    internal companion object {
        /** A lookup answers with a redirect to the show, which is a second request. */
        const val LOOKUP_PERMITS = 2
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_RETRY_MS = 10_000L
        val SEASON_FETCH_AHEAD: Duration = Duration.ofDays(60)
    }
}

fun tvMazeShowDates(show: TvMazeShowDto, seasonEpisodes: List<TvMazeEpisodeDto>): CalendarShowDates {
    val episodes = listOfNotNull(show.embedded?.previousEpisode, show.embedded?.nextEpisode) + seasonEpisodes
    return CalendarShowDates(
        status = tvMazeStatus(show.status),
        entries = episodes.mapNotNull(::tvMazeEpisodeEntry).distinctBy(CalendarEntry::slot),
        name = show.name,
        network = show.network?.name ?: show.webChannel?.name,
        ids = CalendarIds(imdb = show.externals?.imdb?.takeIf { it.startsWith("tt") }, tvdb = show.externals?.thetvdb),
        poster = show.image?.let { it.medium ?: it.original }?.takeIf(String::isNotBlank)?.replaceFirst("http://", "https://")
    )
}

/** An episode with an air time keeps its exact `airstamp`; without one only the plain date is kept. */
fun tvMazeEpisodeEntry(episode: TvMazeEpisodeDto): CalendarEntry? {
    val season = episode.season ?: return null
    val number = episode.number ?: return null
    if (episode.type.equals("insignificant_special", ignoreCase = true)) return null
    val instant = episode.airstamp
        ?.takeIf { !episode.airtime.isNullOrBlank() }
        ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    val date = episode.airdate?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
    if (instant == null && date == null) return null
    return CalendarEntry(
        season = season,
        episode = number,
        title = episode.name?.takeIf(String::isNotBlank),
        airInstantMs = instant,
        airDate = if (instant == null) date else null,
        label = deriveEpisodeLabel(season, number),
        source = CalendarSource.TVMAZE
    )
}

fun tvMazeStatus(status: String?): CalendarShowStatus = when (status?.trim()?.lowercase()) {
    "running" -> CalendarShowStatus.RUNNING
    "in development" -> CalendarShowStatus.IN_PRODUCTION
    "ended" -> CalendarShowStatus.ENDED
    else -> CalendarShowStatus.UNKNOWN
}

// ---------------------------------------------------------------- Trakt

data class TraktCalendarItem(
    val ids: CalendarIds,
    val showTitle: String?,
    val status: CalendarShowStatus,
    val entry: CalendarEntry
)

class TraktUnreachable(why: String) : Exception("Trakt could not be reached: $why")

@Singleton
class TraktCalendarClient @Inject constructor(
    private val api: CalendarTraktApi,
    private val traktAuthService: TraktAuthService
) {
    /** Today - 7 to today + 58 in two calls (Trakt allows 33 days per call); null when a call failed. */
    suspend fun myShows(today: LocalDate): List<TraktCalendarItem>? {
        val rows = mutableListOf<TraktCalendarShowDto>()
        for ((start, days) in traktCalendarRanges(today)) {
            val response = traktAuthService.executeAuthorizedRequest { authorization ->
                api.getMyShowsCalendar(authorization, start.toString(), days)
            } ?: return null
            if (!response.isSuccessful) return null
            rows += response.body().orEmpty()
        }
        return traktCalendarItems(rows)
    }

    /**
     * Null when Trakt does not know the show, so the caller moves on to TMDB. Throws when Trakt cannot be reached,
     * so the caller keeps what Trakt said before.
     */
    suspend fun nextEpisode(ids: CalendarIds): CalendarShowDates? {
        val id = ids.trakt?.toString() ?: ids.imdb ?: return null
        val response = traktAuthService.executePublicRequest { api.getShowNextEpisode(id) }
            ?: throw TraktUnreachable("no answer")
        if (response.code() == 404 || response.code() == 204) return null
        if (!response.isSuccessful) throw TraktUnreachable("HTTP ${response.code()}")
        return CalendarShowDates(
            entries = listOfNotNull(response.body()?.let { traktEpisodeEntry(it, null, CalendarSource.TRAKT_NEXT) })
        )
    }
}

fun traktCalendarRanges(today: LocalDate): List<Pair<LocalDate, Int>> =
    listOf(today.minusDays(7) to TRAKT_CALENDAR_DAYS, today.plusDays(26) to TRAKT_CALENDAR_DAYS)

private const val TRAKT_CALENDAR_DAYS = 33

fun traktCalendarItems(rows: List<TraktCalendarShowDto>): List<TraktCalendarItem> = rows.mapNotNull { row ->
    val ids = row.show?.ids?.toCalendarIds() ?: return@mapNotNull null
    if (ids.isEmpty) return@mapNotNull null
    val entry = row.episode?.let { traktEpisodeEntry(it, row.firstAired) } ?: return@mapNotNull null
    TraktCalendarItem(ids, row.show.title, traktShowStatus(row.show.status), entry)
}.distinctBy { it.ids.primaryAlias() + "|" + it.entry.slot }

fun traktEpisodeEntry(
    episode: TraktCalendarEpisodeDto,
    rowFirstAired: String?,
    source: CalendarSource = CalendarSource.TRAKT
): CalendarEntry? {
    val season = episode.season ?: return null
    val number = episode.number ?: return null
    val instant = (episode.firstAired ?: rowFirstAired)?.let(::parseTraktInstant) ?: return null
    return CalendarEntry(
        season = season,
        episode = number,
        title = episode.title?.takeIf(String::isNotBlank),
        airInstantMs = instant.toEpochMilli(),
        label = traktEpisodeLabel(episode.episodeType, season, number),
        source = source
    )
}

fun traktShowStatus(status: String?): CalendarShowStatus = when (status?.trim()?.lowercase()) {
    "returning series", "continuing" -> CalendarShowStatus.RUNNING
    "in production", "planned", "upcoming", "pilot" -> CalendarShowStatus.IN_PRODUCTION
    "ended" -> CalendarShowStatus.ENDED
    "canceled", "cancelled" -> CalendarShowStatus.CANCELLED
    else -> CalendarShowStatus.UNKNOWN
}

private fun parseTraktInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull() ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

private fun TraktIdsDto.toCalendarIds() = CalendarIds(
    imdb = imdb?.takeIf { it.startsWith("tt") },
    tmdb = tmdb?.toLong(),
    tvdb = tvdb?.toLong(),
    trakt = trakt?.toLong()
)

fun traktCalendarCandidates(items: List<TraktCalendarItem>): List<CalendarTitleCandidate> =
    items.distinctBy { it.ids.primaryAlias() }.map { item ->
        CalendarTitleCandidate(item.ids, CalendarTitleType.SERIES, CalendarOrigin.TRAKT_CALENDAR, name = item.showTitle)
    }

/**
 * The calendar's releases replace every show's [CalendarSource.TRAKT] dates. Trakt's next episode, asked for a show
 * TVmaze does not know, is kept apart as [CalendarSource.TRAKT_NEXT] and is not touched.
 */
fun applyTraktCalendar(
    records: List<CalendarTitleRecord>,
    items: List<TraktCalendarItem>,
    now: Instant
): List<CalendarTitleRecord> = records.map { record ->
    if (record.type != CalendarTitleType.SERIES) return@map record
    val mine = items.filter { it.ids.sharesIdWith(record.ids) }
    if (mine.isEmpty()) return@map record.withoutSources(CalendarSource.TRAKT)
    val status = if (record.status == CalendarShowStatus.UNKNOWN) mine.first().status else record.status
    record.copy(ids = mine.fold(record.ids) { ids, item -> ids.merged(item.ids) }, status = status)
        .withSource(CalendarSource.TRAKT, mine.map(TraktCalendarItem::entry), now)
}

/** A show the fresh Trakt calendar already dates ahead needs no fetch of its own. */
fun CalendarTitleRecord.isCoveredByTrakt(traktCalendarFetchedAtMs: Long, now: Instant): Boolean =
    now.toEpochMilli() - traktCalendarFetchedAtMs < CALENDAR_TRAKT_REFRESH.toMillis() * 2 &&
        sources[CalendarSource.TRAKT].orEmpty().any { it.airInstantMs?.let { at -> at > now.toEpochMilli() } == true }

// ---------------------------------------------------------------- TMDB

@Singleton
class TmdbCalendarClient @Inject constructor(
    private val api: CalendarTmdbApi,
    private val tmdbService: TmdbService
) {
    private val apiKey: String get() = tmdbService.apiKey()

    suspend fun findTmdbId(imdb: String, type: CalendarTitleType): Long? {
        val body = api.findByImdb(imdb, apiKey).foundOrNull() ?: return null
        val results = if (type == CalendarTitleType.MOVIE) body.movieResults else body.tvResults
        return results?.firstOrNull()?.id?.toLong()
    }

    suspend fun film(tmdbId: Long, region: String): CalendarShowDates {
        val results = api.getReleaseDates(tmdbId, apiKey).foundOrNull()?.results.orEmpty()
        return CalendarShowDates(entries = tmdbFilmReleaseEntries(results, region))
    }

    suspend fun show(tmdbId: Long): CalendarShowDates {
        val details = api.getShow(tmdbId, apiKey).foundOrNull() ?: return CalendarShowDates()
        return CalendarShowDates(
            status = tmdbShowStatus(details.status),
            entries = listOfNotNull(details.lastEpisodeToAir, details.nextEpisodeToAir).mapNotNull(::tmdbEpisodeEntry),
            name = details.name,
            network = details.networks?.firstOrNull()?.name,
            poster = tmdbPosterUrl(details.posterPath)
        )
    }

    suspend fun season(tmdbId: Long, season: Int): List<CalendarEntry> {
        val body = api.getSeason(tmdbId, season, apiKey).foundOrNull() ?: return emptyList()
        return body.episodes.orEmpty().mapNotNull { episode ->
            tmdbEpisodeEntry(episode.copy(seasonNumber = episode.seasonNumber ?: body.seasonNumber ?: season))
        }
    }

    private fun <T> Response<T>.foundOrNull(): T? = when {
        isSuccessful -> body()
        code() == 404 -> null
        else -> throw CalendarFetchException("TMDB ${code()}")
    }
}

fun tmdbEpisodeEntry(episode: CalendarTmdbEpisodeDto): CalendarEntry? {
    val season = episode.seasonNumber ?: return null
    val number = episode.episodeNumber ?: return null
    val date = episode.airDate?.trim()?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: return null
    val finale = tmdbEpisodeLabel(episode.episodeType)
    return CalendarEntry(
        season = season,
        episode = number,
        title = episode.name?.takeIf(String::isNotBlank),
        airDate = date,
        label = if (finale != CalendarEpisodeLabel.NONE) finale else deriveEpisodeLabel(season, number),
        source = CalendarSource.TMDB
    )
}

fun tmdbPosterUrl(path: String?): String? =
    path?.trim()?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w185$it" }

fun tmdbShowStatus(status: String?): CalendarShowStatus = when (status?.trim()?.lowercase()) {
    "returning series" -> CalendarShowStatus.RUNNING
    "in production", "planned", "pilot" -> CalendarShowStatus.IN_PRODUCTION
    "ended" -> CalendarShowStatus.ENDED
    "canceled", "cancelled" -> CalendarShowStatus.CANCELLED
    else -> CalendarShowStatus.UNKNOWN
}

/**
 * A film's cinema date (type 3, or 2 when there is no 3) and digital date (type 4), each from [region] when it has
 * one, else from the US. Dates only.
 */
fun tmdbFilmReleaseEntries(results: List<CalendarTmdbReleaseCountryDto>, region: String): List<CalendarEntry> {
    fun datesOf(country: String, type: Int): List<String> = results
        .filter { it.iso31661.equals(country, ignoreCase = true) }
        .flatMap { it.releaseDates.orEmpty() }
        .filter { it.type == type }
        .mapNotNull { item -> item.releaseDate?.take(10)?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } }
        .sorted()

    fun firstDate(vararg types: Int): String? {
        for (country in listOf(region, FALLBACK_REGION).distinct()) {
            for (type in types) datesOf(country, type).firstOrNull()?.let { return it }
        }
        return null
    }

    return listOfNotNull(
        firstDate(TYPE_THEATRICAL, TYPE_THEATRICAL_LIMITED)?.let {
            CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = it, source = CalendarSource.TMDB)
        },
        firstDate(TYPE_DIGITAL)?.let {
            CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = it, source = CalendarSource.TMDB)
        }
    )
}

private const val FALLBACK_REGION = "US"
private const val TYPE_THEATRICAL_LIMITED = 2
private const val TYPE_THEATRICAL = 3
private const val TYPE_DIGITAL = 4


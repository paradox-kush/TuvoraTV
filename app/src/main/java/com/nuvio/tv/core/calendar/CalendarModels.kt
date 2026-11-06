// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CalendarTitleType { SERIES, MOVIE }

@Serializable
data class CalendarIds(
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
    val trakt: Long? = null
) {
    val isEmpty: Boolean get() = imdb == null && tmdb == null && tvdb == null && trakt == null

    fun merged(other: CalendarIds): CalendarIds = CalendarIds(
        imdb = imdb ?: other.imdb,
        tmdb = tmdb ?: other.tmdb,
        tvdb = tvdb ?: other.tvdb,
        trakt = trakt ?: other.trakt
    )

    fun aliases(): Set<String> = buildSet {
        imdb?.let { add("imdb:$it") }
        tmdb?.let { add("tmdb:$it") }
        tvdb?.let { add("tvdb:$it") }
        trakt?.let { add("trakt:$it") }
    }

    fun sharesIdWith(other: CalendarIds): Boolean =
        (imdb != null && imdb == other.imdb) || (tmdb != null && tmdb == other.tmdb) ||
            (tvdb != null && tvdb == other.tvdb) || (trakt != null && trakt == other.trakt)

    fun primaryAlias(): String? = imdb?.let { "imdb:$it" } ?: tmdb?.let { "tmdb:$it" }
        ?: tvdb?.let { "tvdb:$it" } ?: trakt?.let { "trakt:$it" }

    companion object {
        /** `tt123`, `tt123:1:2`, `tmdb:5`, `tvdb:7`, `trakt:9`; anything else (kitsu:, an IPTV id) has no ids. */
        fun fromContentId(raw: String?): CalendarIds {
            val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return CalendarIds()
            if (value.startsWith("tt", ignoreCase = true)) return CalendarIds(imdb = value.substringBefore(':'))
            val id = value.substringAfter(':', "").substringBefore(':').trim()
            return when (value.substringBefore(':').lowercase()) {
                "imdb" -> CalendarIds(imdb = id.takeIf { it.startsWith("tt") })
                "tmdb" -> CalendarIds(tmdb = id.toLongOrNull())
                "tvdb" -> CalendarIds(tvdb = id.toLongOrNull())
                "trakt" -> CalendarIds(trakt = id.toLongOrNull())
                else -> CalendarIds()
            }
        }
    }
}

fun calendarTitleKey(type: CalendarTitleType, ids: CalendarIds): String? =
    ids.primaryAlias()?.let { "${type.name.lowercase()}:$it" }

fun parseCalendarTitleKey(key: String): Pair<CalendarTitleType, CalendarIds>? {
    val type = CalendarTitleType.entries.firstOrNull { key.startsWith("${it.name.lowercase()}:") } ?: return null
    val ids = CalendarIds.fromContentId(key.substringAfter(':'))
    return if (ids.isEmpty) null else type to ids
}

/** True when [key] names this title, also after the title has learned more ids than it had when the key was made. */
fun calendarKeyMatches(key: String, type: CalendarTitleType, ids: CalendarIds): Boolean {
    val (keyType, keyIds) = parseCalendarTitleKey(key) ?: return false
    return keyType == type && keyIds.sharesIdWith(ids)
}

fun calendarTitleType(contentType: String?): CalendarTitleType? = when (contentType?.trim()?.lowercase()) {
    "movie", "film" -> CalendarTitleType.MOVIE
    "series", "tv", "show", "tvshow", "anime" -> CalendarTitleType.SERIES
    else -> null
}

/** Where a followed title came from. [started] titles can go idle; [savesFilms] origins follow a film. */
@Serializable
enum class CalendarOrigin(val started: Boolean = false, val savesFilms: Boolean = false) {
    LIBRARY(savesFilms = true),
    PROGRESS(started = true),
    WATCHED(started = true),
    TRAKT_CALENDAR,
    BELL(savesFilms = true)
}

@Serializable
enum class CalendarEntryKind { EPISODE, MOVIE_THEATRICAL, MOVIE_DIGITAL }

@Serializable
enum class CalendarEpisodeLabel {
    NONE,
    NEW_SHOW,
    SEASON_PREMIERE,
    MID_SEASON_PREMIERE,
    MID_SEASON_FINALE,
    SEASON_FINALE,
    SERIES_FINALE
}

@Serializable
enum class CalendarSource(val priority: Int) {
    TRAKT(0),
    TRAKT_NEXT(1),
    TVMAZE(2),
    TMDB(4);

    val isTrakt: Boolean get() = this == TRAKT || this == TRAKT_NEXT
}

@Serializable
enum class CalendarShowStatus {
    RUNNING,
    IN_PRODUCTION,
    ENDED,
    CANCELLED,
    UNKNOWN;

    val isOver: Boolean get() = this == ENDED || this == CANCELLED
    val mayReturn: Boolean get() = this == RUNNING || this == IN_PRODUCTION
}

/**
 * The zone the Calendar's own bookkeeping (merge order, 15-days-back / a-year-ahead bounds) is counted in.
 * Never used for anything the viewer sees: days, times and "next release" are counted in the zone the
 * caller resolved (override, else the box's), which every [CalendarEntry.sortInstant] caller must pass.
 */
val CALENDAR_STORAGE_ZONE: ZoneId = ZoneOffset.UTC

/**
 * One dated release: an episode, or a film's cinema or digital date. [airInstantMs] is set only when the
 * source gave an exact time; otherwise [airDate] holds the plain date as given and the time is "not announced".
 */
@Serializable
data class CalendarEntry(
    @SerialName("k") val kind: CalendarEntryKind = CalendarEntryKind.EPISODE,
    @SerialName("s") val season: Int? = null,
    @SerialName("e") val episode: Int? = null,
    @SerialName("t") val title: String? = null,
    @SerialName("at") val airInstantMs: Long? = null,
    @SerialName("d") val airDate: String? = null,
    @SerialName("l") val label: CalendarEpisodeLabel = CalendarEpisodeLabel.NONE,
    @SerialName("src") val source: CalendarSource = CalendarSource.TVMAZE
) {
    val timeKnown: Boolean get() = airInstantMs != null

    val slot: String
        get() = if (kind == CalendarEntryKind.EPISODE) "S${season ?: 0}E${episode ?: 0}" else kind.name

    fun localDate(zone: ZoneId): LocalDate? =
        airInstantMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** An exact time is out when it airs; a plain date once that date has begun everywhere (next day 00:00 UTC). */
    fun isOut(now: Instant): Boolean {
        airInstantMs?.let { return it <= now.toEpochMilli() }
        val date = airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        return !date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(now)
    }

    /** The episode name, without placeholders such as "TBA". */
    val shownTitle: String?
        get() = title?.trim()?.takeIf { it.isNotEmpty() && it.uppercase() !in PLACEHOLDER_TITLES }

    /**
     * A plain date counts from midnight in [zone], so it sorts with the day it is shown under. Deliberately no
     * default (fix 4 over Arc): a default of the system zone silently ignored the viewer's zone override.
     */
    fun sortInstant(zone: ZoneId): Instant? = airInstantMs?.let(Instant::ofEpochMilli)
        ?: airDate?.let { runCatching { LocalDate.parse(it).atStartOfDay(zone).toInstant() }.getOrNull() }
}

private val PLACEHOLDER_TITLES = setOf("TBA", "TBD", "TBC", "TBA.", "TBD.", "TO BE ANNOUNCED", "TO BE DETERMINED")

@Serializable
data class CalendarTitleRecord(
    val key: String,
    val type: CalendarTitleType,
    val ids: CalendarIds,
    val name: String? = null,
    val contentId: String? = null,
    val poster: String? = null,
    val origins: Set<CalendarOrigin> = emptySet(),
    val lastActivityMs: Long? = null,
    val tvmazeId: Int? = null,
    val status: CalendarShowStatus = CalendarShowStatus.UNKNOWN,
    val network: String? = null,
    val sources: Map<CalendarSource, List<CalendarEntry>> = emptyMap(),
    val sourceFetchedAtMs: Map<CalendarSource, Long> = emptyMap(),
    val fetchedAtMs: Long = 0L,
    val nextRefreshAtMs: Long = 0L,
    val failures: Int = 0
) {
    val entries: List<CalendarEntry> by lazy {
        mergeCalendarEntries(sources.values.flatten(), sourceFetchedAtMs)
    }

    fun episodeKey(entry: CalendarEntry): String = "$key|${entry.slot}"

    fun matchesKey(key: String): Boolean = key == this.key || calendarKeyMatches(key, type, ids)
}

@Serializable
data class CalendarTvMazeId(val id: Int? = null, val checkedAtMs: Long = 0L)

@Serializable
data class CalendarSnapshot(
    val version: Int = CURRENT_VERSION,
    val titles: List<CalendarTitleRecord> = emptyList(),
    val tvmazeIds: Map<String, CalendarTvMazeId> = emptyMap(),
    val traktCalendarFetchedAtMs: Long = 0L,
    /** Reminder episode keys already shown, with when; pruned after 30 days ([boundCalendarSnapshot]). */
    val seenReminders: Map<String, Long> = emptyMap(),
    /** How the last finished run ended, kept so a failure still shows after a restart; null in files written before this. */
    val lastRun: CalendarRunResult? = null,
    val updatedAtMs: Long = 0L
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * The bell key for a title page: null when the title has no id the Calendar can read (an IPTV channel, a kitsu:
 * anime id without an IMDb id), so the page shows no bell rather than one that can never ring.
 */
fun calendarBellKey(apiType: String?, contentId: String?, imdbId: String?): String? {
    val type = calendarTitleType(apiType) ?: return null
    val ids = CalendarIds.fromContentId(contentId).merged(CalendarIds.fromContentId(imdbId?.takeIf { it.startsWith("tt") }))
    return calendarTitleKey(type, ids)
}

/** True when the bell set holds [key]'s title under any of its ids. */
fun calendarBellOn(bells: Set<String>, key: String?): Boolean {
    val (type, ids) = key?.let(::parseCalendarTitleKey) ?: return false
    return bells.any { it == key || calendarKeyMatches(it, type, ids) }
}

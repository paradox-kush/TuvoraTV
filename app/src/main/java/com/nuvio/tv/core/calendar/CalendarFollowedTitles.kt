// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant

data class CalendarTitleCandidate(
    val ids: CalendarIds,
    val type: CalendarTitleType,
    val origin: CalendarOrigin,
    val lastActivity: Instant? = null,
    val name: String? = null,
    val contentId: String? = null,
    val poster: String? = null
)

data class CalendarFollowedTitle(
    val key: String,
    val ids: CalendarIds,
    val type: CalendarTitleType,
    val origins: Set<CalendarOrigin>,
    val lastActivity: Instant?,
    val name: String? = null,
    val contentId: String? = null,
    val poster: String? = null,
    val bellOn: Boolean = false
)

data class CalendarFollowRules(
    val hiddenTitleKeys: Set<String> = emptySet(),
    val bellOn: Set<String> = emptySet(),
    val staleAfter: Duration = Duration.ofDays(365),
    val cap: Int = CALENDAR_MAX_TITLES
)

const val CALENDAR_MAX_TITLES = 300

/**
 * Hidden titles are always left out. Unless the bell is on, so are started shows idle for
 * [CalendarFollowRules.staleAfter] and films that were only watched, not saved. A title in the library is never idle;
 * over the cap, titles with the bell on go first, then the library, then the most recently active.
 */
fun followCalendarTitles(
    candidates: List<CalendarTitleCandidate>,
    rules: CalendarFollowRules,
    now: Instant
): List<CalendarFollowedTitle> {
    val usable = candidates.filter { !it.ids.isEmpty }
    val bellOnly = rules.bellOn.mapNotNull { key ->
        val (type, ids) = parseCalendarTitleKey(key) ?: return@mapNotNull null
        CalendarTitleCandidate(ids, type, CalendarOrigin.BELL)
    }
    val staleBefore = now.minus(rules.staleAfter)

    val titles = groupBySharedIds(usable + bellOnly).mapNotNull { group ->
        val type = group.first().type
        val ids = group.fold(CalendarIds()) { acc, candidate -> acc.merged(candidate.ids) }
        val key = calendarTitleKey(type, ids) ?: return@mapNotNull null
        val named = group.sortedBy { it.origin.ordinal }
        val bell = rules.bellOn.any { calendarKeyMatches(it, type, ids) }
        val origins = group.mapTo(linkedSetOf()) { it.origin }.apply { if (bell) add(CalendarOrigin.BELL) }
        val lastActivity = group.mapNotNull { it.lastActivity }.maxOrNull()
        val leftOut = when {
            rules.hiddenTitleKeys.any { calendarKeyMatches(it, type, ids) } -> true
            bell -> false
            CalendarOrigin.LIBRARY !in origins && origins.any { it.started } &&
                (lastActivity?.isBefore(staleBefore) ?: origins.none { it.savesFilms }) -> true
            type == CalendarTitleType.MOVIE && origins.none { it.savesFilms } -> true
            else -> false
        }
        if (leftOut) return@mapNotNull null
        CalendarFollowedTitle(
            key = key,
            ids = ids,
            type = type,
            origins = origins,
            lastActivity = lastActivity,
            name = named.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) },
            contentId = ids.imdb ?: named.firstNotNullOfOrNull { it.contentId?.takeIf(String::isNotBlank) }
                ?: ids.tmdb?.let { "tmdb:$it" },
            poster = named.firstNotNullOfOrNull { it.poster?.takeIf(String::isNotBlank) },
            bellOn = bell
        )
    }

    val ordered = titles.sortedWith(
        compareByDescending<CalendarFollowedTitle> { it.bellOn }
            .thenByDescending { CalendarOrigin.LIBRARY in it.origins }
            .thenByDescending { it.lastActivity ?: Instant.EPOCH }
            .thenBy { it.name.orEmpty().lowercase() }
            .thenBy { it.key }
    )
    val bells = ordered.filter { it.bellOn }
    val room = (rules.cap - bells.size).coerceAtLeast(0)
    return bells + ordered.filterNot { it.bellOn }.take(room)
}

private fun groupBySharedIds(candidates: List<CalendarTitleCandidate>): List<List<CalendarTitleCandidate>> {
    val parent = IntArray(candidates.size) { it }
    fun find(i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        var node = i
        while (parent[node] != root) { val next = parent[node]; parent[node] = root; node = next }
        return root
    }
    val owner = HashMap<String, Int>()
    candidates.forEachIndexed { index, candidate ->
        candidate.ids.aliases().forEach { alias ->
            val other = owner.putIfAbsent("${candidate.type}|$alias", index)
            if (other != null) {
                val a = find(index)
                val b = find(other)
                if (a != b) parent[maxOf(a, b)] = minOf(a, b)
            }
        }
    }
    return candidates.indices.groupBy(::find).values.map { members -> members.map(candidates::get) }
}

/** A followed title keeps its stored record (and key) when they share an id; a new one is due at once. */
fun reconcileCalendarRecords(
    followed: List<CalendarFollowedTitle>,
    stored: List<CalendarTitleRecord>
): List<CalendarTitleRecord> {
    val used = BooleanArray(stored.size)
    return followed.map { title ->
        val index = stored.indices.firstOrNull { i ->
            !used[i] && stored[i].type == title.type && stored[i].ids.sharesIdWith(title.ids)
        }
        val previous = index?.let { used[it] = true; stored[it] }
        previous?.copy(
            ids = title.ids.merged(previous.ids),
            name = title.name ?: previous.name,
            contentId = title.contentId ?: previous.contentId,
            poster = title.poster ?: previous.poster,
            origins = title.origins,
            lastActivityMs = title.lastActivity?.toEpochMilli()
        ) ?: CalendarTitleRecord(
            key = title.key,
            type = title.type,
            ids = title.ids,
            name = title.name,
            contentId = title.contentId,
            poster = title.poster,
            origins = title.origins,
            lastActivityMs = title.lastActivity?.toEpochMilli()
        )
    }
}

/**
 * What has been watched, by every id alias of a show plus season and episode. Built from local watch progress
 * and watched items; the Calendar never asks a service for it.
 */
class CalendarWatchedIndex(private val keys: Set<String> = emptySet()) {
    fun isWatched(ids: CalendarIds, season: Int?, episode: Int?): Boolean {
        if (season == null || episode == null) return false
        return ids.aliases().any { "$it|$season|$episode" in keys }
    }

    fun isWatched(title: CalendarTitleRecord, entry: CalendarEntry): Boolean =
        entry.kind == CalendarEntryKind.EPISODE && isWatched(title.ids, entry.season, entry.episode)

    class Builder {
        private val keys = HashSet<String>()

        fun add(contentId: String, season: Int?, episode: Int?) = apply {
            if (season == null || episode == null) return@apply
            CalendarIds.fromContentId(contentId).aliases().forEach { keys += "$it|$season|$episode" }
        }

        fun build() = CalendarWatchedIndex(keys)
    }
}

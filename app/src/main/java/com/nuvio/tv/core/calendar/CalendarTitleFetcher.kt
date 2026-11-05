// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/**
 * What one title's refresh did. [attempted] is false when nothing was asked (the fresh Trakt calendar already
 * covers the show); [failed] when it was asked and the answer could not be read. The refresher counts both
 * to tell a failed run from a quiet one (fix 1).
 */
data class CalendarFetchOutcome(
    val record: CalendarTitleRecord,
    val tvMazeIds: Map<String, CalendarTvMazeId> = emptyMap(),
    val attempted: Boolean = true,
    val failed: Boolean = false
)

/**
 * Shows: TVmaze first (exact times), then Trakt's next episode for shows TVmaze does not know, then TMDB's plain
 * dates; TMDB's season adds finale marks when a TMDB id is known. Films: TMDB release dates. Any failure keeps the
 * old dates and counts towards the back-off. Next-refresh times are counted in [zone].
 */
class CalendarTitleFetcher(
    private val remote: CalendarRemoteSources,
    private val region: String,
    private val zone: ZoneId
) {
    suspend fun refresh(
        record: CalendarTitleRecord,
        tvMazeIds: Map<String, CalendarTvMazeId>,
        coveredByTrakt: Boolean,
        now: Instant
    ): CalendarFetchOutcome {
        if (record.type == CalendarTitleType.SERIES && coveredByTrakt) {
            val kept = record.copy(failures = 0)
            return CalendarFetchOutcome(kept.copy(nextRefreshAtMs = nextRefreshAt(kept, now, zone).toEpochMilli()), attempted = false)
        }
        val learned = mutableMapOf<String, CalendarTvMazeId>()
        val refreshed = try {
            when (record.type) {
                CalendarTitleType.MOVIE -> refreshFilm(record, now)
                CalendarTitleType.SERIES -> refreshShow(record, tvMazeIds, learned, now)
            }.copy(failures = 0, fetchedAtMs = now.toEpochMilli())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "${record.key}: ${error.javaClass.simpleName} ${error.message}")
            return CalendarFetchOutcome(failed(record, now), failed = true)
        }
        return CalendarFetchOutcome(refreshed.copy(nextRefreshAtMs = nextRefreshAt(refreshed, now, zone).toEpochMilli()), learned)
    }

    fun failed(record: CalendarTitleRecord, now: Instant): CalendarTitleRecord {
        val counted = record.copy(failures = record.failures + 1)
        return counted.copy(nextRefreshAtMs = nextRefreshAt(counted, now, zone).toEpochMilli())
    }

    private suspend fun refreshFilm(record: CalendarTitleRecord, now: Instant): CalendarTitleRecord {
        val withTmdb = withTmdbId(record)
        val tmdbId = withTmdb.ids.tmdb ?: return withTmdb
        return withTmdb.withSource(CalendarSource.TMDB, remote.tmdbFilm(tmdbId, region).entries, now)
    }

    private suspend fun refreshShow(
        record: CalendarTitleRecord,
        known: Map<String, CalendarTvMazeId>,
        learned: MutableMap<String, CalendarTvMazeId>,
        now: Instant
    ): CalendarTitleRecord {
        val tvMazeId = record.tvmazeId ?: resolveTvMazeId(record.ids, known, learned, now)
        if (tvMazeId != null) {
            val dates = remote.tvMazeShow(tvMazeId, now)
            val updated = record.copy(
                tvmazeId = tvMazeId,
                ids = record.ids.merged(dates.ids),
                name = record.name ?: dates.name,
                poster = record.poster ?: dates.poster,
                network = dates.network ?: record.network,
                status = dates.status
            ).withSource(CalendarSource.TVMAZE, dates.entries, now)
            return withFinales(updated, now)
        }
        val trakt = try {
            remote.traktNextEpisode(record.ids)
        } catch (unreachable: TraktUnreachable) {
            if (!record.sources[CalendarSource.TRAKT_NEXT].isNullOrEmpty()) return record
            null
        }
        if (trakt != null && trakt.entries.isNotEmpty()) return record.withSource(CalendarSource.TRAKT_NEXT, trakt.entries, now)
        val withTmdb = withTmdbId(if (trakt != null) record.withoutSources(CalendarSource.TRAKT_NEXT) else record)
        val tmdbId = withTmdb.ids.tmdb ?: return withTmdb
        val dates = remote.tmdbShow(tmdbId)
        return withTmdb.copy(
            name = withTmdb.name ?: dates.name,
            poster = withTmdb.poster ?: dates.poster,
            network = dates.network ?: withTmdb.network,
            status = dates.status
        ).withSource(CalendarSource.TMDB, dates.entries, now)
    }

    /** One TMDB season call marks the finales of the season with the next release, when that is within 60 days. */
    private suspend fun withFinales(record: CalendarTitleRecord, now: Instant): CalendarTitleRecord {
        val tmdbId = record.ids.tmdb ?: return record
        val next = record.entries.firstOrNull { entry ->
            entry.kind == CalendarEntryKind.EPISODE &&
                entry.sortInstant(zone)?.let { !it.isBefore(now) && it.isBefore(now.plus(FINALE_LOOKAHEAD)) } == true
        } ?: return record.withoutSources(CalendarSource.TMDB)
        val season = next.season ?: return record
        val finales = try {
            remote.tmdbSeason(tmdbId, season).filter { it.label != CalendarEpisodeLabel.NONE }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return record
        }
        return record.withSource(CalendarSource.TMDB, finales, now)
    }

    private suspend fun resolveTvMazeId(
        ids: CalendarIds,
        known: Map<String, CalendarTvMazeId>,
        learned: MutableMap<String, CalendarTvMazeId>,
        now: Instant
    ): Int? {
        val aliases = tvMazeAliases(ids)
        if (aliases.isEmpty()) return null
        aliases.firstNotNullOfOrNull { known[it]?.id }?.let { return it }
        val missingUntil = now.minus(NOT_FOUND_RECHECK).toEpochMilli()
        if (aliases.all { alias -> known[alias]?.let { it.id == null && it.checkedAtMs > missingUntil } == true }) return null
        val stamp = now.toEpochMilli()
        return when (val lookup = remote.tvMazeLookup(ids)) {
            is TvMazeLookup.Found -> {
                tvMazeAliases(ids.merged(lookup.ids)).forEach { learned[it] = CalendarTvMazeId(lookup.id, stamp) }
                lookup.id
            }
            TvMazeLookup.NotFound -> {
                aliases.forEach { learned[it] = CalendarTvMazeId(null, stamp) }
                null
            }
        }
    }

    private fun tvMazeAliases(ids: CalendarIds): List<String> =
        listOfNotNull(ids.imdb?.let { "imdb:$it" }, ids.tvdb?.let { "tvdb:$it" })

    private suspend fun withTmdbId(record: CalendarTitleRecord): CalendarTitleRecord {
        if (record.ids.tmdb != null) return record
        val imdb = record.ids.imdb ?: return record
        val tmdbId = remote.tmdbFind(imdb, record.type) ?: return record
        return record.copy(ids = record.ids.copy(tmdb = tmdbId))
    }

    private companion object {
        const val TAG = "CalendarFetch"
        val NOT_FOUND_RECHECK: Duration = Duration.ofDays(30)
        val FINALE_LOOKAHEAD: Duration = Duration.ofDays(60)
    }
}

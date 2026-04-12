package com.nuvio.tv.core.anime

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves (TMDB show id, season, episode) → tracker entry + tracker episode
 * number, handling the two hardest cases:
 *
 * 1. Per-season-as-separate-entry: AoT S1, S2, S3 are three different MAL/
 *    AniList/Kitsu entries — pick the one that matches the TMDB season.
 * 2. Absolute numbering: One Piece S21E1072 on TMDB is `num_watched_episodes
 *    = 1072` on MAL (single entry, absolute count).
 *
 * Sources in priority order:
 * - PlexAniBridge-Mappings (if fetched) — has per-episode offset ranges for
 *   absolute-numbered shows and authoritative TMDB↔AniList cross-refs.
 * - arm.haglund.dev via [AnimeIdMapper] — coverage fallback; returns a list
 *   of entries but no per-episode offsets, so numbering assumed to restart.
 * - Heuristic: if nothing matches, return a [TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE]
 *   result with the best-effort first entry and `trackerEpisode = tmdbEpisode`.
 *   Callers should check [TrackerEpisodeMapping.hasAnyTrackerId] before writing.
 */
@Singleton
class EpisodeOffsetMapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val animeMappingsApi: AnimeMappingsApi,
    private val animeIdMapper: AnimeIdMapper,
    private val moshi: Moshi
) {
    private val mutex = Mutex()

    /** Loaded mappings keyed by AniList id (string). */
    @Volatile private var cache: Map<String, AnimeMappingEntryDto>? = null
    @Volatile private var byTmdbShowIndex: Map<Int, List<AnimeMappingEntryDto>> = emptyMap()
    @Volatile private var byTmdbMovieIndex: Map<Int, AnimeMappingEntryDto> = emptyMap()
    @Volatile private var byImdbShowIndex: Map<String, List<AnimeMappingEntryDto>> = emptyMap()

    /** Async kick-off used by StartupSyncService. Non-blocking; errors logged. */
    suspend fun warmIfStale() {
        val file = cacheFile()
        val freshCutoff = System.currentTimeMillis() - MAX_AGE_MS
        if (file.exists() && file.lastModified() > freshCutoff && cache != null) return
        loadOrRefresh()
    }

    /**
     * Convenience entry point that accepts either an IMDb id or a TMDB id.
     * Player and detail screens have IMDb far more reliably than TMDB, so
     * prefer IMDb when both are supplied — [byImdbShowIndex] is authoritative
     * when present. Falls back through ARM if neither is in the PlexAniBridge
     * file.
     */
    suspend fun resolveByIds(
        imdbId: String?,
        tmdbId: Int?,
        season: Int,
        episode: Int
    ): TrackerEpisodeMapping {
        ensureLoaded()
        if (!imdbId.isNullOrBlank()) {
            val pab = byImdbShowIndex[imdbId.trim()].orEmpty()
            if (pab.isNotEmpty()) {
                val match = pickCandidate(pab, season, episode)
                if (match != null) {
                    val absolute = translateEpisode(match.entry, match.tvdbSeason, episode)
                    return TrackerEpisodeMapping(
                        tmdbId = match.entry.tmdbShowId ?: tmdbId ?: -1,
                        tmdbSeason = season,
                        tmdbEpisode = episode,
                        malId = match.entry.malId,
                        anilistId = match.entry.anilistId,
                        kitsuId = match.entry.kitsuId,
                        anidbId = match.entry.anidbId,
                        trackerEpisode = absolute ?: episode,
                        totalEpisodes = match.entry.length,
                        source = TrackerEpisodeMapping.MappingSource.PLEX_ANI_BRIDGE
                    )
                }
            }
        }
        if (tmdbId != null) return resolve(tmdbId, season, episode)
        if (!imdbId.isNullOrBlank()) {
            val arm = animeIdMapper.getEntriesForImdb(imdbId)
            if (arm.isNotEmpty()) {
                val idx = (season - 1).coerceIn(0, arm.lastIndex)
                val entry = arm[idx]
                return TrackerEpisodeMapping(
                    tmdbId = entry.themoviedb ?: -1,
                    tmdbSeason = season,
                    tmdbEpisode = episode,
                    malId = entry.myanimelist,
                    anilistId = entry.anilist,
                    kitsuId = entry.kitsu,
                    anidbId = entry.anidb,
                    trackerEpisode = episode,
                    totalEpisodes = null,
                    source = TrackerEpisodeMapping.MappingSource.ARM
                )
            }
        }
        return TrackerEpisodeMapping(
            tmdbId = tmdbId ?: -1,
            tmdbSeason = season,
            tmdbEpisode = episode,
            trackerEpisode = episode,
            source = TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE
        )
    }

    suspend fun resolve(
        tmdbId: Int,
        tmdbSeason: Int,
        tmdbEpisode: Int
    ): TrackerEpisodeMapping {
        ensureLoaded()
        // 1. PlexAniBridge path — most accurate when present.
        val candidates = byTmdbShowIndex[tmdbId].orEmpty()
        if (candidates.isNotEmpty()) {
            val match = pickCandidate(candidates, tmdbSeason, tmdbEpisode)
            if (match != null) {
                val absolute = translateEpisode(match.entry, match.tvdbSeason, tmdbEpisode)
                return TrackerEpisodeMapping(
                    tmdbId = tmdbId,
                    tmdbSeason = tmdbSeason,
                    tmdbEpisode = tmdbEpisode,
                    malId = match.entry.malId,
                    anilistId = match.entry.anilistId,
                    kitsuId = match.entry.kitsuId,
                    anidbId = match.entry.anidbId,
                    trackerEpisode = absolute ?: tmdbEpisode,
                    totalEpisodes = match.entry.length,
                    source = TrackerEpisodeMapping.MappingSource.PLEX_ANI_BRIDGE
                )
            }
        }
        // 2. ARM fallback — one entry per anime season/cour, no episode offsets.
        val armEntries = animeIdMapper.getEntriesForTmdb(tmdbId)
        if (armEntries.isNotEmpty()) {
            val idx = (tmdbSeason - 1).coerceIn(0, armEntries.lastIndex)
            val entry = armEntries[idx]
            return TrackerEpisodeMapping(
                tmdbId = tmdbId,
                tmdbSeason = tmdbSeason,
                tmdbEpisode = tmdbEpisode,
                malId = entry.myanimelist,
                anilistId = entry.anilist,
                kitsuId = entry.kitsu,
                anidbId = entry.anidb,
                trackerEpisode = tmdbEpisode,
                totalEpisodes = null,
                source = TrackerEpisodeMapping.MappingSource.ARM
            )
        }
        // 3. Nothing — caller should treat as "not anime / unknown".
        return TrackerEpisodeMapping(
            tmdbId = tmdbId,
            tmdbSeason = tmdbSeason,
            tmdbEpisode = tmdbEpisode,
            trackerEpisode = tmdbEpisode,
            source = TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE
        )
    }

    /**
     * [resolveWholeShow] variant that accepts either id. Prefers IMDb because
     * player/detail have it more reliably; falls through to TMDB then ARM.
     */
    suspend fun resolveWholeShowByIds(imdbId: String?, tmdbId: Int?): TrackerShowMapping {
        ensureLoaded()
        if (!imdbId.isNullOrBlank()) {
            val pab = byImdbShowIndex[imdbId.trim()].orEmpty()
            if (pab.isNotEmpty()) {
                return TrackerShowMapping(
                    tmdbId = pab.firstOrNull()?.tmdbShowId ?: tmdbId ?: -1,
                    entries = pab.map {
                        TrackerShowEntry(
                            tmdbSeason = firstTvdbSeason(it),
                            malId = it.malId,
                            anilistId = it.anilistId,
                            kitsuId = it.kitsuId,
                            anidbId = it.anidbId,
                            totalEpisodes = it.length
                        )
                    },
                    source = TrackerEpisodeMapping.MappingSource.PLEX_ANI_BRIDGE
                )
            }
        }
        if (tmdbId != null) return resolveWholeShow(tmdbId)
        if (!imdbId.isNullOrBlank()) {
            val arm = animeIdMapper.getEntriesForImdb(imdbId)
            if (arm.isNotEmpty()) {
                return TrackerShowMapping(
                    tmdbId = arm.firstOrNull()?.themoviedb ?: -1,
                    entries = arm.mapIndexed { idx, e ->
                        TrackerShowEntry(
                            tmdbSeason = idx + 1,
                            malId = e.myanimelist,
                            anilistId = e.anilist,
                            kitsuId = e.kitsu,
                            anidbId = e.anidb
                        )
                    },
                    source = TrackerEpisodeMapping.MappingSource.ARM
                )
            }
        }
        return TrackerShowMapping(
            tmdbId = tmdbId ?: -1,
            entries = emptyList(),
            source = TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE
        )
    }

    /**
     * Every tracker entry that belongs to a TMDB show — used for "mark full
     * show watched" to iterate and complete each entry.
     */
    suspend fun resolveWholeShow(tmdbId: Int): TrackerShowMapping {
        ensureLoaded()
        val pab = byTmdbShowIndex[tmdbId].orEmpty()
        if (pab.isNotEmpty()) {
            return TrackerShowMapping(
                tmdbId = tmdbId,
                entries = pab.map {
                    TrackerShowEntry(
                        tmdbSeason = firstTvdbSeason(it),
                        malId = it.malId,
                        anilistId = it.anilistId,
                        kitsuId = it.kitsuId,
                        anidbId = it.anidbId,
                        totalEpisodes = it.length
                    )
                },
                source = TrackerEpisodeMapping.MappingSource.PLEX_ANI_BRIDGE
            )
        }
        val arm = animeIdMapper.getEntriesForTmdb(tmdbId)
        if (arm.isNotEmpty()) {
            return TrackerShowMapping(
                tmdbId = tmdbId,
                entries = arm.mapIndexed { idx, e ->
                    TrackerShowEntry(
                        tmdbSeason = idx + 1,
                        malId = e.myanimelist,
                        anilistId = e.anilist,
                        kitsuId = e.kitsu,
                        anidbId = e.anidb,
                        totalEpisodes = null
                    )
                },
                source = TrackerEpisodeMapping.MappingSource.ARM
            )
        }
        return TrackerShowMapping(
            tmdbId = tmdbId,
            entries = emptyList(),
            source = TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE
        )
    }

    /**
     * Movie-specific resolution — TMDB movie id → single tracker entry.
     * Falls through the same priority chain as [resolve].
     */
    suspend fun resolveMovie(tmdbMovieId: Int): TrackerShowEntry? {
        ensureLoaded()
        byTmdbMovieIndex[tmdbMovieId]?.let {
            return TrackerShowEntry(
                malId = it.malId,
                anilistId = it.anilistId,
                kitsuId = it.kitsuId,
                anidbId = it.anidbId,
                totalEpisodes = it.length ?: 1
            )
        }
        // arm's /themoviedb returns for both movies and shows — try it.
        val arm = animeIdMapper.getEntriesForTmdb(tmdbMovieId)
        val first = arm.firstOrNull() ?: return null
        return TrackerShowEntry(
            malId = first.myanimelist,
            anilistId = first.anilist,
            kitsuId = first.kitsu,
            anidbId = first.anidb,
            totalEpisodes = 1
        )
    }

    // --- internal --- //

    private suspend fun ensureLoaded() {
        if (cache != null) return
        loadOrRefresh()
    }

    private suspend fun loadOrRefresh() = mutex.withLock {
        if (cache != null) return@withLock
        val file = cacheFile()
        val freshCutoff = System.currentTimeMillis() - MAX_AGE_MS
        val loaded: Map<String, AnimeMappingEntryDto>? = if (file.exists() && file.lastModified() > freshCutoff) {
            readFromFile(file)
        } else {
            val fetched = download()
            if (fetched != null) {
                runCatching { writeToFile(file, fetched) }.onFailure {
                    Log.w(TAG, "failed to persist mappings: ${it.message}")
                }
                fetched
            } else {
                // Network failed — fall back to whatever stale copy we have.
                if (file.exists()) readFromFile(file) else null
            }
        }
        if (loaded != null) {
            cache = loaded
            byTmdbShowIndex = loaded.values
                .filter { it.tmdbShowId != null }
                .groupBy { it.tmdbShowId!! }
            byTmdbMovieIndex = loaded.values
                .filter { it.tmdbMovieId != null }
                .associateBy { it.tmdbMovieId!! }
            byImdbShowIndex = loaded.values
                .filter { !it.imdbId.isNullOrBlank() }
                .groupBy { it.imdbId!!.trim() }
            Log.i(TAG, "loaded ${loaded.size} anime mappings (shows=${byTmdbShowIndex.size}, movies=${byTmdbMovieIndex.size}, imdb=${byImdbShowIndex.size})")
        } else {
            cache = emptyMap()
            Log.w(TAG, "no mappings available — falling back to arm only")
        }
    }

    private suspend fun download(): Map<String, AnimeMappingEntryDto>? {
        val result = safeApiCall {
            animeMappingsApi.getMappings(AnimeMappingsSource.PLEX_ANI_BRIDGE_URL)
        }
        return when (result) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Error -> {
                Log.w(TAG, "mappings download failed code=${result.code} msg=${result.message}")
                null
            }
            NetworkResult.Loading -> null
        }
    }

    private fun cacheFile(): File = File(context.cacheDir, CACHE_FILENAME)

    @Suppress("UNCHECKED_CAST")
    private suspend fun readFromFile(file: File): Map<String, AnimeMappingEntryDto>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val type = Types.newParameterizedType(
                    Map::class.java, String::class.java, AnimeMappingEntryDto::class.java
                )
                val adapter = moshi.adapter<Map<String, AnimeMappingEntryDto>>(type)
                file.source().buffer().use { adapter.fromJson(it) }
            }.getOrNull()
        }

    private suspend fun writeToFile(file: File, data: Map<String, AnimeMappingEntryDto>) {
        withContext(Dispatchers.IO) {
            val type = Types.newParameterizedType(
                Map::class.java, String::class.java, AnimeMappingEntryDto::class.java
            )
            val adapter = moshi.adapter<Map<String, AnimeMappingEntryDto>>(type)
            file.sink().buffer().use { adapter.toJson(it, data) }
        }
    }

    /**
     * Pick which PlexAniBridge candidate corresponds to the given TMDB
     * (season, episode). We walk [AnimeMappingEntryDto.tvdbMappings] keys —
     * the format is one of:
     *   "s{N}"                          — whole TVDB season N
     *   "s{N}e{start}-e{end}"           — specific episode range
     *   "s{N}e{start}-e{end}|s{M}..."   — multiple segments pipe-separated
     *
     * AniList episode numbers for that segment are supplied in the value with
     * the same syntax; translation happens in [translateEpisode].
     */
    private fun pickCandidate(
        candidates: List<AnimeMappingEntryDto>,
        tmdbSeason: Int,
        tmdbEpisode: Int
    ): CandidateMatch? {
        for (entry in candidates) {
            val mappings = entry.tvdbMappings ?: continue
            for ((key, _) in mappings) {
                val segments = key.split("|")
                for (seg in segments) {
                    val (s, range) = parseSeasonSegment(seg) ?: continue
                    if (s != tmdbSeason) continue
                    if (range == null || tmdbEpisode in range) {
                        return CandidateMatch(entry = entry, tvdbSeason = s)
                    }
                }
            }
        }
        // No explicit mapping hit — if exactly one candidate exists, use it.
        return candidates.singleOrNull()?.let { CandidateMatch(entry = it, tvdbSeason = tmdbSeason) }
    }

    /**
     * Given a picked candidate, translate the TMDB episode to the tracker
     * (AniList/MAL/Kitsu) absolute episode. Uses [AnimeMappingEntryDto.tvdbMappings]
     * key→value pair to compute the offset. If no explicit range exists,
     * returns null and the caller falls back to tmdbEpisode.
     */
    private fun translateEpisode(
        entry: AnimeMappingEntryDto,
        tvdbSeason: Int,
        tmdbEpisode: Int
    ): Int? {
        val mappings = entry.tvdbMappings ?: return null
        for ((key, value) in mappings) {
            val keySegs = key.split("|")
            val valueSegs = value.split("|")
            for ((i, seg) in keySegs.withIndex()) {
                val (s, range) = parseSeasonSegment(seg) ?: continue
                if (s != tvdbSeason) continue
                val tvdbStart = range?.first ?: 1
                val matchesEpisode = range == null || tmdbEpisode in range
                if (!matchesEpisode) continue
                val valSeg = valueSegs.getOrNull(i).orEmpty()
                val trackerStart = parseRange(valSeg)?.first ?: 1
                // offset = trackerStart - tvdbStart
                return trackerStart + (tmdbEpisode - tvdbStart)
            }
        }
        return null
    }

    /** Returns `null` when the whole season is mapped (no e-range). */
    private fun parseSeasonSegment(seg: String): Pair<Int, IntRange?>? {
        val m = SEGMENT_REGEX.matchEntire(seg.trim()) ?: return null
        val season = m.groupValues[1].toInt()
        val epStart = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt()
        val epEnd = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toInt()
        val range = when {
            epStart != null && epEnd != null -> epStart..epEnd
            epStart != null -> epStart..epStart
            else -> null
        }
        return season to range
    }

    private fun parseRange(seg: String): IntRange? {
        val m = RANGE_REGEX.matchEntire(seg.trim()) ?: return null
        val start = m.groupValues[1].toInt()
        val end = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: start
        return start..end
    }

    private fun firstTvdbSeason(entry: AnimeMappingEntryDto): Int? {
        val keys = entry.tvdbMappings?.keys ?: return null
        for (key in keys) {
            for (seg in key.split("|")) {
                parseSeasonSegment(seg)?.let { return it.first }
            }
        }
        return null
    }

    private data class CandidateMatch(
        val entry: AnimeMappingEntryDto,
        val tvdbSeason: Int
    )

    companion object {
        private const val TAG = "EpisodeOffsetMapper"
        private const val CACHE_FILENAME = "anime_mappings.json"
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        // s1, s1e1-e12, s21e1-e1072
        private val SEGMENT_REGEX = Regex("""s(\d+)(?:e(\d+)(?:-e(\d+))?)?""", RegexOption.IGNORE_CASE)
        // e1-e12 or e1 or 1-12
        private val RANGE_REGEX = Regex("""e?(\d+)(?:-e?(\d+))?""", RegexOption.IGNORE_CASE)
    }
}

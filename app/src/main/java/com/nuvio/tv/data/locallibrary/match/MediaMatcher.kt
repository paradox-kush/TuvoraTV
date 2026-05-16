package com.nuvio.tv.data.locallibrary.match

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.MatchOverrideStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.LocalMatch
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Resolves a [ScannedItem] to a TMDB id via:
 *  1. A stored userSet=true override (always preferred).
 *  2. A non-userSet auto-match in the override store (cache, avoid re-querying).
 *  3. A strong server-side hint ([ScannedItem.tmdbHintId], e.g. Jellyfin).
 *  4. TMDB search by parsed filename + year, scored against a confidence threshold.
 */
@Singleton
class MediaMatcher @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val overrideStore: MatchOverrideStore
) {

    /**
     * Returns the resolved match for [item] or null if confidence falls below
     * [AUTO_MATCH_THRESHOLD]. Persists the result to the override store
     * (respecting user overrides — see [MatchOverrideStore.put]).
     */
    suspend fun match(item: ScannedItem, language: String = "en-US"): LocalMatch? =
        withContext(Dispatchers.IO) {
            overrideStore.get(item.itemKey)?.let { stored ->
                if (stored.userSet) return@withContext stored
                // Treat non-userSet entries as cached auto-results; re-use them.
                return@withContext stored
            }

            item.tmdbHintId?.let { hintId ->
                val match = LocalMatch(
                    itemKey = item.itemKey,
                    tmdbId = hintId,
                    contentType = item.typeHint.takeIf { it != ContentType.UNKNOWN } ?: ContentType.MOVIE,
                    season = item.parsedSeason,
                    episode = item.parsedEpisode,
                    userSet = false,
                    score = 1f
                )
                overrideStore.put(match)
                return@withContext match
            }

            val parsedTitle = item.parsedTitle?.takeIf { it.isNotBlank() }
                ?: FilenameParser.parse(item.fileName).title
            val parsed = FilenameParser.parse(item.fileName)
            val type = when {
                item.typeHint == ContentType.SERIES -> ContentType.SERIES
                item.typeHint == ContentType.MOVIE -> ContentType.MOVIE
                else -> parsed.contentType
            }
            val year = item.parsedYear ?: parsed.year

            val candidates = searchCandidates(parsedTitle, year, type, language)
            val best = scoreAndPickBest(candidates, parsedTitle, year)
            if (best == null || best.second < AUTO_MATCH_THRESHOLD) {
                Log.i(TAG, "No match for '${item.fileName}' parsedTitle='$parsedTitle' year=$year type=$type bestScore=${best?.second} bestCandidate=${best?.first?.title ?: best?.first?.name}")
                return@withContext null
            }
            Log.i(TAG, "Matched '${item.fileName}' -> tmdbId=${best.first.id} (${best.first.title ?: best.first.name}) score=${best.second}")
            val match = LocalMatch(
                itemKey = item.itemKey,
                tmdbId = best.first.id,
                contentType = type,
                season = item.parsedSeason ?: parsed.season,
                episode = item.parsedEpisode ?: parsed.episode,
                userSet = false,
                score = best.second
            )
            overrideStore.put(match)
            match
        }

    /** Returns up to [limit] TMDB candidates for the manual-match picker UI. */
    suspend fun candidates(
        item: ScannedItem,
        contentType: ContentType? = null,
        language: String = "en-US",
        limit: Int = 5
    ): List<TmdbDiscoverResult> = withContext(Dispatchers.IO) {
        val parsed = FilenameParser.parse(item.fileName)
        val title = item.parsedTitle?.takeIf { it.isNotBlank() } ?: parsed.title
        val year = item.parsedYear ?: parsed.year
        val type = contentType ?: parsed.contentType
        searchCandidates(title, year, type, language).take(limit)
    }

    /** Persists a user-confirmed match. Always sets [LocalMatch.userSet] = true. */
    suspend fun setOverride(
        item: ScannedItem,
        tmdbId: Int,
        contentType: ContentType,
        season: Int? = null,
        episode: Int? = null
    ) {
        overrideStore.put(
            LocalMatch(
                itemKey = item.itemKey,
                tmdbId = tmdbId,
                contentType = contentType,
                season = season ?: item.parsedSeason,
                episode = episode ?: item.parsedEpisode,
                userSet = true,
                score = 1f
            )
        )
    }

    suspend fun clearOverride(itemKey: String) = overrideStore.remove(itemKey)

    private suspend fun searchCandidates(
        title: String,
        year: Int?,
        type: ContentType,
        language: String
    ): List<TmdbDiscoverResult> {
        if (title.isBlank()) return emptyList()
        return try {
            val resp = when (type) {
                ContentType.SERIES, ContentType.TV -> tmdbApi.searchTv(
                    apiKey = TMDB_API_KEY,
                    query = title,
                    firstAirDateYear = year,
                    language = language
                )
                else -> tmdbApi.searchMovie(
                    apiKey = TMDB_API_KEY,
                    query = title,
                    year = year,
                    language = language
                )
            }
            resp.body()?.results.orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "TMDB search failed for '$title'", t)
            emptyList()
        }
    }

    private fun scoreAndPickBest(
        candidates: List<TmdbDiscoverResult>,
        query: String,
        queryYear: Int?
    ): Pair<TmdbDiscoverResult, Float>? {
        if (candidates.isEmpty()) return null
        return candidates
            .map { it to score(it, query, queryYear) }
            .maxByOrNull { it.second }
    }

    private fun score(result: TmdbDiscoverResult, query: String, queryYear: Int?): Float {
        val title = (result.title ?: result.name ?: "").lowercase()
        val originalTitle = (result.originalTitle ?: result.originalName ?: "").lowercase()
        val q = query.lowercase()

        val titleSim = max(titleSimilarity(q, title), titleSimilarity(q, originalTitle))

        val resultYear = (result.releaseDate ?: result.firstAirDate)
            ?.take(4)
            ?.toIntOrNull()
        val yearScore = when {
            queryYear == null || resultYear == null -> 0.5f
            queryYear == resultYear -> 1f
            abs(queryYear - resultYear) == 1 -> 0.6f
            abs(queryYear - resultYear) <= 2 -> 0.3f
            else -> 0f
        }

        val popularity = result.popularity ?: 0.0
        val popularityScore = (ln(1.0 + popularity) / ln(101.0)).coerceIn(0.0, 1.0).toFloat()

        return titleSim * 0.6f + yearScore * 0.3f + popularityScore * 0.1f
    }

    /**
     * Title similarity combining three signals so we don't reject candidates
     * whose canonical title is just shorter than the filename (e.g. TMDB has
     * "F1" but the release is named "F1 The Movie"). Returns the strongest of:
     *  - normalized Levenshtein over the full strings,
     *  - token-set Jaccard,
     *  - a subset bonus when one title's tokens are entirely contained in the
     *    other's — the dominant case for noisy release-group filenames.
     */
    private fun titleSimilarity(query: String, candidate: String): Float {
        if (query.isBlank() || candidate.isBlank()) return 0f
        if (query == candidate) return 1f
        val qTokens = query.split(' ').filter { it.isNotBlank() }.toSet()
        val cTokens = candidate.split(' ').filter { it.isNotBlank() }.toSet()
        if (qTokens.isEmpty() || cTokens.isEmpty()) {
            return normalizedLevenshtein(query, candidate)
        }
        val intersection = qTokens.intersect(cTokens).size.toFloat()
        val union = qTokens.union(cTokens).size.toFloat()
        val jaccard = if (union > 0f) intersection / union else 0f
        val subsetBonus = when {
            qTokens.containsAll(cTokens) && cTokens.containsAll(qTokens) -> 1f
            qTokens.containsAll(cTokens) || cTokens.containsAll(qTokens) -> {
                val ratio = min(qTokens.size, cTokens.size).toFloat() /
                    max(qTokens.size, cTokens.size).toFloat()
                max(ratio, 0.7f)
            }
            else -> 0f
        }
        return max(max(normalizedLevenshtein(query, candidate), jaccard), subsetBonus)
    }

    private fun normalizedLevenshtein(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = max(a.length, b.length)
        val distance = levenshtein(a, b)
        return 1f - distance.toFloat() / maxLen.toFloat()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    companion object {
        private const val TAG = "MediaMatcher"
        private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
        private const val AUTO_MATCH_THRESHOLD = 0.6f
    }
}

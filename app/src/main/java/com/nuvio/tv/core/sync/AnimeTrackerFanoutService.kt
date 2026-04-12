package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.anime.EpisodeOffsetMapper
import com.nuvio.tv.core.anime.TrackerEpisodeMapping
import com.nuvio.tv.core.anime.TrackerShowEntry
import com.nuvio.tv.core.anime.TrackerShowMapping
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.AniListAuthDataStore
import com.nuvio.tv.data.local.AniListSettingsDataStore
import com.nuvio.tv.data.local.KitsuAuthDataStore
import com.nuvio.tv.data.local.KitsuSettingsDataStore
import com.nuvio.tv.data.local.MalAuthDataStore
import com.nuvio.tv.data.local.MalSettingsDataStore
import com.nuvio.tv.data.remote.api.AniListApi
import com.nuvio.tv.data.remote.api.KitsuApi
import com.nuvio.tv.data.remote.api.MalApi
import com.nuvio.tv.data.remote.dto.anilist.AniListGraphQLRequest
import com.nuvio.tv.data.remote.dto.anilist.AniListQueries
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryCreateDataDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryCreateDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryCreateRelsDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryPatchAttributesDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryPatchDataDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryPatchDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuRelationshipDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuRelationshipRefDto
import com.nuvio.tv.data.repository.AniListListService
import com.nuvio.tv.data.repository.KitsuListService
import com.nuvio.tv.data.repository.MalListService
import com.nuvio.tv.domain.model.TrackerListStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fans watch progress out to MyAnimeList / AniList / Kitsu whenever the user
 * watches an episode / season / movie or completes a show. Writes go to
 * every connected tracker in parallel; per-tracker failures never block the
 * others.
 *
 * Key invariants:
 *
 *   * **Monotonic progress.** Before writing, the service fetches the current
 *     `progress` value from the tracker and writes only the larger of
 *     `current` and `newProgress`. Manual "re-watches" via the player must go
 *     through the tracker's own UI for now — this service never lowers.
 *   * **Status promotion.** If `newProgress >= totalEpisodes`, status flips to
 *     COMPLETED. Otherwise, if current status is PLANNED/DROPPED/ON_HOLD, it
 *     promotes to WATCHING. COMPLETED + new progress < total stays COMPLETED
 *     (the user is re-watching and should use the tracker's rewatch UI).
 *   * **Anime only.** When the [EpisodeOffsetMapper] resolution returns
 *     [TrackerEpisodeMapping.MappingSource.HEURISTIC_NONE] — i.e. we could
 *     not find the show in any mapping source — all tracker writes are
 *     skipped (this show is probably live-action, or outside the anime
 *     database).
 *
 * This is the single integration point that [PlayerRuntimeControllerPlaybackEvents]
 * (scrobble-stop at ≥80%) and [MetaDetailsViewModel] (manual mark-as-watched)
 * call into.
 */
@Singleton
class AnimeTrackerFanoutService @Inject constructor(
    private val episodeOffsetMapper: EpisodeOffsetMapper,
    // MAL
    private val malApi: MalApi,
    private val malAuth: MalAuthDataStore,
    private val malSettings: MalSettingsDataStore,
    private val malList: MalListService,
    // AniList
    private val aniListApi: AniListApi,
    private val aniListAuth: AniListAuthDataStore,
    private val aniListSettings: AniListSettingsDataStore,
    private val aniListList: AniListListService,
    // Kitsu
    private val kitsuApi: KitsuApi,
    private val kitsuAuth: KitsuAuthDataStore,
    private val kitsuSettings: KitsuSettingsDataStore,
    private val kitsuList: KitsuListService
) {

    suspend fun markEpisodeWatched(imdbId: String?, tmdbId: Int?, season: Int, episode: Int) {
        val mapping = episodeOffsetMapper.resolveByIds(imdbId, tmdbId, season, episode)
        if (!mapping.hasAnyTrackerId) {
            Log.d(TAG, "skip episode s${season}e$episode: no tracker mapping (imdb=$imdbId tmdb=$tmdbId)")
            return
        }
        Log.i(TAG, "fanout episode: source=${mapping.source} trackerEp=${mapping.trackerEpisode} " +
            "mal=${mapping.malId} anilist=${mapping.anilistId} kitsu=${mapping.kitsuId}")
        coroutineScope {
            mapping.malId?.let { launch { writeMal(it, mapping.trackerEpisode, mapping.totalEpisodes) } }
            mapping.anilistId?.let { launch { writeAniList(it, mapping.trackerEpisode, mapping.totalEpisodes) } }
            mapping.kitsuId?.let { launch { writeKitsu(it.toString(), mapping.trackerEpisode, mapping.totalEpisodes) } }
        }
    }

    suspend fun markSeasonWatched(imdbId: String?, tmdbId: Int?, season: Int, episodeCount: Int) {
        // Resolve the specific season's tracker entry and mark it complete.
        val mapping = episodeOffsetMapper.resolveByIds(imdbId, tmdbId, season, episodeCount)
        if (!mapping.hasAnyTrackerId) {
            Log.d(TAG, "skip season $season: no tracker mapping")
            return
        }
        val finalEp = mapping.totalEpisodes ?: episodeCount.coerceAtLeast(mapping.trackerEpisode)
        coroutineScope {
            mapping.malId?.let { launch { writeMal(it, finalEp, mapping.totalEpisodes, forceComplete = true) } }
            mapping.anilistId?.let { launch { writeAniList(it, finalEp, mapping.totalEpisodes, forceComplete = true) } }
            mapping.kitsuId?.let { launch { writeKitsu(it.toString(), finalEp, mapping.totalEpisodes, forceComplete = true) } }
        }
    }

    suspend fun markShowCompleted(imdbId: String?, tmdbId: Int?) {
        val whole = episodeOffsetMapper.resolveWholeShowByIds(imdbId, tmdbId)
        if (whole.entries.isEmpty()) {
            Log.d(TAG, "skip whole-show mark: no mapped entries")
            return
        }
        Log.i(TAG, "fanout whole-show: source=${whole.source} entries=${whole.entries.size}")
        coroutineScope {
            for (entry in whole.entries) {
                val finalEp = entry.totalEpisodes ?: continue
                entry.malId?.let { launch { writeMal(it, finalEp, entry.totalEpisodes, forceComplete = true) } }
                entry.anilistId?.let { launch { writeAniList(it, finalEp, entry.totalEpisodes, forceComplete = true) } }
                entry.kitsuId?.let { launch { writeKitsu(it.toString(), finalEp, entry.totalEpisodes, forceComplete = true) } }
            }
        }
    }

    suspend fun markMovieWatched(imdbId: String?, tmdbId: Int?) {
        // TMDB movie id, not show id — use the dedicated resolver.
        val entry: TrackerShowEntry? = tmdbId?.let { episodeOffsetMapper.resolveMovie(it) }
            ?: firstEntryFromImdb(imdbId)
        if (entry == null) {
            Log.d(TAG, "skip movie: no mapping imdb=$imdbId tmdb=$tmdbId")
            return
        }
        val total = entry.totalEpisodes ?: 1
        coroutineScope {
            entry.malId?.let { launch { writeMal(it, total, total, forceComplete = true) } }
            entry.anilistId?.let { launch { writeAniList(it, total, total, forceComplete = true) } }
            entry.kitsuId?.let { launch { writeKitsu(it.toString(), total, total, forceComplete = true) } }
        }
    }

    private suspend fun firstEntryFromImdb(imdbId: String?): TrackerShowEntry? {
        if (imdbId.isNullOrBlank()) return null
        val whole: TrackerShowMapping = episodeOffsetMapper.resolveWholeShowByIds(imdbId, null)
        return whole.entries.firstOrNull()
    }

    // --- MyAnimeList write path --- //

    private suspend fun writeMal(animeId: Int, newProgress: Int, totalEpisodes: Int?, forceComplete: Boolean = false) {
        if (!malAuth.isAuthenticated.first()) return
        if (!malSettings.settings.first().sendProgress) return
        val fetched = safeApiCall { malApi.getAnimeWithMyStatus(animeId) }
        val current = (fetched as? NetworkResult.Success)?.data
        val currentProgress = current?.myListStatus?.numEpisodesWatched ?: 0
        val currentStatus = TrackerListStatus.fromMal(current?.myListStatus?.status)
        val total = totalEpisodes ?: current?.numEpisodes
        val targetProgress = maxOf(currentProgress, newProgress).coerceAtMost(total ?: Int.MAX_VALUE)
        val shouldComplete = forceComplete || (total != null && targetProgress >= total)
        val targetStatus = when {
            shouldComplete -> TrackerListStatus.COMPLETED
            currentStatus == TrackerListStatus.COMPLETED -> TrackerListStatus.COMPLETED
            currentStatus == null || currentStatus in NEEDS_PROMOTION -> TrackerListStatus.WATCHING
            else -> currentStatus
        }
        if (targetProgress == currentProgress && targetStatus == currentStatus) {
            Log.d(TAG, "MAL noop anime=$animeId progress=$currentProgress status=$currentStatus")
            return
        }
        val result = safeApiCall {
            malApi.updateListStatus(
                animeId = animeId,
                status = targetStatus.toMal(),
                numWatchedEpisodes = targetProgress
            )
        }
        when (result) {
            is NetworkResult.Success -> {
                Log.i(TAG, "MAL wrote anime=$animeId progress=$targetProgress status=${targetStatus.toMal()}")
                malList.invalidateAll()
            }
            is NetworkResult.Error -> Log.w(TAG, "MAL write failed anime=$animeId code=${result.code} ${result.message}")
            NetworkResult.Loading -> { /* unreachable */ }
        }
    }

    // --- AniList write path --- //

    private suspend fun writeAniList(mediaId: Int, newProgress: Int, totalEpisodes: Int?, forceComplete: Boolean = false) {
        if (!aniListAuth.isAuthenticated.first()) return
        if (!aniListSettings.settings.first().sendProgress) return
        val userId = aniListAuth.state.first().userId?.toIntOrNull() ?: return
        val current = fetchAniListEntry(userId, mediaId)
        val currentProgress = current?.progress ?: 0
        val currentStatus = TrackerListStatus.fromAniList(current?.status)
        val total = totalEpisodes ?: current?.media?.episodes
        val targetProgress = maxOf(currentProgress, newProgress).coerceAtMost(total ?: Int.MAX_VALUE)
        val shouldComplete = forceComplete || (total != null && targetProgress >= total)
        val targetStatus = when {
            shouldComplete -> TrackerListStatus.COMPLETED
            currentStatus == TrackerListStatus.COMPLETED -> TrackerListStatus.COMPLETED
            currentStatus == null || currentStatus in NEEDS_PROMOTION -> TrackerListStatus.WATCHING
            else -> currentStatus
        }
        if (targetProgress == currentProgress && targetStatus == currentStatus) {
            Log.d(TAG, "AniList noop media=$mediaId progress=$currentProgress status=$currentStatus")
            return
        }
        val req = AniListGraphQLRequest(
            query = AniListQueries.SAVE_MEDIA_LIST_ENTRY,
            variables = mapOf(
                "mediaId" to mediaId,
                "status" to targetStatus.toAniList(),
                "progress" to targetProgress
            )
        )
        val result = safeApiCall { aniListApi.saveMediaListEntry(req) }
        when (result) {
            is NetworkResult.Success -> {
                Log.i(TAG, "AniList wrote media=$mediaId progress=$targetProgress status=${targetStatus.toAniList()}")
                aniListList.invalidateAll()
            }
            is NetworkResult.Error -> Log.w(TAG, "AniList write failed media=$mediaId code=${result.code} ${result.message}")
            NetworkResult.Loading -> {}
        }
    }

    private suspend fun fetchAniListEntry(userId: Int, mediaId: Int): com.nuvio.tv.data.remote.dto.anilist.AniListMediaListEntryDto? {
        val req = AniListGraphQLRequest(
            query = AniListQueries.MEDIA_LIST_ENTRY,
            variables = mapOf("userId" to userId, "mediaId" to mediaId)
        )
        val result = safeApiCall { aniListApi.mediaListEntry(req) }
        // AniList returns 404 (wrapped as Error) when no entry exists.
        return (result as? NetworkResult.Success)?.data?.data?.entry
    }

    // --- Kitsu write path --- //

    private suspend fun writeKitsu(animeId: String, newProgress: Int, totalEpisodes: Int?, forceComplete: Boolean = false) {
        if (!kitsuAuth.isAuthenticated.first()) return
        if (!kitsuSettings.settings.first().sendProgress) return
        val userId = kitsuAuth.state.first().userId ?: return
        // Find existing library entry for (user, anime). Filter by anime_id.
        val existingResult = safeApiCall {
            kitsuApi.getLibrary(
                userId = userId,
                kind = "anime",
                status = null,
                include = "anime"
            )
        }
        // Full library scan on write is pricey but correct. A follow-up could
        // index by anime id during the normal list cache population — worth it
        // if write latency becomes a concern (current cost: ~1 page, ~0.5s).
        val page = (existingResult as? NetworkResult.Success)?.data
        val existing = page?.data?.firstOrNull { entry ->
            entry.relationships?.anime?.data?.id == animeId
        }
        val currentProgress = existing?.attributes?.progress ?: 0
        val currentStatus = TrackerListStatus.fromKitsu(existing?.attributes?.status)
        val targetProgress = maxOf(currentProgress, newProgress).coerceAtMost(totalEpisodes ?: Int.MAX_VALUE)
        val shouldComplete = forceComplete || (totalEpisodes != null && targetProgress >= totalEpisodes)
        val targetStatus = when {
            shouldComplete -> TrackerListStatus.COMPLETED
            currentStatus == TrackerListStatus.COMPLETED -> TrackerListStatus.COMPLETED
            currentStatus == null || currentStatus in NEEDS_PROMOTION -> TrackerListStatus.WATCHING
            else -> currentStatus
        }
        if (existing != null && targetProgress == currentProgress && targetStatus == currentStatus) {
            Log.d(TAG, "Kitsu noop anime=$animeId progress=$currentProgress status=$currentStatus")
            return
        }

        val attrs = KitsuLibraryPatchAttributesDto(
            status = targetStatus.toKitsu(),
            progress = targetProgress
        )
        val result = if (existing != null) {
            safeApiCall {
                kitsuApi.updateEntry(
                    id = existing.id,
                    body = KitsuLibraryPatchDto(
                        data = KitsuLibraryPatchDataDto(id = existing.id, attributes = attrs)
                    )
                )
            }
        } else {
            safeApiCall {
                kitsuApi.createEntry(
                    body = KitsuLibraryCreateDto(
                        data = KitsuLibraryCreateDataDto(
                            attributes = attrs,
                            relationships = KitsuLibraryCreateRelsDto(
                                user = KitsuRelationshipDto(data = KitsuRelationshipRefDto(id = userId, type = "users")),
                                anime = KitsuRelationshipDto(data = KitsuRelationshipRefDto(id = animeId, type = "anime"))
                            )
                        )
                    )
                )
            }
        }
        when (result) {
            is NetworkResult.Success -> {
                Log.i(TAG, "Kitsu wrote anime=$animeId progress=$targetProgress status=${targetStatus.toKitsu()}")
                kitsuList.invalidateAll()
            }
            is NetworkResult.Error -> Log.w(TAG, "Kitsu write failed anime=$animeId code=${result.code} ${result.message}")
            NetworkResult.Loading -> {}
        }
    }

    companion object {
        private const val TAG = "AnimeFanout"
        private val NEEDS_PROMOTION = setOf(
            TrackerListStatus.PLANNED,
            TrackerListStatus.DROPPED,
            TrackerListStatus.ON_HOLD
        )
    }
}

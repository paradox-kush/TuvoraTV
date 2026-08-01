package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class SimklTrackingProgressProvider @Inject constructor(
    private val syncRepository: SimklSyncRepository,
    private val apiClient: SimklApiClient,
    private val authStorage: SimklAuthStorage,
    private val layoutPreferences: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.SIMKL
    override val isAuthenticated = authStorage.state.map { state -> state.isAuthenticated }
        .distinctUntilChanged()
    override val allProgress = syncRepository.state.map { state ->
        state.snapshot.toSimklProgressEntries()
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val remoteProgressLoaded = syncRepository.state.map { state ->
        state.hasLoaded && state.errorMessage == null
    }.distinctUntilChanged()
    override val nextUpSeeds = combine(
        syncRepository.state,
        layoutPreferences.nextUpFromFurthestEpisode
    ) { state, preferFurthestEpisode ->
        val watchedProjection = state.snapshot.toSimklWatchedProjection()
        val seeds = state.snapshot.toSimklNextUpSeeds(
            watchedProjection = watchedProjection,
            preferFurthestEpisode = preferFurthestEpisode
        )
        SimklContinueWatchingProjectionLogger.log(
            snapshot = state.snapshot,
            watchedProjection = watchedProjection,
            seeds = seeds,
            preferFurthestEpisode = preferFurthestEpisode
        )
        seeds
    }.distinctUntilChanged()
    override val watchedMovieIds = syncRepository.state.map { state ->
        val watched = mutableSetOf<String>()
        state.snapshot.entries.forEach { entry ->
            if (entry.mediaType != SimklMediaType.MOVIES) return@forEach
            if (entry.lastWatchedAt == null && entry.status != SimklListStatus.COMPLETED) return@forEach
            val media = entry.media ?: return@forEach
            media.canonicalContentId()?.let(watched::add)
            media.ids.idValue("imdb")?.takeIf(String::isNotBlank)?.let(watched::add)
            media.ids.idValue("tmdb")?.takeIf(String::isNotBlank)?.let { watched.add("tmdb:$it") }
            media.ids.idValue("tvdb")?.takeIf(String::isNotBlank)?.let { watched.add("tvdb:$it") }
        }
        state.snapshot.toSimklProgressEntries()
            .filter { progress ->
                progress.contentType == "movie" && progress.progressPercentage > 0f && !progress.isCompleted()
            }
            .forEach { progress -> watched.remove(progress.contentId) }
        watched as Set<String>
    }.distinctUntilChanged()
    override val watchedItems = syncRepository.state.map { state ->
        state.snapshot.toSimklWatchedProjection().items
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        syncRepository.state.map { state ->
            val resolvedId = state.snapshot.resolveCanonicalContentId(contentId) ?: contentId
            val matchIds = buildSet {
                add(contentId)
                add(resolvedId)
            }
            val completed = state.snapshot.toSimklWatchedProjection().items
                .filter { item ->
                    matchIds.any { id -> item.contentId.equals(id, ignoreCase = true) } &&
                        item.season != null && item.episode != null
                }
                .associate { item ->
                    (requireNotNull(item.season) to requireNotNull(item.episode)) to item.toCompletedProgress()
                }
                .toMutableMap()
            state.snapshot.toSimklProgressEntries()
                .filter { progress ->
                    matchIds.any { id -> progress.contentId.equals(id, ignoreCase = true) } &&
                        progress.season != null && progress.episode != null
                }
                .forEach { progress ->
                    completed[requireNotNull(progress.season) to requireNotNull(progress.episode)] = progress
                }
            completed
        }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
            .distinctUntilChanged()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean> = syncRepository.state.map { state ->
        val progress = state.snapshot.toSimklProgressEntries().firstOrNull { candidate ->
            candidate.contentId.equals(contentId, ignoreCase = true) &&
                candidate.season == season && candidate.episode == episode
        }
        if (progress != null && progress.progressPercentage > 0f && !progress.isCompleted()) {
            false
        } else {
            isWatchedInSnapshot(state.snapshot, contentId, videoId, season, episode)
        }
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC)
        return syncRepository.state.value.snapshot.toSimklWatchedProjection().items
            .filter { item -> item.season != null && item.episode != null }
            .groupBy(WatchedItem::contentId)
            .mapValues { (_, items) ->
                items.map { item -> requireNotNull(item.season) to requireNotNull(item.episode) }.toSet()
            }
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        syncRepository.ensureLoaded()
        val siblings = mutableMapOf<String, MutableSet<String>>()
        syncRepository.state.value.snapshot.entries.forEach { entry ->
            val ids = entry.media?.trackingContentIds().orEmpty()
            ids.forEach { id -> siblings.getOrPut(id) { linkedSetOf() }.addAll(ids - id) }
        }
        return siblings
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        syncRepository.ensureLoaded()
        val sessions = syncRepository.state.value.snapshot.playback.filter { session ->
            val media = session.media ?: return@filter false
            val sameContent = media.canonicalContentId().equals(contentId, ignoreCase = true) ||
                syncRepository.state.value.snapshot.entries.any { entry ->
                    entry.media == media && entry.matchesSimklContentId(contentId)
                }
            val sessionSeason = session.episode?.tvdbSeason ?: session.episode?.season
            val sessionEpisode = session.episode?.tvdbNumber ?: session.episode?.number
            sameContent && (season == null || episode == null ||
                sessionSeason == season && sessionEpisode == episode)
        }
        val removed = linkedSetOf<Long>()
        sessions.mapNotNull(SimklPlaybackSession::id).forEach { sessionId ->
            try {
                apiClient.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.DELETE,
                        path = "/sync/playback/$sessionId"
                    )
                )
                removed += sessionId
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
        }
        syncRepository.removePlaybackSessions(removed)
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit

    override fun clearOptimistic() = Unit

    override fun isHiddenFromProgress(contentId: String): Boolean =
        syncRepository.state.value.snapshot.isHiddenFromContinueWatching(contentId)

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean =
        checkWatchedByVideoId(syncRepository.state.value.snapshot, videoId, episode)

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
}

internal fun SimklSyncSnapshot.toSimklNextUpSeeds(
    preferFurthestEpisode: Boolean
): List<WatchProgress> = toSimklNextUpSeeds(
    watchedProjection = toSimklWatchedProjection(),
    preferFurthestEpisode = preferFurthestEpisode
)

private fun SimklSyncSnapshot.toSimklNextUpSeeds(
    watchedProjection: SimklWatchedProjection,
    preferFurthestEpisode: Boolean
): List<WatchProgress> = selectPreferredTrackingNextUpSeeds(
    candidates = watchedProjection.items
        .filterNot { item -> isHiddenFromContinueWatching(item.contentId) }
        .filter { item -> item.season != null && item.episode != null && item.season != 0 }
        .map(WatchedItem::toCompletedProgress),
    preferFurthestEpisode = preferFurthestEpisode
)

private fun WatchedItem.toCompletedProgress(): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = contentType,
    name = title,
    poster = poster,
    backdrop = null,
    logo = null,
    videoId = if (season != null && episode != null) "$contentId:$season:$episode" else contentId,
    season = season,
    episode = episode,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = watchedAt,
    progressPercent = 100f,
    source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
    trackingProviderId = trackingProviderId,
    trackingProviderItemId = trackingProviderItemId,
    trackingSourceUrl = trackingSourceUrl
)

private fun SimklMedia.trackingContentIds(): Set<String> = buildSet {
    canonicalContentId()?.let(::add)
    ids.idValue("imdb")?.let(::add)
    ids.idValue("tmdb")?.let { add("tmdb:$it") }
    ids.idValue("tvdb")?.let { add("tvdb:$it") }
    ids.idValue("mal")?.let { add("mal:$it") }
    ids.idValue("anidb")?.let { add("anidb:$it") }
    ids.idValue("anilist")?.let { add("anilist:$it") }
    ids.idValue("kitsu")?.let { add("kitsu:$it") }
    ids.simklIdValue()?.let { add("simkl:$it") }
}

/**
 * Determines whether an episode is watched by resolving through Simkl entries.
 *
 * Resolution strategy:
 * 1. Direct match: contentId equals WatchedItem.contentId (existing behavior)
 * 2. Sibling match: contentId resolves to a canonical ID that matches a WatchedItem
 * 3. Anime video ID match: for anime, parse the MAL/AniDB/etc ID from videoId,
 *    find the Simkl entry, and check if the episode number is watched in that entry.
 */
private fun isWatchedInSnapshot(
    snapshot: SimklSyncSnapshot,
    contentId: String,
    videoId: String?,
    season: Int?,
    episode: Int?
): Boolean {
    val watchedItems = snapshot.toSimklWatchedProjection().items

    // 1. Direct match by contentId
    if (watchedItems.any { item ->
            item.contentId.equals(contentId, ignoreCase = true) &&
                item.season == season && item.episode == episode
        }) return true

    // 2. Resolve contentId through snapshot entries (sibling/alias resolution)
    val resolvedCanonicalId = snapshot.resolveCanonicalContentId(contentId)
    if (resolvedCanonicalId != null && !resolvedCanonicalId.equals(contentId, ignoreCase = true)) {
        if (watchedItems.any { item ->
                item.contentId.equals(resolvedCanonicalId, ignoreCase = true) &&
                    item.season == season && item.episode == episode
            }) return true
    }

    // 3. Anime video ID resolution: parse anime-specific ID and episode from videoId,
    //    find the corresponding Simkl entry, and check watched status directly.
    if (videoId != null && episode != null) {
        if (checkWatchedByVideoId(snapshot, videoId, episode)) return true
    }

    return false
}

/**
 * Resolve a contentId to its canonical form by finding a matching Simkl entry.
 * e.g. "mal:123" → finds entry with mal=123 → returns "tt2560140" (its IMDB/canonical ID)
 */
private fun SimklSyncSnapshot.resolveCanonicalContentId(contentId: String): String? {
    val entry = entries.firstOrNull { it.matchesSimklContentId(contentId) }
    return entry?.media?.canonicalContentId()
}

/**
 * Check if an episode is watched by resolving through the video ID.
 * Parses the anime-specific ID from videoId (e.g. "mal:123:5" → mal=123, ep=5),
 * finds the Simkl entry, and checks if that episode number has watched_at.
 *
 * Works for:
 * - Single MAL = single season: video ID "mal:123:5" → entry mal:123, episode 5
 * - Split season (multiple MAL per season): "mal:11759:3" → entry mal:11759, episode 3
 * - Franchise parent with child MAL IDs per season
 */
internal fun checkWatchedByVideoId(
    snapshot: SimklSyncSnapshot,
    videoId: String,
    episode: Int
): Boolean {
    val parsed = parseAnimeVideoId(videoId) ?: return false
    val entry = snapshot.entries.firstOrNull { entry ->
        entry.mediaType == SimklMediaType.ANIME &&
            entry.media?.toTrackingExternalIds()?.sharesAnimeIdentityWith(parsed.ids) == true
    } ?: return false

    // Check if episode number is watched in this entry
    // Use the episode number from the video ID segment if available, otherwise fall back to the
    // season/episode passed by the caller.
    val targetEpisodeNumber = parsed.episodeNumber ?: episode
    return entry.seasons.any { season ->
        season.episodes.any { ep ->
            ep.number == targetEpisodeNumber && ep.watchedAt != null
        }
    }
}

private data class AnimeVideoIdParts(
    val ids: TrackingExternalIds,
    val episodeNumber: Int?
)

/**
 * Parses a video ID like "mal:123:5" into the anime-specific ID (mal=123) and episode number (5).
 * Returns null if the video ID doesn't match an anime-tracker prefix.
 */
private fun parseAnimeVideoId(videoId: String): AnimeVideoIdParts? {
    val trimmed = videoId.trim()
    if (trimmed.isBlank()) return null
    val prefix = trimmed.substringBefore(':').lowercase()
    if (prefix !in ANIME_VIDEO_ID_PREFIXES) return null
    val afterPrefix = trimmed.substringAfter(':', "")
    val idValue = afterPrefix.substringBefore(':')
    val episodePart = afterPrefix.substringAfter(':', "").substringBefore(':')
    val ids = parseTrackingExternalIds("$prefix:$idValue")
    val episodeNumber = episodePart.toIntOrNull()
    return AnimeVideoIdParts(ids, episodeNumber)
}

private val ANIME_VIDEO_ID_PREFIXES = setOf("mal", "anidb", "anilist", "kitsu")

private fun TrackingExternalIds.sharesAnimeIdentityWith(other: TrackingExternalIds): Boolean =
    (mal != null && mal == other.mal) ||
        (anidb != null && anidb == other.anidb) ||
        (anilist != null && anilist == other.anilist) ||
        (kitsu != null && kitsu == other.kitsu)

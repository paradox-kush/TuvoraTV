package com.nuvio.tv.data.locallibrary

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LocalLibraryPreferences
import com.nuvio.tv.data.local.MatchOverrideStore
import com.nuvio.tv.data.locallibrary.source.LocalLibrarySourceFactory
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ExternalSubtitle
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.LocalMatch
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import com.nuvio.tv.domain.repository.LocalLibraryGateway
import com.nuvio.tv.domain.repository.LocalLibraryGateway.Companion.ADDON_ID
import com.nuvio.tv.domain.repository.LocalLibraryGateway.Companion.LOCAL_ID_PREFIX
import com.nuvio.tv.domain.repository.LocalLibraryGateway.Companion.SYNTHETIC_BASE_URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLibraryGatewayImpl @Inject constructor(
    private val preferences: LocalLibraryPreferences,
    private val index: LocalLibraryIndex,
    private val overrideStore: MatchOverrideStore,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbService: TmdbService,
    private val sourceFactory: LocalLibrarySourceFactory
) : LocalLibraryGateway {

    override fun synthesizeAddon(): Flow<Addon?> =
        preferences.sources.map { configs ->
            val enabled = configs.filter { it.enabled }
            if (enabled.isEmpty()) return@map null
            buildAddon(enabled)
        }

    override fun isLocalLibrary(addonId: String?, baseUrl: String?): Boolean {
        if (addonId == ADDON_ID) return true
        if (baseUrl != null && baseUrl.startsWith(SYNTHETIC_BASE_URL)) return true
        return false
    }

    override fun isLocalId(id: String?): Boolean =
        id != null && id.startsWith(LOCAL_ID_PREFIX)

    override suspend fun catalog(
        catalogId: String,
        skip: Int,
        skipStep: Int
    ): NetworkResult<CatalogRow> {
        val parsed = CatalogId.parse(catalogId)
            ?: return NetworkResult.Error("Unknown local catalog: $catalogId")
        val source = preferences.sources.first().firstOrNull { it.id == parsed.sourceId }
            ?: return NetworkResult.Error("Source ${parsed.sourceId} not configured")

        val previews = buildPreviewsForSource(source, parsed.type)
        val paged = previews.drop(skip).take(skipStep.takeIf { it > 0 } ?: 100)

        return NetworkResult.Success(
            CatalogRow(
                addonId = ADDON_ID,
                addonName = ADDON_NAME,
                addonBaseUrl = SYNTHETIC_BASE_URL,
                catalogId = catalogId,
                catalogName = "${source.displayName} — ${parsed.type.displayName()}",
                type = parsed.type,
                items = paged,
                hasMore = previews.size > skip + paged.size,
                currentPage = (skip / (skipStep.takeIf { it > 0 } ?: 100)),
                supportsSkip = true,
                skipStep = skipStep.takeIf { it > 0 } ?: 100
            )
        )
    }

    override suspend fun meta(type: String, id: String): NetworkResult<Meta> {
        val parsedId = MetaId.parse(id)
            ?: return NetworkResult.Error("Unknown local id: $id")
        return when (parsedId) {
            is MetaId.Movie -> resolveMovieMeta(parsedId.tmdbId)
            is MetaId.Series -> resolveSeriesMeta(parsedId.tmdbId)
        }
    }

    override suspend fun streams(
        type: String,
        id: String,
        season: Int?,
        episode: Int?
    ): NetworkResult<List<Stream>> {
        val resolution = resolveExternalId(type, id)
            ?: return if (id.startsWith(LOCAL_ID_PREFIX)) {
                NetworkResult.Error("Unknown local id: $id")
            } else {
                // Foreign id we couldn't resolve to a local match — gracefully
                // sit out so the aggregate failure path doesn't report this
                // addon as broken.
                NetworkResult.Success(emptyList())
            }
        val effectiveSeason = season ?: resolution.season
        val effectiveEpisode = episode ?: resolution.episode
        val items = matchedItemsFor(resolution.metaId, effectiveSeason, effectiveEpisode)
        if (items.isEmpty()) return NetworkResult.Success(emptyList())

        val streams = items.mapNotNull { (config, item) ->
            val source = sourceFactory.create(config)
            val resolved = runCatching { source.resolveStream(item) }
                .onFailure { Log.w(TAG, "resolveStream failed for ${item.itemKey}", it) }
                .getOrNull() ?: return@mapNotNull null
            Stream(
                name = config.displayName,
                title = item.fileName,
                description = item.fileName,
                url = resolved.url,
                ytId = null,
                infoHash = null,
                fileIdx = null,
                externalUrl = null,
                behaviorHints = if (resolved.headers.isNotEmpty()) {
                    StreamBehaviorHints(
                        notWebReady = null,
                        bingeGroup = null,
                        countryWhitelist = null,
                        proxyHeaders = ProxyHeaders(request = resolved.headers, response = null),
                        videoSize = item.sizeBytes,
                        filename = item.fileName
                    )
                } else null,
                addonName = ADDON_NAME,
                addonLogo = null,
                externalSubtitles = resolved.subtitles.map { sub ->
                    ExternalSubtitle(
                        url = sub.url,
                        displayName = sub.displayName,
                        language = sub.language,
                        mimeType = sub.mimeType,
                        isForced = sub.isForced,
                        headers = sub.headers
                    )
                }
            )
        }
        return NetworkResult.Success(streams)
    }

    private fun buildAddon(enabled: List<LocalLibrarySourceConfig>): Addon {
        val catalogs = enabled.flatMap { config ->
            listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = CatalogId.format(config.id, ContentType.MOVIE),
                    name = "${config.displayName} — Movies",
                    showInHome = true,
                    hasExplicitShowInHome = true
                ),
                CatalogDescriptor(
                    type = ContentType.SERIES,
                    id = CatalogId.format(config.id, ContentType.SERIES),
                    name = "${config.displayName} — Series",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        }
        return Addon(
            id = ADDON_ID,
            name = ADDON_NAME,
            displayName = ADDON_NAME,
            version = "1.0.0",
            description = "Your local Jellyfin / SMB / on-device library",
            logo = null,
            baseUrl = SYNTHETIC_BASE_URL,
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(
                AddonResource("catalog", listOf("movie", "series"), null),
                AddonResource("meta", listOf("movie", "series"), listOf(LOCAL_ID_PREFIX)),
                AddonResource("stream", listOf("movie", "series"), listOf(LOCAL_ID_PREFIX))
            ),
            idPrefixes = listOf(LOCAL_ID_PREFIX)
        )
    }

    private suspend fun buildPreviewsForSource(
        source: LocalLibrarySourceConfig,
        type: ContentType
    ): List<MetaPreview> {
        val items = index.load(source.id)
        val matches = overrideStore.matches.first()
        val matched = items.mapNotNull { item ->
            val match = matches[item.itemKey] ?: return@mapNotNull null
            if (match.contentType != type) return@mapNotNull null
            item to match
        }
        // Dedupe by tmdbId (multiple files of same movie / episodes of same series)
        val byTmdb = matched.groupBy { (_, match) -> match.tmdbId }
        return byTmdb.mapNotNull { (tmdbId, group) ->
            buildPreview(tmdbId, type, group.first().first)
        }
    }

    private suspend fun buildPreview(
        tmdbId: Int,
        type: ContentType,
        sampleItem: ScannedItem
    ): MetaPreview? {
        val enrichment = runCatching {
            tmdbMetadataService.fetchEnrichment(tmdbId.toString(), type)
        }.getOrNull()
        val title = enrichment?.localizedTitle
            ?: sampleItem.parsedTitle
            ?: sampleItem.fileName.substringBeforeLast('.')
        return MetaPreview(
            id = MetaId.format(tmdbId, type),
            type = type,
            name = title,
            poster = enrichment?.poster,
            posterShape = PosterShape.POSTER,
            background = enrichment?.backdrop,
            logo = enrichment?.logo,
            description = enrichment?.description,
            releaseInfo = enrichment?.releaseInfo ?: sampleItem.parsedYear?.toString(),
            imdbRating = enrichment?.rating?.toFloat(),
            genres = enrichment?.genres ?: emptyList()
        )
    }

    private suspend fun resolveMovieMeta(tmdbId: Int): NetworkResult<Meta> {
        val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId.toString(), ContentType.MOVIE)
            ?: return NetworkResult.Error("TMDB enrichment missing for movie $tmdbId")
        return NetworkResult.Success(
            buildMeta(tmdbId, ContentType.MOVIE, enrichment, videos = emptyList())
        )
    }

    private suspend fun resolveSeriesMeta(tmdbId: Int): NetworkResult<Meta> {
        val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId.toString(), ContentType.SERIES)
            ?: return NetworkResult.Error("TMDB enrichment missing for series $tmdbId")
        val configs = preferences.sources.first().associateBy { it.id }
        val matches = overrideStore.matches.first()
        val episodes = mutableListOf<Video>()
        configs.values.forEach { config ->
            val items = index.load(config.id)
            items.forEach { item ->
                val match = matches[item.itemKey] ?: return@forEach
                if (match.tmdbId != tmdbId) return@forEach
                val season = match.season ?: item.parsedSeason ?: return@forEach
                val episode = match.episode ?: item.parsedEpisode ?: return@forEach
                episodes += Video(
                    id = MetaId.formatEpisode(tmdbId, season, episode),
                    title = "Episode $episode",
                    released = null,
                    thumbnail = null,
                    season = season,
                    episode = episode,
                    overview = null
                )
            }
        }
        return NetworkResult.Success(
            buildMeta(tmdbId, ContentType.SERIES, enrichment, episodes.distinctBy { it.season to it.episode })
        )
    }

    private fun buildMeta(
        tmdbId: Int,
        type: ContentType,
        enrichment: TmdbEnrichment,
        videos: List<Video>
    ): Meta = Meta(
        id = if (type == ContentType.MOVIE) MetaId.format(tmdbId, ContentType.MOVIE)
        else MetaId.format(tmdbId, ContentType.SERIES),
        type = type,
        name = enrichment.localizedTitle ?: "Unknown",
        poster = enrichment.poster,
        posterShape = PosterShape.POSTER,
        background = enrichment.backdrop,
        logo = enrichment.logo,
        description = enrichment.description,
        releaseInfo = enrichment.releaseInfo,
        imdbRating = enrichment.rating?.toFloat(),
        genres = enrichment.genres,
        runtime = enrichment.runtimeMinutes?.let { "${it}m" },
        director = enrichment.director,
        writer = enrichment.writer,
        cast = enrichment.castMembers.map { it.name },
        castMembers = enrichment.castMembers,
        videos = videos,
        productionCompanies = enrichment.productionCompanies,
        networks = enrichment.networks,
        ageRating = enrichment.ageRating,
        country = enrichment.countries?.firstOrNull(),
        awards = null,
        language = enrichment.language,
        links = emptyList(),
        trailers = enrichment.trailers,
        trailerYtIds = enrichment.trailers.mapNotNull { it.ytId },
        status = enrichment.status
    )

    /**
     * Resolves an incoming stream-request id (which may be a local `nuvio-local:`,
     * an IMDB `tt...`, or a `tmdb:` id) into a [MetaId] plus optional season/episode
     * extracted from the id suffix. Returns null when the id can't be resolved to a
     * local-library lookup (e.g. IMDB id with no TMDB mapping, or a totally foreign
     * format) — callers translate that to either a graceful empty result (for
     * foreign ids) or an "Unknown local id" error (for malformed nuvio-local: ids).
     */
    private suspend fun resolveExternalId(type: String, id: String): ResolvedId? {
        // 1) nuvio-local:... — existing path.
        if (id.startsWith(LOCAL_ID_PREFIX)) {
            val parsed = MetaId.parse(id) ?: return null
            val (s, e) = when (parsed) {
                is MetaId.Series -> parsed.season to parsed.episode
                is MetaId.Movie -> null to null
            }
            return ResolvedId(parsed, s, e)
        }

        val contentType = when (type.lowercase()) {
            "movie" -> ContentType.MOVIE
            "series", "tv", "show" -> ContentType.SERIES
            else -> return null
        }

        // Strip an optional :S:E (or :S/E) suffix to find the base content id.
        val parts = id.split(':')
        val basePart = parts.first()
        val suffixSeason = parts.getOrNull(1)?.toIntOrNull()
        val suffixEpisode = parts.getOrNull(2)?.toIntOrNull()

        // 2) tmdb:<id> (and tmdb:<id>:S:E).
        if (basePart.equals("tmdb", ignoreCase = true)) {
            val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val tmdbSeason = parts.getOrNull(2)?.toIntOrNull()
            val tmdbEpisode = parts.getOrNull(3)?.toIntOrNull()
            return ResolvedId(buildMetaId(contentType, tmdbId), tmdbSeason, tmdbEpisode)
        }

        // 3) IMDB tt\d+ (and tt\d+:S:E).
        if (basePart.startsWith("tt") && basePart.length > 2 && basePart.drop(2).all { it.isDigit() }) {
            val tmdbId = tmdbService.imdbToTmdb(basePart, type) ?: return null
            return ResolvedId(buildMetaId(contentType, tmdbId), suffixSeason, suffixEpisode)
        }

        return null
    }

    private fun buildMetaId(type: ContentType, tmdbId: Int): MetaId = when (type) {
        ContentType.MOVIE -> MetaId.Movie(tmdbId)
        else -> MetaId.Series(tmdbId)
    }

    private data class ResolvedId(val metaId: MetaId, val season: Int?, val episode: Int?)

    /** Returns `(sourceConfig, scannedItem)` pairs matching the requested meta id + S/E. */
    private suspend fun matchedItemsFor(
        meta: MetaId,
        season: Int?,
        episode: Int?
    ): List<Pair<LocalLibrarySourceConfig, ScannedItem>> {
        val configs = preferences.sources.first().filter { it.enabled }.associateBy { it.id }
        val matches = overrideStore.matches.first()
        val out = mutableListOf<Pair<LocalLibrarySourceConfig, ScannedItem>>()
        configs.values.forEach { config ->
            index.load(config.id).forEach { item ->
                val match = matches[item.itemKey] ?: return@forEach
                if (match.tmdbId != meta.tmdbId) return@forEach
                if (match.contentType != meta.contentType) return@forEach
                if (meta is MetaId.Series) {
                    val itemSeason = match.season ?: item.parsedSeason
                    val itemEpisode = match.episode ?: item.parsedEpisode
                    if (season != null && itemSeason != season) return@forEach
                    if (episode != null && itemEpisode != episode) return@forEach
                }
                out += config to item
            }
        }
        return out
    }

    private fun ContentType.displayName(): String = when (this) {
        ContentType.MOVIE -> "Movies"
        ContentType.SERIES -> "Series"
        else -> name.lowercase().replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val TAG = "LocalLibraryGateway"
        private const val ADDON_NAME = "Local Library"
    }

    /** Catalog id codec: `local_<sourceId>_<movie|series>` */
    private data class CatalogId(val sourceId: String, val type: ContentType) {
        companion object {
            fun format(sourceId: String, type: ContentType): String =
                "local_${sourceId}_${type.name.lowercase()}"

            fun parse(catalogId: String): CatalogId? {
                if (!catalogId.startsWith("local_")) return null
                val rest = catalogId.removePrefix("local_")
                val lastUnderscore = rest.lastIndexOf('_')
                if (lastUnderscore <= 0) return null
                val sourceId = rest.substring(0, lastUnderscore)
                val typeStr = rest.substring(lastUnderscore + 1)
                val type = when (typeStr) {
                    "movie" -> ContentType.MOVIE
                    "series" -> ContentType.SERIES
                    else -> return null
                }
                return CatalogId(sourceId, type)
            }
        }
    }

    /** Meta id codec for the synthetic addon. */
    private sealed class MetaId {
        abstract val tmdbId: Int
        abstract val contentType: ContentType

        data class Movie(override val tmdbId: Int) : MetaId() {
            override val contentType = ContentType.MOVIE
        }

        data class Series(override val tmdbId: Int, val season: Int? = null, val episode: Int? = null) : MetaId() {
            override val contentType = ContentType.SERIES
        }

        companion object {
            fun format(tmdbId: Int, type: ContentType): String = when (type) {
                ContentType.MOVIE -> "${LOCAL_ID_PREFIX}movie:$tmdbId"
                else -> "${LOCAL_ID_PREFIX}series:$tmdbId"
            }

            fun formatEpisode(tmdbId: Int, season: Int, episode: Int): String =
                "${LOCAL_ID_PREFIX}series:$tmdbId:$season:$episode"

            fun parse(id: String): MetaId? {
                if (!id.startsWith(LOCAL_ID_PREFIX)) return null
                val rest = id.removePrefix(LOCAL_ID_PREFIX)
                val parts = rest.split(':')
                return when {
                    parts.size >= 2 && parts[0] == "movie" -> parts[1].toIntOrNull()?.let { Movie(it) }
                    parts.size >= 2 && parts[0] == "series" -> {
                        val tmdb = parts[1].toIntOrNull() ?: return null
                        val season = parts.getOrNull(2)?.toIntOrNull()
                        val episode = parts.getOrNull(3)?.toIntOrNull()
                        Series(tmdb, season, episode)
                    }
                    else -> null
                }
            }
        }
    }
}

package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.domain.model.Meta
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layers TMDB art / people / ratings onto a server item's native metadata when the server knows the item's TMDB
 * id (design 5.4: "Tuvora TMDB enrichment when a TMDB/IMDB id is present") - the same merge the IPTV lane applies
 * to an Xtream VOD item, so a server title's detail page looks like every other one. The server's own values
 * (name, overview, runtime, people, episodes) stay when TMDB has nothing better.
 */
@Singleton
internal class MediaServerTmdbEnricher @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
) {
    suspend fun enrich(meta: Meta, tmdbId: String): Meta {
        val enrichment = tmdbMetadataService.fetchEnrichment(tmdbId, meta.type) ?: return meta
        return meta.copy(
            name = enrichment.localizedTitle ?: enrichment.originalTitle ?: meta.name,
            poster = enrichment.poster ?: meta.poster,
            background = enrichment.backdrop ?: meta.background,
            logo = enrichment.logo ?: meta.logo,
            description = enrichment.description ?: meta.description,
            releaseInfo = enrichment.releaseInfo ?: meta.releaseInfo,
            status = enrichment.status ?: meta.status,
            imdbRating = enrichment.rating?.toFloat() ?: meta.imdbRating,
            genres = enrichment.genres.ifEmpty { meta.genres },
            runtime = enrichment.runtimeMinutes?.toString() ?: meta.runtime,
            ageRating = enrichment.ageRating ?: meta.ageRating,
            country = enrichment.countries?.joinToString(", ") ?: meta.country,
            language = enrichment.language ?: meta.language,
            director = enrichment.director.ifEmpty { meta.director },
            writer = enrichment.writer.ifEmpty { meta.writer },
            cast = enrichment.castMembers.map { it.name }.ifEmpty { meta.cast },
            castMembers = enrichment.castMembers.ifEmpty { meta.castMembers },
            productionCompanies = enrichment.productionCompanies.ifEmpty { meta.productionCompanies },
            networks = enrichment.networks.ifEmpty { meta.networks },
            trailers = enrichment.trailers.ifEmpty { meta.trailers },
        )
    }
}

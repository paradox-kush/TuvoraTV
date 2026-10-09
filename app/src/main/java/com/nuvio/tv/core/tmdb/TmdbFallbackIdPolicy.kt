package com.nuvio.tv.core.tmdb

/** What the TMDB meta fallback can look up for a content id when no add-on serves its meta. */
sealed interface TmdbFallbackId {
    data class Tmdb(val tmdbId: Int) : TmdbFallbackId
    data class Imdb(val imdbId: String) : TmdbFallbackId
}

object TmdbFallbackIdPolicy {
    fun classify(id: String): TmdbFallbackId? {
        val raw = id.trim()
        if (raw.startsWith("tmdb:", ignoreCase = true)) {
            return raw.substringAfter(':').substringBefore(':').toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let(TmdbFallbackId::Tmdb)
        }
        val imdbId = raw.substringBefore(':').lowercase()
        return if (IMDB_ID.matches(imdbId)) TmdbFallbackId.Imdb(imdbId) else null
    }

    private val IMDB_ID = Regex("tt[0-9]+")
}

package com.nuvio.tv.core.player

/**
 * Which id the player may send to third-party subtitle add-ons (F17 + a privacy fix). Hand-port of
 * NuvioMobile's commonMain `AddonSubtitleIdPolicy` (same rules).
 *
 * IPTV items play under `xtream:{account}:{kind}:{id}` ids whose account part is the raw playlist
 * key — server+username (Xtream), the full playlist URL with username/password (M3U link), the MAC
 * (Stalker). An add-on declaring no idPrefixes used to receive that id. Those ids must never leave
 * the app; an IPTV movie/episode is looked up under its public IMDb id (`tt…` / `tt…:S:E`) instead,
 * and with no public id nothing is requested.
 */
object AddonSubtitleIdPolicy {
    private const val PROVIDER_PREFIX = "xtream:"
    private val IMDB_ID = Regex("^tt\\d{5,10}$")

    fun isProviderScoped(id: String?): Boolean = id?.trim()?.startsWith(PROVIDER_PREFIX) == true

    fun publicVideoId(imdbId: String?, isSeries: Boolean, season: Int?, episode: Int?): String? {
        val imdb = imdbId?.trim()?.lowercase()?.takeIf { IMDB_ID.matches(it) } ?: return null
        if (!isSeries) return imdb
        val s = season?.takeIf { it >= 0 } ?: return null
        val e = episode?.takeIf { it > 0 } ?: return null
        return "$imdb:$s:$e"
    }

    fun requestVideoId(activeVideoId: String?, resolvedPublicId: String?): String? {
        val id = activeVideoId?.takeIf { it.isNotBlank() } ?: return null
        if (!isProviderScoped(id)) return id
        return resolvedPublicId?.takeUnless { isProviderScoped(it) }
    }

    fun requestType(contentType: String, publicId: String): String =
        if (publicId.count { it == ':' } >= 2) "series" else contentType
}

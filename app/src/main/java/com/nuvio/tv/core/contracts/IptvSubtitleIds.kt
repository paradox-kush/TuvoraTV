package com.nuvio.tv.core.contracts

/**
 * Neutral port (F17): the public id (`tt…` or `tt…:S:E`) subtitle add-ons know an IPTV movie/episode
 * by, so upstream-aligned code (SubtitleRepositoryImpl) never names the fork's IPTV feature. Null =
 * unknown; the caller then asks no add-on (provider-scoped ids carry the playlist key).
 */
interface IptvSubtitleIds {
    suspend fun publicSubtitleVideoId(contentId: String, season: Int?, episode: Int?): String?
}

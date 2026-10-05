package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.ContentChannel
import com.nuvio.tv.core.iptv.content.ContentEpisode
import com.nuvio.tv.core.iptv.content.ContentSeries
import com.nuvio.tv.core.iptv.content.ContentVod
import com.nuvio.tv.core.iptv.content.M3UEntry
import com.nuvio.tv.core.iptv.content.M3UKind
import com.nuvio.tv.core.iptv.content.M3UParser
import com.nuvio.tv.core.iptv.identity.M3uIdentity
import com.nuvio.tv.core.iptv.identity.M3uSeriesGrouping

/** What one parsed M3U entry becomes in the content DB (pure — see [M3uIngestMapping]). */
sealed interface M3uIngestRow {
    data class Channel(val row: ContentChannel) : M3uIngestRow
    data class Movie(val row: ContentVod) : M3uIngestRow
    data class Episode(val series: ContentSeries, val row: ContentEpisode) : M3uIngestRow
}

/**
 * B64 — the pure decision "which row, under which id, does this M3U entry become" on TV. The ids are
 * the phone's (NuvioMobile `M3uIngestMapping`), so a favourite / Continue Watching / watched mark made
 * on one device is the same item on the other:
 *  - channel / movie sid = [M3uIdentity.itemSid] of the stream URL with the playlist's login removed
 *    (was the entry's ORDINAL in the file — a provider reorder re-pointed saved items);
 *  - series sid = [M3uSeriesGrouping.seriesSid] of the shared series key (was an ordinal per
 *    category + name), episode id = hex([M3uIdentity.itemSid]) (was `e<sequence>`);
 *  - a `/movie/` row named "Show S01E02" is an episode of Show (TV's promotion, now shared — D2);
 *  - category id = the phone's hash of the group name (was the raw name); the name is kept for display.
 *    A row with no group keeps no category (TV shows it under "All" only, as before).
 */
object M3uIngestMapping {

    fun categoryId(group: String?): String? =
        group?.takeIf { it.isNotBlank() }?.let { M3uIdentity.sidOf(it).toString() }

    fun map(entry: M3UEntry, login: M3uIdentity.Login?): M3uIngestRow {
        val cat = categoryId(entry.group)
        return when (entry.kind) {
            M3UKind.LIVE -> M3uIngestRow.Channel(
                ContentChannel(M3uIdentity.itemSid(entry.url, login), entry.name, entry.logo, entry.tvgId, cat, entry.url),
            )
            M3UKind.VOD -> {
                val promoted = M3uSeriesGrouping.promotion(entry.name, entry.tvgName, entry.group)
                if (promoted != null) {
                    episode(entry, promoted.seriesKey, promoted.seriesName, promoted.season, promoted.episode, login, cat)
                } else {
                    M3uIngestRow.Movie(ContentVod(M3uIdentity.itemSid(entry.url, login), entry.name, entry.logo, cat, entry.url, entry.ext))
                }
            }
            M3UKind.SERIES -> {
                val se = M3uSeriesGrouping.seasonEpisodeOf(entry.name)
                episode(
                    entry,
                    M3uSeriesGrouping.seriesKeyOf(entry.name, entry.tvgName, entry.group),
                    M3UParser.seriesEpisodeOf(entry.name)?.first ?: entry.name,
                    se?.first ?: 1,
                    se?.second ?: 0,
                    login,
                    cat,
                )
            }
        }
    }

    private fun episode(
        entry: M3UEntry, key: String, seriesName: String, season: Int, episode: Int, login: M3uIdentity.Login?, cat: String?,
    ): M3uIngestRow.Episode {
        val seriesSid = M3uSeriesGrouping.seriesSid(key)
        return M3uIngestRow.Episode(
            series = ContentSeries(seriesSid, seriesName, entry.logo, cat),
            row = ContentEpisode(seriesSid, M3uIdentity.episodeId(entry.url, login), season, episode, entry.name, entry.logo, entry.url, entry.ext),
        )
    }
}

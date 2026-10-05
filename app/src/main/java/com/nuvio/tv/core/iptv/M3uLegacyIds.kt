package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.ContentCategory
import com.nuvio.tv.core.iptv.content.ContentChannel
import com.nuvio.tv.core.iptv.content.ContentEpisode
import com.nuvio.tv.core.iptv.content.ContentSeries
import com.nuvio.tv.core.iptv.content.ContentVod
import com.nuvio.tv.core.iptv.content.M3UEntry
import com.nuvio.tv.core.iptv.content.M3UParser
import com.nuvio.tv.core.iptv.identity.M3uIdentity

/**
 * B64 phase 3 (TV) — where every pre-B64 id of an M3U catalog moves to.
 *
 * TV's old ids are NOT derivable from anything but this device's catalog: channel / movie / series ids
 * were ORDINALS, episode ids `e<sequence>`, category ids the raw group name. So right before the first
 * login-free ingest replaces the old catalog, every old row is mapped through the new ingest rules
 * ([M3uIngestMapping] over the row rebuilt as an M3U entry) and the old → new pairs are kept on the
 * device ([com.nuvio.tv.core.iptv.content.IptvContentDb.captureLegacyIds]); each profile then moves its
 * saved ids with them ([M3uSavedIdRewrite]). Ids are content-id SUFFIXES (`live:12`, `episode:e7`,
 * `cat:live:UK`) so a later playlist-key re-key (Step 0 adoption) does not invalidate them.
 *
 *  - a channel / movie / episode keeps its kind and gets the new id;
 *  - a movie the shared grouping now reads as an episode ("Show S01E02") moves to that episode, with the
 *    show it now belongs to (a saved movie becomes the show; its progress becomes episode progress);
 *  - an episode the shared grouping now reads as a movie keeps no mapping (its saved refs stay; rare:
 *    only names TV's old SxxExx regex matched and the shared one does not);
 *  - a series maps to the series of its first episode; a category to the hash of its name.
 */
object M3uLegacyIds {

    data class Move(
        val oldId: String,
        val newId: String,
        /** Set when a movie became an episode: the show it now belongs to, and where. */
        val seriesId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val seriesName: String? = null,
    )

    private fun entry(name: String, group: String?, url: String, tvgId: String? = null): M3UEntry {
        val ext = M3UParser.extOf(url)
        return M3UEntry(name = name, tvgId = tvgId, tvgName = null, logo = null, group = group, url = url, kind = M3UParser.classify(url, ext), ext = ext)
    }

    fun channel(row: ContentChannel, login: M3uIdentity.Login?): Move? =
        when (val m = M3uIngestMapping.map(entry(row.name, row.categoryId, row.url, row.tvgId), login)) {
            is M3uIngestRow.Channel -> Move("live:${row.sid}", "live:${m.row.sid}")
            else -> null
        }

    fun movie(row: ContentVod, login: M3uIdentity.Login?): Move? =
        when (val m = M3uIngestMapping.map(entry(row.name, row.categoryId, row.url), login)) {
            is M3uIngestRow.Movie -> Move("vod:${row.sid}", "vod:${m.row.sid}")
            is M3uIngestRow.Episode -> Move(
                "vod:${row.sid}", "episode:${m.row.episodeSid}",
                seriesId = "series:${m.series.sid}", season = m.row.season, episode = m.row.episodeNum, seriesName = m.series.name,
            )
            else -> null
        }

    /** [series] = the old header the episode was grouped under (its category is the episode's group). */
    fun episode(row: ContentEpisode, series: ContentSeries?, login: M3uIdentity.Login?): List<Move> {
        val m = M3uIngestMapping.map(entry(row.title, series?.categoryId, row.url), login) as? M3uIngestRow.Episode ?: return emptyList()
        val moves = mutableListOf(Move("episode:${row.episodeSid}", "episode:${m.row.episodeSid}"))
        if (series != null) moves += Move("series:${series.sid}", "series:${m.series.sid}")
        return moves
    }

    fun category(type: String, row: ContentCategory): Move =
        Move("cat:$type:${row.id}", "cat:$type:${M3uIngestMapping.categoryId(row.id)}")
}

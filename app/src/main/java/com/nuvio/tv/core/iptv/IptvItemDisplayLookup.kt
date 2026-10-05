package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T2: an item's name + artwork from the catalogs this device already holds — the ingested content DB
 * (M3U / Stalker rows) first, then the Xtream match index (the panel's bulk list). No network.
 * Feeds [rebuildFromId] so a saved item opened before its catalog was browsed this session (Library,
 * Continue Watching, a live favourite) is not a nameless page.
 */
@Singleton
class IptvItemDisplayLookup @Inject constructor(
    private val db: IptvContentDb,
    private val matchIndex: XtreamMatchIndex,
) : XtreamItemDisplaySource {
    override suspend fun display(accountId: String, kind: XtreamKind, streamId: Int): XtreamItemDisplay? {
        val row = when (kind) {
            XtreamKind.VOD -> db.vodRow(accountId, streamId)?.let { XtreamItemDisplay(it.name, it.logo) }
            XtreamKind.SERIES -> db.seriesRow(accountId, streamId)?.let { XtreamItemDisplay(it.name, it.logo) }
            XtreamKind.LIVE -> db.channelRow(accountId, streamId)?.let { XtreamItemDisplay(it.name, it.logo) }
        }
        if (row != null && row.name.isNotBlank()) return row
        val matchKind = when (kind) {
            XtreamKind.VOD -> MatchKind.MOVIE
            XtreamKind.SERIES -> MatchKind.SERIES
            XtreamKind.LIVE -> MatchKind.LIVE
        }
        return matchIndex.item(accountId, matchKind, streamId)
            ?.takeIf { it.name.isNotBlank() }
            ?.let { XtreamItemDisplay(it.name, it.poster) }
    }
}

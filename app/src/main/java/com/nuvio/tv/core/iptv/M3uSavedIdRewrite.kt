package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

/**
 * B64 phase 3 (TV) — moves one profile's saved refs of an M3U playlist onto the login-free ids, given
 * the device's [M3uLegacyIds.Move]s for that playlist ([moves], keyed by old suffix). Pure: every
 * function returns the replacement, or null when the value is not this playlist's or has no move.
 */
class M3uSavedIdRewrite(private val playlistId: String, private val moves: Map<String, M3uLegacyIds.Move>) {

    private val prefix = XtreamItemRegistry.accountPrefix(playlistId)

    private fun moveOf(id: String): M3uLegacyIds.Move? =
        if (id.startsWith(prefix)) moves[id.removePrefix(prefix)] else null

    /** Same-kind rename of a full content id (null = untouched). */
    fun contentId(id: String): String? = moveOf(id)?.takeIf { it.seriesId == null }?.let { prefix + it.newId }

    fun library(item: SavedLibraryItem): SavedLibraryItem? {
        val move = moveOf(item.id) ?: return null
        val series = move.seriesId
        return if (series == null) item.copy(id = prefix + move.newId)
        else item.copy(id = prefix + series, type = "series", name = move.seriesName ?: item.name)
    }

    fun progress(p: WatchProgress): WatchProgress? {
        val promoted = moveOf(p.videoId)?.takeIf { it.seriesId != null }
        if (promoted != null) {
            return p.copy(
                contentId = prefix + promoted.seriesId,
                contentType = "series",
                videoId = prefix + promoted.newId,
                season = promoted.season,
                episode = promoted.episode,
                episodeTitle = p.episodeTitle ?: p.name,
                name = promoted.seriesName ?: p.name,
            )
        }
        val content = contentId(p.contentId)
        val video = contentId(p.videoId)
        if (content == null && video == null) return null
        return p.copy(contentId = content ?: p.contentId, videoId = video ?: p.videoId)
    }

    fun watched(w: WatchedItem): WatchedItem? {
        val move = moveOf(w.contentId) ?: return null
        val series = move.seriesId
        return if (series == null) w.copy(contentId = prefix + move.newId)
        else w.copy(contentId = prefix + series, contentType = "series", season = move.season, episode = move.episode, title = move.seriesName ?: w.title)
    }

    fun liveRef(ref: LiveChannelRef): LiveChannelRef? = contentId(ref.id)?.let { ref.copy(id = it) }

    /**
     * A category include-list ([com.nuvio.tv.core.iptv.CategorySelections] of one content type): raw
     * group names -> the shared hashed ids. Null when nothing changed (or "all" = null stays null).
     */
    fun categories(contentType: String, selection: List<String>?): List<String>? {
        if (selection == null) return null
        val dbType = when (contentType) {
            XtreamAccount.TYPE_LIVE -> IptvContentDb.TYPE_LIVE
            XtreamAccount.TYPE_MOVIES -> IptvContentDb.TYPE_VOD
            XtreamAccount.TYPE_SERIES -> IptvContentDb.TYPE_SERIES
            else -> return null
        }
        var changed = false
        val out = selection.map { raw ->
            moves["cat:$dbType:$raw"]?.newId?.removePrefix("cat:$dbType:")?.also { changed = true } ?: raw
        }
        return if (changed) out.distinct() else null
    }

    /** Every suffix a saved value of this playlist could need (for the indexed legacy-id lookup). */
    fun suffixOf(id: String): String? = if (id.startsWith(prefix)) id.removePrefix(prefix) else null
}

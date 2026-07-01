package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.local.LiveChannelRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ordered channel list currently being watched, so the fullscreen player can zap
 * up/down to the next/previous channel (TiViMate-style). The guide sets it before launching
 * a channel; the player reads the neighbour of the channel it's on.
 *
 * ponytail: a single process-lifetime list — only one live session is active at a time.
 */
@Singleton
class XtreamLivePlaylist @Inject constructor() {
    @Volatile private var channels: List<LiveChannelRef> = emptyList()

    fun set(list: List<LiveChannelRef>) { channels = list }

    /** The channel [delta] steps from [contentId] (e.g. +1 = next, -1 = previous), or null
     *  if there's no list / no neighbour in that direction. */
    fun relativeTo(contentId: String, delta: Int): LiveChannelRef? {
        val list = channels
        val i = list.indexOfFirst { it.id == contentId }
        if (i < 0) return null
        val j = i + delta
        return list.getOrNull(j)
    }
}

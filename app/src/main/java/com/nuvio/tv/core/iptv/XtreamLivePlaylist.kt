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

    /**
     * The channel [delta] steps from [contentId] (e.g. +1 = next, -1 = previous), or null if
     * there is no list or the channel is not in it.
     *
     * Wraps at both ends: the list is a ring, and stopping dead on the last channel reads as a
     * broken remote. Every live entry point (guide, search, sports) hands off to the same player,
     * so one D-pad press means the same thing however the viewer arrived.
     */
    fun relativeTo(contentId: String, delta: Int): LiveChannelRef? {
        val list = channels
        if (list.isEmpty()) return null
        val i = list.indexOfFirst { it.id == contentId }
        if (i < 0) return null
        val j = ((i + delta) % list.size + list.size) % list.size
        return list.getOrNull(j)
    }
}

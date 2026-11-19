package com.nuvio.tv.core.iptv.home

import com.nuvio.tv.core.iptv.content.EpgProgramme

/**
 * Pure decisions for the Home Recent Channels row: which recent channels make the row, and which programme counts
 * as "on now". Kept out of the I/O so it tests without a store, a database or the network.
 */
object HomeLiveRecentsPolicy {
    /** A row that longer than this stops being a shortcut and starts being a list. */
    const val MAX_CHANNELS = 12

    data class Candidate(val contentId: String, val name: String, val logo: String?)

    /** Newest first as given: drops nameless and repeated channels, then caps the row. */
    fun select(recentsNewestFirst: List<Candidate>, limit: Int = MAX_CHANNELS): List<Candidate> =
        recentsNewestFirst
            .filter { it.contentId.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.contentId }
            .take(limit.coerceAtLeast(0))

    /**
     * The programme airing at [nowMs] from stored guide rows. Uses timestamps, never a provider flag, and returns
     * null when nothing covers the moment (the card then shows the name alone, never a stale title).
     */
    fun nowTitle(programmes: List<EpgProgramme>, nowMs: Long): String? =
        programmes
            .filter { it.endMs > it.startMs && it.startMs <= nowMs && nowMs < it.endMs }
            .maxByOrNull { it.startMs }
            ?.title
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

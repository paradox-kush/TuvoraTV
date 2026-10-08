package com.nuvio.tv.ui.screens.iptv

/**
 * What an open live guide re-asks when new guide data lands (a playlist's XMLTV ingest swapping its
 * stored guide in, or the mirror committing programmes).
 *
 * The race this exists for (F4, reproduced 2026-10-08 on phone, iOS and Apple TV): a guide opened
 * while a playlist's XMLTV ingest was still downloading. The rows asked, the store rung answered
 * empty, and the rows kept "No information" after the ingest landed — on TV the empty verdict is
 * only a 60 s cooldown, but nothing told the open guide that data had arrived, so an idle guide
 * (or one the viewer had not moved focus in) stayed empty until they navigated away and back.
 *
 * Only the focused row and the prefetch window's rows that show nothing are re-asked: a handful of
 * store reads (the store rung is zero-network), never a refetch of a whole screen.
 */
internal object GuideDataRefreshPolicy {

    /**
     * Whether a commit concerns the playlist on screen. [committedPlaylistId] null = a whole-lineup
     * commit (the mirror), which can change any playlist's rows.
     */
    fun appliesTo(committedPlaylistId: String?, currentPlaylistId: String?): Boolean {
        if (currentPlaylistId == null) return false
        return committedPlaylistId == null || committedPlaylistId == currentPlaylistId
    }

    /**
     * Stream ids to ask again, focused row first then the prefetch window nearest-first. The focused
     * row is always re-asked (a manual guide pick changes data it already shows); the others only
     * when [hasProgramme] says they still show nothing. Non-positive ids are synthetic rows.
     */
    fun streamIdsToReask(
        focusedIndex: Int,
        streamIds: List<Int>,
        hasProgramme: (Int) -> Boolean,
    ): List<Int> {
        val window = GuideEpgPrefetchPolicy.indexesAround(focusedIndex, streamIds.size)
        return window.mapNotNull { index ->
            val id = streamIds[index]
            when {
                id <= 0 -> null
                index == focusedIndex -> id
                hasProgramme(id) -> null
                else -> id
            }
        }
    }
}

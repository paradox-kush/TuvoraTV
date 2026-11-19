package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.LoadStatus

/**
 * What the hub's "Nothing here yet" card shows. A slow, half-loaded or wrongly parsed playlist looks
 * the same as a truly empty one, so Retry is ALWAYS on offer (and takes focus); once a user-initiated
 * Retry has landed empty again the card says so, so the press does not look like it did nothing.
 * Pure: no I/O, no Compose.
 */
internal object HubEmptyStatePolicy {
    data class Presentation(val offersRetry: Boolean, val showCheckedJustNow: Boolean)

    fun forEmpty(userRetried: Boolean, load: LoadStatus): Presentation = Presentation(
        offersRetry = true,
        // Only a finished answer counts: still loading or failed is not "still empty".
        showCheckedJustNow = userRetried && (load is LoadStatus.Empty || load is LoadStatus.Loaded),
    )
}

package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.WatchProgress

/**
 * T1 (W2 device pass) — the display-only update a Continue Watching artwork/metadata hydration makes
 * to a progress entry that ALREADY exists. Pure.
 *
 * Hydration starts from a snapshot of the progress list and writes back seconds later, after a meta
 * fetch. It used to write with [WatchProgressPreferences.saveProgress] — an upsert of the WHOLE stale
 * entry. When the entry had been re-keyed in between (B64 moved every M3U item onto its login-free
 * id at start-up, while the home screen was already hydrating the old ids), that upsert brought the
 * old key back: every M3U item showed twice in Continue Watching and the extra card opened an empty
 * page. The same race resurrected an entry removed or replaced while its hydration was in flight.
 *
 * So hydration only PATCHES: it never creates an entry, and it only fills display fields the stored
 * entry lacks. Position, lastWatched, ids and sync attribution are always the stored entry's own.
 */
internal object ProgressDisplayPatch {

    /** The stored entry with [patch]'s display fields filled in where it has none; null = nothing to write. */
    fun apply(existing: WatchProgress?, patch: WatchProgress): WatchProgress? {
        existing ?: return null
        val merged = existing.copy(
            poster = existing.poster ?: patch.poster,
            backdrop = existing.backdrop ?: patch.backdrop,
            logo = existing.logo ?: patch.logo,
            name = existing.name.takeIf { it.isNotBlank() && it != existing.contentId }
                ?: patch.name.takeIf { it.isNotBlank() }
                ?: existing.name,
            duration = existing.duration.takeIf { it > 0 } ?: patch.duration,
        )
        return merged.takeIf { it != existing }
    }
}

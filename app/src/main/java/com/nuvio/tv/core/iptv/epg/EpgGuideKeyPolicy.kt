package com.nuvio.tv.core.iptv.epg

/**
 * Which stored guide row-set one channel reads (B10 + F14), first answer wins:
 *
 *  1. the user's manual pick (F14) — per PROFILE, synced as an overlay row, so it is applied here at
 *     read time rather than baked into the per-playlist map (profiles share one ingested guide);
 *  2. the ingest's automatic match (`epg_channel_map`: provider id, then cleaned name);
 *  3. the provider's raw id — the pre-B10 join, still right for a playlist ingested before the map
 *     existed (no census row yet) and harmless after (the map already holds every id hit).
 *
 * Pure: the lookups are passed in, so the precedence is unit-tested without a database.
 */
object EpgGuideKeyPolicy {

    fun resolve(
        manualGuideId: String?,
        keyForGuideId: (String) -> String?,
        mappedKey: String?,
        providerId: String?,
    ): String? {
        manualGuideId?.let { normalizeChannelId(it) }?.takeIf { it.isNotEmpty() }?.let(keyForGuideId)?.let { return it }
        mappedKey?.takeIf { it.isNotEmpty() }?.let { return it }
        return providerId?.let { normalizeChannelId(it) }?.takeIf { it.isNotEmpty() }
    }
}

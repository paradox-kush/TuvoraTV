package com.nuvio.tv.core.iptv.overlay

/**
 * Pure policy: collapse an overlay push batch so no two events share a `(kind, okey)` identity.
 *
 * The backend's `sync_push_iptv_overlay` upserts each `p_items` element with `ON CONFLICT(kind, okey)`.
 * If the same `(kind, okey)` appears twice in one batch, Postgres aborts the ENTIRE push with
 *
 *     ON CONFLICT DO UPDATE command cannot affect row a second time   (SQLSTATE 21000)
 *
 * so every edit in that batch is lost, not just the duplicate. This keeps, per `(kind, okey)`, the
 * single event with the greatest [OverlayPushRow.updatedAt] (the freshest edit); on a tie the later
 * element in the input wins. Surviving events keep first-seen input order so the batch stays stable.
 *
 * Pure (no Android / no I/O) so it unit-tests without the DB, the network, or the player. KMP twin:
 * the mobile/desktop IptvOverlayPushDedupPolicy.
 */
object IptvOverlayPushDedupPolicy {
    fun dedupe(events: List<OverlayPushRow>): List<OverlayPushRow> {
        if (events.size < 2) return events
        // Keyed by (kind, okey). LinkedHashMap keeps each identity at its first-seen position while the
        // value is replaced by any equal-or-fresher event, so ties keep the LAST occurrence and the
        // surviving order stays stable.
        val winners = LinkedHashMap<Pair<String, String>, OverlayPushRow>(events.size)
        for (event in events) {
            val key = event.kind to event.okey
            val existing = winners[key]
            if (existing == null || event.updatedAt >= existing.updatedAt) {
                winners[key] = event
            }
        }
        return if (winners.size == events.size) events else winners.values.toList()
    }
}

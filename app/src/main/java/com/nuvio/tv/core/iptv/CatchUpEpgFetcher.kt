package com.nuvio.tv.core.iptv

import kotlinx.coroutines.CompletableDeferred

/**
 * Gates the historical-EPG fetch: at most one in flight per channel, and no fetch at all while the
 * stored copy is fresh.
 *
 * `get_simple_data_table` returns a full week for ONE channel, so this is deliberately lazy and
 * deliberately not prefetched — a screenful of them is how 2 MB becomes 40 MB on a box with a
 * 192 MB heap, which is the same trade [GuideEpgPrefetchPolicy] already makes for now/next.
 *
 * The single-flight matters for a second reason: focus lands, the debounce fires, the viewer steps
 * into the timeline and the strip asks again — three asks before the first answer. Two of the
 * user's three real accounts are `max_connections=1`, and these panels rate-limit.
 *
 * Drive it from one dispatcher; the in-flight map is deliberately unsynchronized, like the walk's.
 */
class CatchUpEpgFetcher(
    private val fetchedAt: suspend (playlistId: String, channelId: String) -> Long?,
    private val refill: suspend (playlistId: String, channelId: String, catchUpDays: Int, nowMs: Long) -> Unit,
) {
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()

    /**
     * Makes sure [channelId]'s stored guide is fresh enough to browse, fetching only if the gate
     * opens. A concurrent ask for the same channel joins the fetch already running.
     *
     * A failure is not remembered: one transient panel error must not read as "this channel has no
     * history" for the rest of the session. The fetch stamp is written by the refill itself (even
     * for an empty answer), which is what stops us re-asking a channel the provider has no guide
     * for.
     */
    suspend fun ensure(playlistId: String, channelId: String, catchUpDays: Int, nowMs: Long) {
        val key = "$playlistId|$channelId"
        inFlight[key]?.let { return it.await() }
        if (!CatchUpEpgWindow.shouldFetch(fetchedAt(playlistId, channelId), nowMs)) return

        // Re-check: awaiting the stamp above is a suspension point, so another caller may have
        // started the fetch while this one was reading disk.
        inFlight[key]?.let { return it.await() }

        val gate = CompletableDeferred<Unit>()
        inFlight[key] = gate
        try {
            refill(playlistId, channelId, catchUpDays, nowMs)
        } finally {
            inFlight.remove(key)
            gate.complete(Unit)
        }
    }
}

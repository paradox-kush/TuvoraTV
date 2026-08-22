package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazy poster fill for Xtream panels whose bulk lists ship empty icons (measured: one real
 * provider returns 0/55,001 non-empty `stream_icon`s while `get_vod_info` carries full TMDB
 * artwork — the same trick TiviMate uses, except we persist the answer in the index so each
 * item is asked about once EVER, not once per focus).
 *
 * Semantics (research/iptv-catalog-loading.md §12):
 *  - [enqueue] puts a VISIBLE window at the front of the queue and appends prefetch windows
 *    behind pending work: what's on screen fills first, and the queue cap ages tails out.
 *  - Concurrency 2, same ceiling the Stalker session enforces — browse traffic stays polite.
 *  - An item is attempted at most once per process: a panel with no art for it stays a
 *    title-card, we don't re-ask on every visit.
 *  - Fetched URLs are written to the index row ([XtreamMatchIndex.updatePoster]) and announced
 *    on [updates] so visible cards can patch in place.
 *  - Three consecutive transport failures pause the drain for a minute — a panel outage must
 *    not turn the queue into a hammer.
 */
@Singleton
class PosterEnricher @Inject constructor(
    private val index: XtreamMatchIndex,
    private val clientFactory: IptvClientFactory,
) {
    data class PosterUpdate(val accountId: String, val kind: MatchKind, val sid: Int, val poster: String)

    private data class Request(val acc: XtreamAccount, val kind: MatchKind, val sid: Int) {
        val key: String get() = "${acc.id}|${kind.slug}|$sid"
    }

    private val _updates = MutableSharedFlow<PosterUpdate>(extraBufferCapacity = 256)
    val updates: SharedFlow<PosterUpdate> = _updates

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val queue = LinkedHashMap<String, Request>()
    private val attempted = HashSet<String>()
    private var workers = 0
    private var transportFailures = 0

    /**
     * Removes every queued entry that does NOT belong to [keepAccountId]; returns how many went.
     *
     * Pure and generic so the rule is testable without a portal: [accountOf] maps a queue value to
     * its account id. See [onProviderSwitched] for why this exists.
     */
    internal fun <V> dropQueuedFor(
        queue: MutableMap<String, V>,
        keepAccountId: String,
        accountOf: (V) -> String,
    ): Int {
        val doomed = queue.entries.filter { accountOf(it.value) != keepAccountId }.map { it.key }
        doomed.forEach { queue.remove(it) }
        return doomed.size
    }

    /**
     * The hub switched to [accountId]: abandon every queued poster fetch for other providers.
     *
     * Measured on a phone (2026-08-16): 30s after switching away from a Stalker portal the
     * enricher was still issuing 87 requests to it with 202 queued at ~700ms each — about two
     * minutes of work for a screen nobody was on, competing with the NEW playlist for the same
     * rate-limited host. The Stalker switch-abandon could not catch these: the enricher mints each
     * request fresh AFTER the switch, so every one looks current. The queue itself is scoped here.
     *
     * Deliberately NOT clearing [attempted]: a dropped item was never asked, and its key is only
     * added when a worker actually picks it up — so a return visit re-queues it normally.
     */
    fun onProviderSwitched(accountId: String) {
        synchronized(lock) { dropQueuedFor(queue, accountId) { it.acc.id } }
    }

    /**
     * Queues the null-poster sids of a just-served window. A window the user can SEE
     * ([prioritize]=true: row compose, loadMore) goes AHEAD of everything pending; a
     * prefetch window appends behind — during a section load half a dozen off-screen
     * categories serve in one burst, and letting them jump the queue (or push visible
     * rows past [QUEUE_CAP]) froze the on-screen fill (observed on-device). The cap
     * ages abandoned tails out; prefetch work simply doesn't queue while it's full.
     */
    fun enqueue(acc: XtreamAccount, kind: MatchKind, sids: List<Int>, prioritize: Boolean = true) {
        if (kind == MatchKind.LIVE) return // no per-item artwork endpoint exists for live
        synchronized(lock) {
            if (prioritize) {
                val pending = queue.values.toList()
                queue.clear()
                for (sid in sids) {
                    val req = Request(acc, kind, sid)
                    if (req.key !in attempted) queue[req.key] = req
                }
                for (req in pending) {
                    if (queue.size >= QUEUE_CAP) break
                    if (req.key !in queue) queue[req.key] = req
                }
            } else {
                for (sid in sids) {
                    if (queue.size >= QUEUE_CAP) break
                    val req = Request(acc, kind, sid)
                    if (req.key !in attempted && req.key !in queue) queue[req.key] = req
                }
            }
            repeat(CONCURRENCY - workers) {
                workers++
                scope.launch { drain() }
            }
        }
    }

    /**
     * Test-only: the pending queue's sids in drain order (front first). Lets the priority test
     * assert the reorder directly, under the lock, instead of inferring it from post-gate drain
     * scheduling — which depends on how fast the workers resume and was flaky on slow CI.
     */
    internal fun pendingSids(): List<Int> = synchronized(lock) { queue.values.map { it.sid } }

    private suspend fun drain() {
        while (true) {
            val req = synchronized(lock) {
                val iterator = queue.entries.iterator()
                if (!iterator.hasNext()) {
                    workers--
                    return
                }
                // Keep this twin safe for Kotlin/Native ports: a HashMap.Entry is invalid as
                // soon as its backing entry is removed. Copy first, then remove via the iterator.
                val request = iterator.next().value
                iterator.remove()
                attempted.add(request.key)
                request
            }
            val client = clientFactory.clientFor(req.acc) as? XtreamClient ?: continue
            val result = when (req.kind) {
                MatchKind.MOVIE -> client.vodArtwork(req.acc, req.sid)
                MatchKind.SERIES -> client.seriesArtwork(req.acc, req.sid)
                MatchKind.LIVE -> continue
            }
            result.fold(
                onSuccess = { url ->
                    synchronized(lock) { transportFailures = 0 }
                    if (url != null) {
                        index.updatePoster(req.acc.id, req.kind, req.sid, url)
                        _updates.tryEmit(PosterUpdate(req.acc.id, req.kind, req.sid, url))
                    }
                },
                onFailure = {
                    // Transport failure is NOT "the panel has no art": un-mark the item so a
                    // later window serve can retry it. Only a successful answer (with or
                    // without artwork) is permanent. The pause still guards against a panel
                    // outage turning the queue into a hammer; counter is approximate across
                    // workers by design.
                    val pause = synchronized(lock) {
                        attempted.remove(req.key)
                        ++transportFailures >= FAILURE_PAUSE_THRESHOLD
                    }
                    if (pause) {
                        delay(FAILURE_PAUSE_MS)
                        synchronized(lock) { transportFailures = 0 }
                    }
                }
            )
        }
    }

    // internal, not private: the priority test gates on CONCURRENCY to know when every worker is
    // busy. Hardcoding a 2 there would keep compiling but stop testing anything if this changed.
    internal companion object {
        const val CONCURRENCY = 2
        /** ~5 windows of pending backlog; beyond this the oldest abandoned rows age out. */
        const val QUEUE_CAP = 2_000
        const val FAILURE_PAUSE_THRESHOLD = 3
        const val FAILURE_PAUSE_MS = 60_000L
    }
}

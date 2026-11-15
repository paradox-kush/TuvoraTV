package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.GameDetailPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * One shared poller per game (multiview shows several games, each possibly in several tiles), tied to COLLECTION:
 *
 *  - [feed] acquires when a collector starts and releases when it stops - completion, cancellation or an exception, in a `finally` -
 *    so a cancelled consumer can never leak the poller. There is no handle a caller must remember to release.
 *  - The FIRST collector of a game starts ONE [GameDetailPoller]; further collectors share it (one set of requests whatever the count).
 *  - The LAST collector leaving PAUSES the game: the poller is cancelled (no request follows), but the entry keeps its source, its
 *    poller (with its last-fetch times) and its last snapshot. Collecting again within [PAUSE_KEEP_MS] resumes from them: the snapshot
 *    replays at once and the next request waits for its slot - no summary + whole-day scoreboard refetch. A game left paused longer
 *    than that is dropped, and its next collector starts fresh with a full tick.
 *  - A late collector gets the latest snapshot immediately (replay 1). A final / postponed / far-off game stops ITSELF after its
 *    first load and the flow holds the last value.
 *  - [retry] is user / event driven (a Retry button, never a timer): one full tick now, then the normal cadence resumes. A source
 *    that throws ends that run with `Failed` and the poll stops (no hot loop) until a collector returns or [retry] is called.
 *
 * Cadence, backoff and idle handling are the poller's ([GameDetailRefreshPolicy.plan]); the only repeating work is the poller's.
 */
class GameFeedHub(
    private val scope: CoroutineScope,
    private val open: (GameRef) -> GameDetailSource,
    private val config: () -> GameDetailRefreshPolicy.Config,
    private val clock: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    constructor(scope: CoroutineScope, service: SportsDetailService, clock: () -> Long = System::currentTimeMillis) :
        this(scope, service::open, service::refreshConfig, clock)

    private class Entry(val poller: GameDetailPoller, val shared: MutableSharedFlow<DetailLoad>) {
        var refs = 0
        var job: Job? = null
        var pausedAt: Long? = null
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    /** The feed of [ref]: latest snapshot first (replay 1), then every tick. Collecting acquires, ending the collection releases. */
    fun feed(ref: GameRef): Flow<DetailLoad> = flow {
        val entry = retain(ref)
        try {
            emitAll(entry.shared)
        } finally {
            release(ref.gameId, entry)
        }
    }

    /** User-driven Retry: one full tick now (backoff reset), then the normal cadence. No effect on a game nobody collects. */
    fun retry(gameId: String) {
        synchronized(lock) {
            val entry = entries[gameId]?.takeIf { it.refs > 0 } ?: return
            entry.job?.cancel()
            entry.job = launchRun(entry, retryFirst = true)
        }
    }

    private fun retain(ref: GameRef): Entry = synchronized(lock) {
        evictStale()
        val entry = entries.getOrPut(ref.gameId) {
            val shared = MutableSharedFlow<DetailLoad>(replay = 1, extraBufferCapacity = 0, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val poller = GameDetailPoller(
                source = open(ref), startsAtMs = ref.startsAtMs, initialState = ref.seedState, config = config(), clock = clock,
                delayFn = delayFn, wantsUpcomingSummary = ref.sport.equals("Soccer", ignoreCase = true),
            )
            Entry(poller, shared)
        }
        entry.refs++
        entry.pausedAt = null
        if (entry.refs == 1) entry.job = launchRun(entry, retryFirst = false)
        entry
    }

    private fun launchRun(entry: Entry, retryFirst: Boolean): Job = scope.launch {
        try {
            if (retryFirst) entry.poller.retry { entry.shared.tryEmit(it) }
            entry.poller.run { entry.shared.tryEmit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A source must never throw; if one does the feed shows Failed and the run ends (no hot retry loop). A returning collector
            // or retry() starts the next run.
            entry.shared.tryEmit(DetailLoad.Failed())
        }
    }

    private fun release(gameId: String, entry: Entry) {
        val job = synchronized(lock) {
            entry.refs--
            if (entry.refs > 0) return
            entry.pausedAt = clock()
            entry.job.also { entry.job = null }
        }
        job?.cancel()
    }

    private fun evictStale() {
        val now = clock()
        entries.entries.removeAll { (_, e) -> e.refs == 0 && e.pausedAt?.let { now - it > PAUSE_KEEP_MS } == true }
    }

    /** Number of games currently polled (at least one collector). */
    fun activeGames(): Int = synchronized(lock) { entries.values.count { it.refs > 0 } }

    /** Games kept for a quick resume (paused, snapshot and fetch times held). */
    fun pausedGames(): Int = synchronized(lock) { entries.values.count { it.refs == 0 } }

    companion object {
        /** How long a game nobody collects keeps its snapshot and fetch times for a no-refetch resume. */
        const val PAUSE_KEEP_MS = 10 * 60_000L
    }
}

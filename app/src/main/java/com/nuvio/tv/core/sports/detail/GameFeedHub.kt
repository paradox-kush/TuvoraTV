package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.GameDetailPoller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** A consumer's hold on one game's shared feed. [release] is idempotent; after it the [feed] completes. */
interface GameFeedHandle {
    /** Latest snapshot first (replay 1), then every tick. Completes once this handle is released. */
    val feed: Flow<DetailLoad>

    fun release()
}

/**
 * One shared, ref-counted poller per game (multiview shows several games, each possibly in several tiles).
 *
 *  - The FIRST [acquire] of a game starts ONE [GameDetailPoller] on a fresh [GameDetailSource]; further acquires share it
 *    (one set of requests, whatever the consumer count).
 *  - The LAST [GameFeedHandle.release] cancels the poller: no request is issued afterwards. Acquiring again starts a new
 *    poller, whose first tick is a full one.
 *  - A late subscriber gets the latest snapshot immediately (replay 1).
 *  - Cadence, backoff and idle handling are the poller's ([GameDetailRefreshPolicy.plan]). A final / postponed / far-off game
 *    stops ITSELF after its first load: no more requests, and the flow HOLDS the last value (it does not complete) until the
 *    last consumer releases.
 *
 * Lifecycle: the caller owns [scope] (a screen / view-model scope) and calls [GameFeedHandle.release] when its screen stops
 * being RESUMED (`repeatOnLifecycle(RESUMED) { val h = hub.acquire(ref); try { h.feed.collect(...) } finally { h.release() } }`).
 * There is no loop here: the only repeating work is the poller's, and cancelling [scope] cancels it.
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

    private class Entry(val shared: MutableSharedFlow<DetailLoad>) {
        var refs = 0
        var job: Job? = null
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    fun acquire(ref: GameRef): GameFeedHandle {
        val entry: Entry
        val start: Boolean
        synchronized(lock) {
            val existing = entries[ref.gameId]
            entry = existing ?: Entry(MutableSharedFlow(replay = 1, extraBufferCapacity = 0, onBufferOverflow = BufferOverflow.DROP_OLDEST)).also { entries[ref.gameId] = it }
            entry.refs++
            start = existing == null
            if (start) entry.job = launchPoller(ref, entry)
        }
        return Handle(ref.gameId, entry)
    }

    private fun launchPoller(ref: GameRef, entry: Entry): Job = scope.launch {
        val poller = GameDetailPoller(
            source = open(ref), startsAtMs = ref.startsAtMs, initialState = ref.seedState, config = config(), clock = clock,
            delayFn = delayFn, wantsUpcomingSummary = ref.sport.equals("Soccer", ignoreCase = true),
        )
        try {
            poller.run { entry.shared.tryEmit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A source must never throw; if one does, the screen keeps its last value and the poll ends (no hot retry loop).
            entry.shared.tryEmit(DetailLoad.Failed())
        }
    }

    private fun release(gameId: String, entry: Entry) {
        val job = synchronized(lock) {
            entry.refs--
            if (entry.refs > 0) return
            if (entries[gameId] === entry) entries.remove(gameId)
            entry.job
        }
        job?.cancel()
    }

    /** Number of games currently polled (tests, diagnostics). */
    fun activeGames(): Int = synchronized(lock) { entries.size }

    private inner class Handle(private val gameId: String, private val entry: Entry) : GameFeedHandle {
        private val released = AtomicBoolean(false)
        private val done = CompletableDeferred<Unit>()
        val shared: SharedFlow<DetailLoad> = entry.shared

        override val feed: Flow<DetailLoad> = channelFlow {
            val collector = launch { shared.collect { send(it) } }
            done.await()
            collector.cancel()
        }

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            done.complete(Unit)
            release(gameId, entry)
        }
    }
}

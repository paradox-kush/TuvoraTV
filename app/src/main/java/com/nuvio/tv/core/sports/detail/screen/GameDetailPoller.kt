package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.DetailLoad
import com.nuvio.tv.core.sports.detail.GameDetailRefreshPolicy
import com.nuvio.tv.core.sports.detail.GameDetailSource
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.plan
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The polling loop of one open game screen, decided by [GameDetailRefreshPolicy.plan]. The CALLER runs
 * [run] inside `repeatOnLifecycle(RESUMED)`, so cancellation on pause stops every request; the loop
 * remembers its last-fetch times, so resuming waits for the next slot instead of refetching. It returns
 * on its own when the game is idle (final / postponed / far-off): zero requests, no wake-up.
 */
class GameDetailPoller(
    private val source: GameDetailSource,
    private val startsAtMs: Long,
    initialState: GameState,
    private val config: GameDetailRefreshPolicy.Config,
    private val clock: () -> Long,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    @Volatile private var state = initialState
    @Volatile private var lastEvent: Long? = null
    @Volatile private var lastSummary: Long? = null
    @Volatile private var failures = 0
    @Volatile private var loadedOnce = false
    /** The polling loop and a user Retry may race; ticks are serialised so they never overlap. */
    private val tickLock = Mutex()

    suspend fun run(onLoad: (DetailLoad) -> Unit) {
        if (!loadedOnce) tick(fetchEvent = true, fetchSummary = true, onLoad)
        while (true) {
            val p = GameDetailRefreshPolicy.plan(state, startsAtMs, clock(), lastEvent, lastSummary, failures, config)
            if (p.fetchEvent || p.fetchSummary) {
                tick(p.fetchEvent, p.fetchSummary, onLoad)
                continue
            }
            delayFn(p.nextCheckInMs ?: return)
        }
    }

    /** User pressed Retry: a full tick now, regardless of the schedule. */
    suspend fun retry(onLoad: (DetailLoad) -> Unit) {
        failures = 0
        tick(fetchEvent = true, fetchSummary = true, onLoad)
    }

    private suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean, onLoad: (DetailLoad) -> Unit) = tickLock.withLock {
        val startedAt = clock()
        // Bookkeeping happens only AFTER the tick completes: a pause (cancellation) mid first load must leave
        // the poller "never loaded", so the next RESUMED run starts the full tick again.
        val load = source.tick(fetchEvent, fetchSummary)
        if (fetchEvent) lastEvent = startedAt
        if (fetchSummary) lastSummary = startedAt
        loadedOnce = true
        when (load) {
            is DetailLoad.Loaded -> { failures = 0; state = load.detail.state }
            is DetailLoad.Failed -> failures++
            DetailLoad.Empty -> Unit
        }
        onLoad(load)
    }
}

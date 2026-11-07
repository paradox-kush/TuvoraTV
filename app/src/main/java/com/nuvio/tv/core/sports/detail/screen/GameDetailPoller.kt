package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.DetailLoad
import com.nuvio.tv.core.sports.detail.GameDetailRefreshPolicy
import com.nuvio.tv.core.sports.detail.GameDetailSource
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.plan
import kotlinx.coroutines.delay

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
    private var state = initialState
    private var lastEvent: Long? = null
    private var lastSummary: Long? = null
    private var failures = 0
    private var loadedOnce = false

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

    private suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean, onLoad: (DetailLoad) -> Unit) {
        val now = clock()
        if (fetchEvent) lastEvent = now
        if (fetchSummary) lastSummary = now
        loadedOnce = true
        val load = source.tick(fetchEvent, fetchSummary)
        when (load) {
            is DetailLoad.Loaded -> { failures = 0; state = load.detail.state }
            is DetailLoad.Failed -> failures++
            DetailLoad.Empty -> Unit
        }
        onLoad(load)
    }
}

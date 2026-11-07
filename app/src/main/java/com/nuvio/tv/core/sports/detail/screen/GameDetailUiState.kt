package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.Availability
import com.nuvio.tv.core.sports.detail.DetailLoad
import com.nuvio.tv.core.sports.detail.GameDetail

/**
 * What the game screen shows. Every load ends: Loaded, NoData (fall back to the existing match sheet)
 * or Failed (Retry). A failure is never NoData, and the Loading state carries its own deadline so the
 * screen ends the wait even if the work never returns (BoundedLoad rule).
 */
sealed interface GameDetailUiState {
    data class Loading(val startedAtMs: Long, val deadlineAtMs: Long) : GameDetailUiState
    data class Loaded(val detail: GameDetail, val stale: Boolean = false) : GameDetailUiState
    data class Failed(val timedOut: Boolean) : GameDetailUiState
    /** Sources answered with nothing usable: show the existing channel match sheet instead. */
    data object NoData : GameDetailUiState
}

object GameDetailUiReducer {
    const val LOAD_DEADLINE_MS = 12_000L

    fun initial(nowMs: Long): GameDetailUiState = GameDetailUiState.Loading(nowMs, nowMs + LOAD_DEADLINE_MS)

    fun retry(nowMs: Long): GameDetailUiState = initial(nowMs)

    fun onLoad(prev: GameDetailUiState, load: DetailLoad, nowMs: Long): GameDetailUiState {
        val shown = prev as? GameDetailUiState.Loaded
        return when (load) {
            is DetailLoad.Loaded ->
                if (load.detail.blocks.isEmpty() && load.detail.availability != Availability.FULL) shown ?: GameDetailUiState.NoData
                else GameDetailUiState.Loaded(load.detail)
            DetailLoad.Empty -> shown ?: GameDetailUiState.NoData
            is DetailLoad.Failed -> shown?.copy(stale = true) ?: GameDetailUiState.Failed(load.timedOut)
        }
    }

    fun onDeadline(prev: GameDetailUiState, nowMs: Long): GameDetailUiState =
        if (prev is GameDetailUiState.Loading && nowMs >= prev.deadlineAtMs) GameDetailUiState.Failed(timedOut = true) else prev
}

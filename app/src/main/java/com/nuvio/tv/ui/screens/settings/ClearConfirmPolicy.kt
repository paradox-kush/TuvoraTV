package com.nuvio.tv.ui.screens.settings

/**
 * Inline two-step confirm for a destructive settings row ("Clear play counts", F-8 / UX-35). Same behaviour as the
 * phone's `PinSuggestionSettingsSection`: the first press arms the row, a second press clears; the armed state
 * reverts after [CONFIRM_WINDOW_MS], and on TV also when focus leaves the row. "Cleared" is shown for
 * [CLEARED_SHOWN_MS]. A press that lands within [DOUBLE_FIRE_GUARD_MS] of arming (a repeated OK) is ignored, so a
 * single OK can never arm and clear in one go. Pure, so it tests without Compose.
 */
internal object ClearConfirmPolicy {
    const val CONFIRM_WINDOW_MS = 5_000L
    const val CLEARED_SHOWN_MS = 3_000L
    const val DOUBLE_FIRE_GUARD_MS = 400L

    enum class Phase { Idle, Armed, Cleared }

    data class State(val phase: Phase = Phase.Idle, val armedAtMs: Long = 0L)

    data class Result(val state: State, val clear: Boolean)

    fun onPress(state: State, nowMs: Long): Result = when (state.phase) {
        Phase.Armed ->
            if (nowMs - state.armedAtMs < DOUBLE_FIRE_GUARD_MS) Result(state, clear = false)
            else Result(State(Phase.Cleared), clear = true)
        Phase.Idle, Phase.Cleared -> Result(State(Phase.Armed, armedAtMs = nowMs), clear = false)
    }

    fun onTimeout(state: State): State = State()

    fun onFocusLost(state: State): State =
        if (state.phase == Phase.Armed) State() else state
}

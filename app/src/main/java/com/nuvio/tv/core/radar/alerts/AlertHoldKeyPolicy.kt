package com.nuvio.tv.core.radar.alerts

/**
 * Hold-OK-to-act while an alert banner is visible. The banner is never focusable, so the action rides on the
 * remote's OK key at activity key-dispatch level. This object is the whole decision, pure so it tests without a
 * device:
 *
 *  - A SHORT press passes through untouched (the focused control / player sees the OK exactly as without a banner).
 *  - A HOLD (>= [HOLD_MS] since the first down) fires the action ONCE and swallows the rest of that press (repeat
 *    downs and the up), so the held OK can never also "click" whatever has focus.
 *  - Repeat downs of a tracked press are swallowed from the start (a held OK must not repeat a click either); the
 *    first down is the only one the app ever sees.
 *  - A press that began before the banner appeared is not tracked and is never touched.
 *
 * Android only sends key repeats on some remotes, so the caller also arms a one-shot timer for [HOLD_MS] after the
 * first down and feeds it to [onTimer].
 */
object AlertHoldKeyPolicy {
    const val HOLD_MS = 500L

    // KeyEvent.KEYCODE_DPAD_CENTER / KEYCODE_ENTER / KEYCODE_NUMPAD_ENTER (literals keep this free of android.*).
    private const val KEYCODE_DPAD_CENTER = 23
    private const val KEYCODE_ENTER = 66
    private const val KEYCODE_NUMPAD_ENTER = 160

    enum class Phase { Down, Repeat, Up }

    /** [downAtMs] = first down of the tracked press (null = no tracked press); [fired] = the action already ran. */
    data class State(val downAtMs: Long? = null, val fired: Boolean = false)

    /** [consume] = swallow this key event; [fire] = run the banner's action now. */
    data class Step(val state: State, val consume: Boolean, val fire: Boolean)

    fun isOkKey(keyCode: Int): Boolean =
        keyCode == KEYCODE_DPAD_CENTER || keyCode == KEYCODE_ENTER || keyCode == KEYCODE_NUMPAD_ENTER

    fun onKey(state: State, bannerVisible: Boolean, isOk: Boolean, phase: Phase, nowMs: Long): Step {
        if (!isOk) return Step(state, consume = false, fire = false)
        return when (phase) {
            Phase.Down ->
                if (bannerVisible) Step(State(downAtMs = nowMs), consume = false, fire = false)
                else Step(State(), consume = false, fire = false)
            Phase.Repeat -> when {
                state.fired -> Step(state, consume = true, fire = false)
                state.downAtMs == null -> Step(state, consume = false, fire = false)
                bannerVisible && nowMs - state.downAtMs >= HOLD_MS ->
                    Step(State(state.downAtMs, fired = true), consume = true, fire = true)
                else -> Step(state, consume = true, fire = false)
            }
            Phase.Up -> Step(State(), consume = state.fired, fire = false)
        }
    }

    /** The one-shot timer armed at the first down: fires when the press is still held and the banner still up. */
    fun onTimer(state: State, bannerVisible: Boolean, nowMs: Long): Step =
        if (state.downAtMs != null && !state.fired && bannerVisible && nowMs - state.downAtMs >= HOLD_MS) {
            Step(State(state.downAtMs, fired = true), consume = false, fire = true)
        } else Step(state, consume = false, fire = false)
}

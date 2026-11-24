package com.nuvio.tv.core.radar.alerts

/**
 * Hold-OK-to-act while an alert banner is visible. The banner is never focusable, so the action rides on the
 * remote's OK key at activity key-dispatch level. This object is the whole decision, pure so it tests without a
 * device (review round 1, M1):
 *
 *  - While a banner is visible the OK DOWN is consumed (the app never sees a press that might be a hold).
 *  - A SHORT press (up before [HOLD_MS]) is replayed as down + up through the activity's normal dispatch, so the
 *    focused control / player sees exactly the press it would have seen without a banner.
 *  - A HOLD (>= [HOLD_MS] since the down) fires the action ONCE and swallows the rest of that press (repeats and up).
 *  - The banner going away mid-press replays the down (the press is then the app's again).
 *  - A press that began before the banner appeared is not tracked and is never touched.
 *
 * Android only sends key repeats on some remotes, so the caller also arms a one-shot timer for [HOLD_MS] after the
 * down and feeds it to [onTimer].
 */
object AlertHoldKeyPolicy {
    const val HOLD_MS = 500L

    // KeyEvent.KEYCODE_DPAD_CENTER / KEYCODE_ENTER / KEYCODE_NUMPAD_ENTER (literals keep this free of android.*).
    private const val KEYCODE_DPAD_CENTER = 23
    private const val KEYCODE_ENTER = 66
    private const val KEYCODE_NUMPAD_ENTER = 160

    enum class Phase { Down, Repeat, Up }

    /** What the caller must re-deliver to the app: nothing, the swallowed down, or the swallowed down and then this up. */
    enum class Replay { None, Down, DownUp }

    /** [downAtMs] = down of the tracked press (null = no tracked press); [fired] = the action already ran. */
    data class State(val downAtMs: Long? = null, val fired: Boolean = false)

    /** [consume] = swallow this key event; [fire] = run the banner's action now; [replay] = re-deliver the swallowed key(s). */
    data class Step(val state: State, val consume: Boolean, val fire: Boolean, val replay: Replay = Replay.None)

    fun isOkKey(keyCode: Int): Boolean =
        keyCode == KEYCODE_DPAD_CENTER || keyCode == KEYCODE_ENTER || keyCode == KEYCODE_NUMPAD_ENTER

    fun onKey(state: State, bannerVisible: Boolean, isOk: Boolean, phase: Phase, nowMs: Long): Step {
        if (!isOk) return Step(state, consume = false, fire = false)
        return when (phase) {
            Phase.Down ->
                if (bannerVisible) Step(State(downAtMs = nowMs), consume = true, fire = false)
                else Step(State(), consume = false, fire = false)
            Phase.Repeat -> when {
                state.fired -> Step(state, consume = true, fire = false)
                state.downAtMs == null -> Step(state, consume = false, fire = false)
                !bannerVisible -> Step(State(), consume = false, fire = false, replay = Replay.Down)
                nowMs - state.downAtMs >= HOLD_MS -> Step(State(state.downAtMs, fired = true), consume = true, fire = true)
                else -> Step(state, consume = true, fire = false)
            }
            Phase.Up -> when {
                state.fired -> Step(State(), consume = true, fire = false)
                state.downAtMs == null -> Step(State(), consume = false, fire = false)
                // Released before the hold: it was a short press all along.
                else -> Step(State(), consume = true, fire = false, replay = Replay.DownUp)
            }
        }
    }

    /** The one-shot timer armed at the down: fires when the press is still held and the banner still up. */
    fun onTimer(state: State, bannerVisible: Boolean, nowMs: Long): Step = when {
        state.downAtMs == null || state.fired -> Step(state, consume = false, fire = false)
        !bannerVisible -> onBannerGone(state)
        nowMs - state.downAtMs >= HOLD_MS -> Step(State(state.downAtMs, fired = true), consume = false, fire = true)
        else -> Step(state, consume = false, fire = false)
    }

    /** The banner disappeared: a press still being tracked (not yet a hold) gets its down back. */
    fun onBannerGone(state: State): Step =
        if (state.downAtMs != null && !state.fired) Step(State(), consume = false, fire = false, replay = Replay.Down)
        else Step(state, consume = false, fire = false)

    /** The activity paused: the up of a press in flight is lost for good, so the press is forgotten (never fires later). */
    fun onPause(state: State): Step = Step(State(), consume = false, fire = false)
}

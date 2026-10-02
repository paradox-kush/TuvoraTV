package com.nuvio.tv.core.iptv

/**
 * Step 2 — destructive actions on TV (Detach, Remove playlist) confirm by HOLDING OK, not by typing a
 * word (typing with a remote costs ~15 presses). Cancel is focused first; a quick press or an early
 * release does nothing; the destructive button shows a filling ring while OK is held.
 */
class HoldToConfirmPolicy(private val holdMs: Long = DEFAULT_HOLD_MS) {
    /** 0.0 .. 1.0, the fill of the ring after [heldMs] of continuous hold. */
    fun progress(heldMs: Long): Float = (heldMs.coerceAtLeast(0L).toFloat() / holdMs).coerceIn(0f, 1f)

    fun isConfirmed(heldMs: Long): Boolean = heldMs >= holdMs

    enum class Hint { HOLD, TOO_SHORT }

    /** After OK was released: a press that did not last long enough is explained, not silently ignored. */
    fun hintAfterRelease(heldMs: Long): Hint = if (heldMs > 0 && !isConfirmed(heldMs)) Hint.TOO_SHORT else Hint.HOLD

    companion object {
        const val DEFAULT_HOLD_MS = 2_000L
    }
}

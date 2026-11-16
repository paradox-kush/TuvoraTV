package com.nuvio.tv.ui.screens.iptv

/**
 * Change 7 + B1 + B2 of the IPTV redesign (2026-11-15, 02 / 05): number keys jump to a channel with the match shown
 * as you type (exact number, else the first number that starts with what was typed), committing after
 * [COMMIT_MS] or on OK; "0" alone is the previous channel; CH+ / CH- page the guide. Channel numbers are the
 * numbers the guide prints (the row's position in the list on screen). Pure.
 */
internal object NumberEntryPolicy {
    const val MAX_DIGITS = 4

    /** Each digit restarts this countdown (05: "Up to 4. Each digit restarts the 1.5 s countdown"). */
    const val COMMIT_MS = 1_500L

    /** The buffer after [digit] (0-9). A fifth digit starts over with it, so a held finger cannot overflow. */
    fun append(buffer: String, digit: Int): String {
        require(digit in 0..9)
        val next = buffer + digit
        return if (next.length > MAX_DIGITS) digit.toString() else next
    }

    /** The row index [buffer] lands on among [count] numbered rows (1..count), or null for "No channel N". */
    fun match(buffer: String, count: Int): Int? {
        val typed = buffer.toIntOrNull() ?: return null
        if (typed in 1..count) return typed - 1
        // No exact number: the first number that starts with what was typed (typing "1" in a 9-row list is row 1).
        return (1..count).firstOrNull { it.toString().startsWith(buffer) }?.minus(1)
    }

    /** What a committed number does. The two misses say so on screen (sim pass 2026-11-16, UX: "999" did nothing). */
    sealed interface Commit {
        data class Jump(val index: Int) : Commit
        data object Previous : Commit
        data class NoChannel(val typed: String) : Commit
        data object NoPrevious : Commit
    }

    /** [buffer] committed over [count] numbered rows; [hasPrevious] = a channel watched before is in this lineup. */
    fun commit(buffer: String, count: Int, hasPrevious: Boolean): Commit {
        if (isPreviousChannel(buffer)) return if (hasPrevious) Commit.Previous else Commit.NoPrevious
        return match(buffer, count)?.let { Commit.Jump(it) } ?: Commit.NoChannel(buffer)
    }

    /** "0" alone (committed) is the previous channel, not channel 0. */
    fun isPreviousChannel(buffer: String): Boolean = buffer == "0"

    /** CH+ / CH- in the guide: one screen of rows, clamped to the list (never wraps). */
    fun pageTarget(current: Int, visibleRows: Int, count: Int, down: Boolean): Int {
        if (count <= 0) return 0
        val step = visibleRows.coerceAtLeast(1)
        return (if (down) current + step else current - step).coerceIn(0, count - 1)
    }

    /** Device pass 2, D2: the row "0" goes back to, so the readout can name it ("Back to 12 · Name"); null = none. */
    fun previousTarget(targetId: String?, rowIds: List<String>): Int? =
        targetId?.let { rowIds.indexOf(it) }?.takeIf { it >= 0 }
}

package com.nuvio.tv.ui.screens.iptv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A category under D-pad focus becomes the shown category only once focus rests on it.
 *
 * Selecting on every focus move made scrolling the category list the slowest thing in the guide:
 * each row passed cancelled a channel load, emptied the pane, loaded and composed a new channel
 * list and primed its EPG, all for a category the viewer was only passing. On the Onn 4K box that
 * was a 37 ms median / 105 ms p90 frame, against 19 ms for the channel list beside it.
 */
object GuideCategorySettlePolicy {
    /**
     * Longer than a held key's repeat ([GuideKeyRepeatPolicy.VERTICAL_GATE_MS]) so a held key
     * never switches mid-travel; short enough that stopping on a category still feels immediate.
     */
    const val SETTLE_MS = 200L

    /**
     * B254: Search with no query has nothing to show, so resting on it (or passing it on the way up
     * to the tabs) must not blank the guide. It still opens the dialog on OK, and a submitted query
     * selects it directly; only the focus-alone path is refused.
     */
    fun selectsOnFocus(focusedId: String?, searchQuery: String): Boolean =
        focusedId != XtreamLiveGuideViewModel.SEARCH_ID || searchQuery.isNotBlank()


    /** How long to wait before showing [focusedId]. The category already shown never waits. */
    fun delayMs(focusedId: String?, selectedId: String?): Long =
        if (focusedId == selectedId) 0L else SETTLE_MS
}

/**
 * Holds the one pending category switch. Not thread-safe: focus events and [scope] are both on
 * the main thread.
 */
class GuideCategorySettler(
    private val scope: CoroutineScope,
    private val select: (String?) -> Unit,
) {
    private var job: Job? = null
    private var pendingId: String? = null
    private var hasPending = false

    /** [focusedId] took focus while [selectedId] is the category on screen. [searchQuery] is the current search text. */
    fun focus(focusedId: String?, selectedId: String?, searchQuery: String = "") {
        cancel()
        if (!GuideCategorySettlePolicy.selectsOnFocus(focusedId, searchQuery)) return
        val wait = GuideCategorySettlePolicy.delayMs(focusedId, selectedId)
        if (wait <= 0L) {
            select(focusedId)
            return
        }
        pendingId = focusedId
        hasPending = true
        job = scope.launch {
            delay(wait)
            hasPending = false
            job = null
            select(focusedId)
        }
    }

    /** The viewer is entering the focused category (RIGHT / OK): show it now, not after the wait. */
    fun commitNow() {
        if (!hasPending) return
        val id = pendingId
        cancel()
        select(id)
    }

    fun cancel() {
        job?.cancel()
        job = null
        hasPending = false
        pendingId = null
    }
}

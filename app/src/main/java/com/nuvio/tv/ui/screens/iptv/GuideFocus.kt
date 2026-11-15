package com.nuvio.tv.ui.screens.iptv

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester

/**
 * Requests focus and says whether it landed.
 *
 * `FocusRequester.requestFocus()` does NOT throw when no node is attached — current Compose UI
 * (verified in the ui-android 1.11.2 and 1.12.0 bytecode) prints a "FocusRequester is not
 * initialized" warning and returns. So a `runCatching { requestFocus() }.isFailure` fallback never
 * fires: the guide's "else focus the list" / "else leave the timeline" branches were dead, and the
 * cursor was stranded or lost instead (B106 Undo restore, B114 time travel). The Boolean overload
 * reports the outcome; runCatching only guards the Default/Cancel sentinels, which still throw.
 */
internal fun FocusRequester.requestFocusOrFalse(): Boolean =
    runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)

/**
 * Requests focus on a control inside another `focusRestorer` group (the hub header, a guide list) and makes it stick.
 * A request arriving from outside such a group enters it first, and the group's restorer redirects the enter to its
 * saved child or fallback (proved in HubContentFocusRestoreTest: a request for row 4 from outside landed on row 1).
 * The second ask comes from inside the group and reaches the control itself.
 * Device pass 2, finding C: UP from guide row 1 left no control focused (no ring anywhere).
 */
internal fun FocusRequester.requestFocusPastRestorer(): Boolean {
    val first = requestFocusOrFalse()
    return requestFocusOrFalse() || first
}

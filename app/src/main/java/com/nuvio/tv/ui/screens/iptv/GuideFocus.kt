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

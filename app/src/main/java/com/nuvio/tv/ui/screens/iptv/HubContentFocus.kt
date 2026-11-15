package com.nuvio.tv.ui.screens.iptv

import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Where focus goes when it comes back into the hub from the side rail.
 *
 * Device pass 1 (2026-11-15), finding 4: the rail hands focus back by going unfocusable as it collapses
 * (ModernSidebarBlurPanel `canFocus`), so Compose enters the hub afresh and the first group it met was the tab row
 * (its restorer: the active tab). A plain `focusRestorer` on the hub did not help: focus left for the rail by
 * `requestFocus` (MainActivity pendingSidebarFocusRequest), which saves no child. So the hub remembers the content
 * region the viewer was last in and enters there (01-hub-entry: "later visits: exactly where you left"). The header
 * clears it: leaving from the tabs comes back to the tabs. Main-thread only.
 */
class HubContentReturn {
    private var target: (() -> FocusRequester?)? = null

    /** Focus is now in a content region; [target] names the control to come back to. Null = the header. */
    fun remember(target: (() -> FocusRequester?)?) {
        this.target = target
    }

    /** The control to enter on, or null to leave Compose's own entry (the tab row) in charge. */
    fun target(): FocusRequester? = target?.invoke()
}

/**
 * The redirect goes through `enter` (a returned requester), not a `requestFocus` inside `onEnter`: an enter caused by
 * the rail going unfocusable carried on after such a request and moved one row further (proved in
 * HubContentFocusRestoreTest).
 */
@Suppress("DEPRECATION")
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.hubContentFocusRestore(contentReturn: HubContentReturn): Modifier =
    focusProperties { enter = { contentReturn.target() ?: FocusRequester.Default } }.focusGroup()

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
 * region the viewer was last in and enters there (01-hub-entry: "later visits: exactly where you left"). Leaving from
 * the header still comes back into the content (01: "never onto the tab row").
 *
 * It is also the hub's focus safety net (device pass 3, finding 3): when the focused control leaves composition
 * (a list swapped under it, a panel closed), Compose re-enters from the root, and this `enter` puts focus back on
 * the region the viewer was in instead of the first control on screen. Main-thread only.
 */
class HubContentReturn {
    private var target: (() -> FocusRequester?)? = null

    /** Focus is now in a content region; [target] names the control to come back to. Null = the header. */
    fun remember(target: (() -> FocusRequester?)?) {
        this.target = target
    }

    /**
     * Device pass 3, finding 1: focus in the header keeps the content region. 01-hub-entry: RIGHT or OK on the rail
     * goes "into the hub content ... never onto the tab row"; clearing it here sent the round trip rail - tabs - rail
     * back to the Live TV tab.
     */
    fun onHeaderFocused() = Unit

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

/**
 * Device pass 3, finding 2: the group column collapsed to zero width sits right under the Live TV tab, so LEFT on
 * the tab (and LEFT from the guide's left edge) took it as "to the left" and focus went into an invisible row:
 * nothing focused, and a second LEFT reached the rail. While collapsed it refuses to be entered, so LEFT falls
 * through to MainActivity, which opens the rail. Requests made after it opened again (exitCategory waits for the
 * full width) are not affected.
 */
internal fun Modifier.collapsedFocusGuard(collapsed: Boolean): Modifier =
    focusProperties { onEnter = { if (collapsed) cancelFocusChange() } }

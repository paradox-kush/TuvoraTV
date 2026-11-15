package com.nuvio.tv.ui.screens.iptv

/**
 * B132: whether focus landing on a hub section tab may switch the section.
 *
 * The tabs switch on focus (TiViMate-style), which is right while the viewer is arrowing along the
 * header. It is wrong when focus merely ARRIVES on a tab from somewhere else: the hub regains its
 * window after Back from a Movies/Series detail or a "View all" page, the sidebar hands focus back,
 * or Compose's initial-focus search picks the top-left control. That control is the Live tab, so
 * Back from Movies switched the hub to Live and mounted the guide with its preview player ("Back
 * opened a live channel"). Only a move that started inside the header may change the section;
 * a landing from outside leaves it alone, and OK on the tab still selects it.
 */
internal object HubSectionFocusPolicy {
    /**
     * A2 (IPTV redesign 2026-11-15, owner default): section tabs change on OK only. Walking RIGHT toward Search,
     * Recordings or the playlist passed Movies and Series and reloaded the page each time (UX253, UX338, UX355,
     * UX292). Flip to true to restore the TiViMate-style switch-on-focus (with the B132 rules below).
     */
    const val SWITCH_ON_FOCUS = false

    /** The section to switch to, or null to stay. [chip] is null for header controls that are not tabs. */
    fun sectionOnFocus(
        chip: XtreamSection?,
        current: XtreamSection,
        cameFromHeader: Boolean,
        switchOnFocus: Boolean = SWITCH_ON_FOCUS,
    ): XtreamSection? = chip?.takeIf { switchOnFocus && cameFromHeader && it != current }
}

/**
 * Remembers whether focus is already travelling inside the hub header. One per composed header;
 * main-thread only (focus callbacks).
 */
internal class HubHeaderFocus(private val switchOnFocus: Boolean = HubSectionFocusPolicy.SWITCH_ON_FOCUS) {
    private var focusInHeader = false
    private var rowHasFocus = false

    /**
     * The header row's aggregate focus changed. A move between two header controls reports "no focus"
     * BEFORE the next control gains it (same focus transaction), so a loss only counts once [post] runs
     * on the next main-loop turn and the row still holds nothing.
     */
    fun onHeaderFocusChanged(hasFocus: Boolean, post: (Runnable) -> Unit) {
        rowHasFocus = hasFocus
        if (!hasFocus) post(Runnable { if (!rowHasFocus) onHeaderFocusLost() })
    }

    /** The header row no longer holds focus (it moved to the content, a dialog, or another screen). */
    fun onHeaderFocusLost() {
        focusInHeader = false
    }

    /** A header control took focus; returns the section to switch to, or null. */
    fun onControlFocused(chip: XtreamSection?, current: XtreamSection): XtreamSection? {
        val cameFromHeader = focusInHeader
        focusInHeader = true
        return HubSectionFocusPolicy.sectionOnFocus(chip, current, cameFromHeader, switchOnFocus)
    }
}

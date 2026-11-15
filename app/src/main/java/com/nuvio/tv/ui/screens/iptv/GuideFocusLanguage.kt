package com.nuvio.tv.ui.screens.iptv

/**
 * Change 2 of the IPTV redesign (2026-11-15): one focus language for the hub and the guide. Three marks, three
 * shapes, never shared, so "where my remote points", "what is shown" and "what is playing" read apart at ten feet:
 *
 * - **Focused** = the marigold FocusRing, drawn inside the element. Nothing else uses it as a full outline.
 * - **Selected** = a soft marigold-brown fill and bold text (the active tab, the shown group, the row whose cells
 *   the cursor is in).
 * - **Playing** = a red-orange left edge, a red channel number and level bars beside the name; it never shortens
 *   the name and never draws a ring.
 * - **At rest** every row keeps a hairline outline, so the grid survives AMOLED black.
 *
 * Grey `Primary` is no longer the cursor anywhere in IPTV (on TV it is neutral500, which read as disabled).
 * Pure, so it tests without Compose.
 */
internal object GuideFocusLanguage {
    /** Ring width in dp (the approved Multiview spec, 02-guide-normal). One constant so the owner can tune it. */
    const val RING_DP = 4

    /** Hairline outline at rest, in dp. */
    const val REST_OUTLINE_DP = 1

    /** Alpha of the soft fill that marks "selected" (over FocusBackground). */
    const val SELECTED_FILL_ALPHA = 0.55f

    data class Look(
        /** The FocusRing outline: only the element the remote points at. */
        val ring: Boolean,
        /** The soft fill: the selected element, focused or not. */
        val softFill: Boolean,
        val boldText: Boolean,
        /** The hairline outline at rest (no ring). */
        val restOutline: Boolean,
        /** Red left edge + red number + level bars. */
        val playingMarks: Boolean,
    )

    fun look(focused: Boolean, selected: Boolean, playing: Boolean = false): Look = Look(
        ring = focused,
        softFill = selected,
        boldText = selected,
        restOutline = !focused,
        playingMarks = playing,
    )
}

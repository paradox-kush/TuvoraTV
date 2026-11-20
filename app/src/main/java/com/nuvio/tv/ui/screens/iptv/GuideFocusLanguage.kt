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

    // ---- Restyle "B" (2026-11-20, owner pick; supersedes the ring ONLY for the guide list) -----------------------
    // The group column, the channel rows and the programme lane sit on one panel each, divided by hairlines.
    // Focus there is a SOLID FILL with dark content (the ring stays everywhere else: side panel, overlay, hub).
    // Marks stay distinct: focus = solid white fill; selected = quiet fill + a thin accent bar; playing = a red edge.

    /** Solid focus fill (opaque white): >= 15:1 against AMOLED black, so it is unmistakable on a TV. */
    const val LIST_FOCUS_FILL_ARGB = 0xFFFFFFFFL

    /** Text / icons drawn on the solid fill. */
    const val LIST_FOCUS_CONTENT_ARGB = 0xFF111111L

    /** The selected row's quiet fill (white at this alpha over the panel). Far below the focus fill on purpose. */
    const val QUIET_FILL_ALPHA = 0.08f

    /** The selected group's / active row's accent bar, in dp. */
    const val SELECTED_BAR_DP = 3

    /** The playing channel's red left edge, in dp. */
    const val PLAYING_EDGE_DP = 5

    /** Hairline between rows / programme ticks, in dp. */
    const val HAIRLINE_DP = 1

    data class ListLook(
        /** Solid fill: the element the remote points at. */
        val solidFill: Boolean,
        /** Quiet fill: selected and NOT focused (focus wins the fill). */
        val quietFill: Boolean,
        /** The thin accent bar on the selected element, kept under focus. */
        val selectedBar: Boolean,
        /** Content flips dark because it sits on the solid fill. */
        val darkContent: Boolean,
        val playingEdge: Boolean,
        /** Rows are always divided by a hairline, so the panel survives AMOLED black. */
        val hairline: Boolean,
    )

    fun listLook(focused: Boolean, selected: Boolean, playing: Boolean = false): ListLook = ListLook(
        solidFill = focused,
        quietFill = selected && !focused,
        selectedBar = selected,
        darkContent = focused,
        playingEdge = playing,
        hairline = true,
    )
}

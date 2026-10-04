package com.nuvio.tv.ui.screens.iptv

/**
 * How LEFT/RIGHT move through time inside a guide row's timeline, and where the cursor goes when
 * the window moves.
 *
 * The D-pad only stops on cells where OK does something ([GuideCellIntent]) — but time travel must
 * not depend on such a cell existing in the window it lands on. Future programmes are never
 * actionable, and neither is the past of a channel with no archive, so a window that travelled
 * there used to have nowhere for the cursor to go: it was dropped back on the channel row and the
 * next RIGHT, finding nothing to focus, reset the guide to now (B114/B11 — "the remote cannot move
 * back or forward in time"). When there is no cell, the cursor holds the row's STRIP instead, and
 * LEFT/RIGHT on the strip keep travelling — the TV equivalent of mobile's Earlier/Later buttons,
 * which move the window whatever the cells hold.
 */
internal object GuideTimelineNavPolicy {

    enum class Direction { BACK, FORWARD }

    enum class KeyOutcome {
        /** Let focus search step to the neighbouring cell inside the window. */
        WALK,

        /** Page the window back ([GuideTimeTravel.EDGE_TRAVEL_SLOTS]). */
        TRAVEL_BACK,

        /** Page the window forward. */
        TRAVEL_FORWARD,
    }

    /** Where the cursor lands after the window moves, or when the viewer steps into the timeline. */
    enum class Landing {
        /** The edge cell nearest the direction of travel. */
        CELL,

        /** The row's strip: the window holds nothing actionable, but the cursor stays in time. */
        STRIP,
    }

    /**
     * [focusedCellStart] is the start of the focused programme cell, or null when the strip itself
     * holds focus. [actionableStarts] are the window's focusable cells, in time order.
     */
    fun onHorizontalKey(
        direction: Direction,
        focusedCellStart: Long?,
        actionableStarts: List<Long>,
    ): KeyOutcome {
        val travel = if (direction == Direction.BACK) KeyOutcome.TRAVEL_BACK else KeyOutcome.TRAVEL_FORWARD
        // On the strip there is no neighbouring cell to walk to: every press is a page.
        if (focusedCellStart == null) return travel
        val edge = if (direction == Direction.BACK) actionableStarts.firstOrNull() else actionableStarts.lastOrNull()
        return if (focusedCellStart == edge) travel else KeyOutcome.WALK
    }

    fun landing(hasActionableCell: Boolean): Landing =
        if (hasActionableCell) Landing.CELL else Landing.STRIP

    /**
     * Whether the strip is a focus target. Only on the row the viewer is in, and only while it has
     * nothing better to offer — or while it already holds focus, so cells arriving under the cursor
     * (history landing late) can never pull the focus target out from under it — or while a page is
     * in flight ([travelling]): the window reaches the screen a frame or more after the key (the
     * ViewModel round trip), and the cell that held the cursor leaves with the old window. Parked on
     * the strip, which survives the move, the cursor stays in the row; left on the cell, focus was
     * dropped and the view re-took it on row 1 (Onn, on device).
     */
    fun stripFocusable(
        interactive: Boolean,
        hasActionableCell: Boolean,
        stripFocused: Boolean,
        travelling: Boolean = false,
    ): Boolean = interactive && (!hasActionableCell || stripFocused || travelling)

    /**
     * Whether the edge landing after a page should run now: only once the window has actually moved
     * off [travelFromWindowMs]. Run on the old window it refocused the departing cell.
     */
    fun landingDue(pendingSlots: Int, travelFromWindowMs: Long?, windowStartMs: Long): Boolean =
        pendingSlots != 0 && travelFromWindowMs != null && windowStartMs != travelFromWindowMs

    enum class BackOutcome {
        /** Leave the timeline and return the guide to now. */
        LEAVE_TIMELINE,

        /** The press: swallowed so Compose's Exit cannot move the cursor to the row first. */
        CONSUME,
    }

    /**
     * BACK inside the timeline. Compose maps Back to FocusDirection.Exit (ui 1.11.2
     * FocusInteropUtils.toFocusDirection), which moves the cursor from a cell to its row and consumes
     * the key, so the guide's BackHandler only saw a second press. The timeline answers it itself:
     * swallow the press, leave on the release (BACK acts on release, like the platform's), so no
     * half of the key is left over for the next focus owner or the activity.
     */
    fun onBack(isKeyDown: Boolean): BackOutcome =
        if (isKeyDown) BackOutcome.CONSUME else BackOutcome.LEAVE_TIMELINE
}

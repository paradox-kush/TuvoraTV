package com.nuvio.tv.ui.screens.iptv

/**
 * Change 3 of the IPTV redesign (2026-11-15, 04-channel-panel): channel options as a right-side panel. The first row
 * never changes state (UX211: the old menu opened on "Add to Favorites", so a double OK starred the channel), Start
 * over is not one press below it any more (UX392), and the guide's size left the channel's menu for its own place.
 * Pure, so it tests without Compose.
 */
internal object GuideChannelPanelPolicy {

    enum class Variant {
        /** MENU or hold OK on a guide row. */
        CHANNEL,

        /** MENU or hold OK in full screen. */
        FULLSCREEN,
    }

    enum class Section { WATCH, FAVORITES, ORDER, GUIDE, TIDY, INFO }

    enum class Row(val section: Section, val keepsPanelOpen: Boolean = false) {
        WATCH_FULLSCREEN(Section.WATCH),
        OPEN_GUIDE(Section.WATCH),
        ADD_FAVORITE(Section.FAVORITES),
        REMOVE_FAVORITE(Section.FAVORITES),
        MOVE_UP(Section.ORDER, keepsPanelOpen = true),
        MOVE_DOWN(Section.ORDER, keepsPanelOpen = true),
        FIX_GUIDE(Section.GUIDE),
        GUIDE_SIZE(Section.GUIDE),
        HIDE(Section.TIDY),
        STREAM_INFO(Section.INFO),
    }

    /**
     * The rows, top to bottom. [menuActions] is [GuideChannelMenuPolicy.menu] for this channel (Move up / Move down in
     * Favorites, Hide elsewhere); [noGuide] adds "Fix guide" when the channel's playlist sent none. [guideSizeHere]
     * keeps the guide size reachable from the panel until the Guide options panel holds it (A9).
     */
    fun rows(
        variant: Variant,
        favorite: Boolean,
        menuActions: List<GuideChannelMenuPolicy.Action>,
        noGuide: Boolean,
        guideSizeHere: Boolean,
    ): List<Row> = buildList {
        when (variant) {
            Variant.CHANNEL -> {
                add(Row.WATCH_FULLSCREEN)
                add(if (favorite) Row.REMOVE_FAVORITE else Row.ADD_FAVORITE)
                if (GuideChannelMenuPolicy.Action.MOVE_UP in menuActions) add(Row.MOVE_UP)
                if (GuideChannelMenuPolicy.Action.MOVE_DOWN in menuActions) add(Row.MOVE_DOWN)
                if (noGuide) add(Row.FIX_GUIDE)
                if (guideSizeHere) add(Row.GUIDE_SIZE)
                // In Favorites "Remove" is the favourite row itself; Hide is offered wherever the menu offers it.
                if (GuideChannelMenuPolicy.Action.HIDE in menuActions) add(Row.HIDE)
            }
            Variant.FULLSCREEN -> {
                add(Row.OPEN_GUIDE)
                add(if (favorite) Row.REMOVE_FAVORITE else Row.ADD_FAVORITE)
                add(Row.STREAM_INFO)
            }
        }
    }

    /** Where the cursor stays after a row that keeps the panel open ran: the same row, else its neighbour, else row 1. */
    fun focusAfter(ran: Row, before: List<Row>, after: List<Row>): Row? {
        if (ran in after) return ran
        val index = before.indexOf(ran)
        val neighbours = (index + 1 until before.size).map(before::get) + (index - 1 downTo 0).map(before::get)
        return neighbours.firstOrNull { it in after } ?: after.firstOrNull()
    }

    /** A section label shows above the first row of each section except the first one (the panel title heads it). */
    fun labelAbove(rows: List<Row>, index: Int): Section? {
        if (index == 0) return null
        val section = rows[index].section
        return section.takeIf { rows[index - 1].section != it }
    }
}

/**
 * UX213 / 04 "First OK after opening must register": the press that OPENED a panel (a long OK, MENU) keeps sending
 * repeats and its KeyUp to whatever has focus next. The panel ignores keys until a fresh press (a KeyDown that is not
 * a repeat) arrives, so the opening press can never run the first row.
 */
internal class PanelKeyGate {
    private var armed = false

    /** True when the panel may act on this key event. */
    fun admit(isKeyDown: Boolean, repeatCount: Int): Boolean {
        if (armed) return true
        if (isKeyDown && repeatCount == 0) armed = true
        return armed
    }
}

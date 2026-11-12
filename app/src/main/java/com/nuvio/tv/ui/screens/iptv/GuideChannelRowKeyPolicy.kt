package com.nuvio.tv.ui.screens.iptv

import androidx.compose.ui.input.key.Key

/**
 * What a remote key does on a focused guide CHANNEL ROW (the label block, not a programme cell).
 *
 * Pulled out of the Composable so the decision is testable without Compose focus: the row used to
 * handle RIGHT/LEFT inline and nothing else, so the "MENU hide" the guide hint advertises did nothing
 * on a channel row (B106) — it only worked in fullscreen and on a category.
 */
internal object GuideChannelRowKeyPolicy {

    enum class Action {
        /** Let the key fall through (focus search, clickable, the root handler). */
        PASS,

        /** Key handled with nothing to run (the KeyUp half of a consumed press). */
        CONSUME,

        /** RIGHT: step into this channel's timeline. */
        ENTER_TIMELINE,

        /** LEFT: back to the category column. */
        EXIT_CATEGORY,

        /** MENU: open explicit channel actions. */
        OPEN_CHANNEL_MENU,

        /** UP on the first row: leave the list for the selected tab. */
        FOCUS_TABS,
    }

    fun actionFor(
        key: Key,
        isKeyDown: Boolean,
        rowFocused: Boolean,
        lockFocus: Boolean,
        timelineActive: Boolean,
        canExitCategory: Boolean,
        isFirstRow: Boolean = false,
        awayFromNow: Boolean = false,
        hasTabTarget: Boolean = false,
        repeatCount: Int = 0,
    ): Action {
        // Fullscreen: the row is a hidden focus anchor and every key belongs to the root handler.
        // In the timeline the cells own the keys; and a row that is merely an ancestor of the
        // focused cell must not act on them.
        if (lockFocus || timelineActive || !rowFocused) return Action.PASS
        // MENU: both halves are ours, so the KeyUp can't fall through to the activity once the
        // DOWN has hidden the row (the same consumption the category row's MENU uses).
        if (key == Key.Menu) return if (isKeyDown) Action.OPEN_CHANNEL_MENU else Action.CONSUME
        // B254: UP from the first row leaves the list for the tabs, on a fresh press only: a held
        // key stops at the top (that is why the row used to clamp UP) instead of flying off into
        // the tabs. In travelled time UP belongs to return-to-now, which focusProperties routes.
        if (key == Key.DirectionUp && isFirstRow && !awayFromNow && hasTabTarget) {
            if (!isKeyDown) return Action.PASS
            return if (repeatCount == 0) Action.FOCUS_TABS else Action.CONSUME
        }
        if (!isKeyDown) return Action.PASS
        return when (key) {
            Key.DirectionRight -> Action.ENTER_TIMELINE
            Key.DirectionLeft -> if (canExitCategory) Action.EXIT_CATEGORY else Action.PASS
            else -> Action.PASS
        }
    }
}

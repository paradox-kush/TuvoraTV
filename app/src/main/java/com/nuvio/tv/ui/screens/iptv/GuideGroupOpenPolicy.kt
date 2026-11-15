package com.nuvio.tv.ui.screens.iptv

/**
 * OK / RIGHT on a group (02-guide-normal focus map): "opens the group, closes the column, focus on the group's
 * remembered row (else row 1). On the shown group: just returns to its rows. On an empty group: its one action."
 *
 * Device pass 1 (2026-11-15), finding 2: OK / RIGHT moved focus at once into the list still on screen, which was the
 * PREVIOUS group's (groups open on OK only, A12). The switch then disposed that row and focus fell to the Live TV
 * tab. Focus now holds on the group row until the opened group's own list is in, then lands. Pure, so it tests
 * without Compose.
 */
internal object GuideGroupOpenPolicy {
    enum class Handoff {
        /** Move into the channel list now: the group is already shown and its list is in. */
        INTO_LIST,
        /** Stay on the group row; the landing moves focus once the group's own list is in. */
        HOLD_ON_GROUP,
    }

    enum class Landing { WAIT, ROW, LIST_ACTION }

    fun handoff(opensShownGroup: Boolean, listLanded: Boolean): Handoff =
        if (opensShownGroup && listLanded) Handoff.INTO_LIST else Handoff.HOLD_ON_GROUP

    /** Where the pending landing goes now: wait for the group's list, its remembered row, or its one action. */
    fun landing(groupShown: Boolean, loading: Boolean, empty: Boolean): Landing = when {
        !groupShown || loading -> Landing.WAIT
        empty -> Landing.LIST_ACTION
        else -> Landing.ROW
    }
}

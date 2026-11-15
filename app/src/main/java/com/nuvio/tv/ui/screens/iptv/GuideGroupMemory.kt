package com.nuvio.tv.ui.screens.iptv

/**
 * B4 (IPTV redesign 2026-11-15): Favorites, Recent and All channels stay pinned on top, the last groups opened sit
 * right under them, and the guide reopens on the group and the row the viewer left. Pure, so it tests without
 * Android (house pattern: [GuideFocusRestorePolicy]).
 */
internal object GuideGroupMemoryPolicy {
    /** How many "recent groups" the column lists under the pinned rows (02-guide-normal: "the last two you opened"). */
    const val RECENT_GROUPS = 2

    /** Row ids of the recent-group copies; the real id follows the prefix. */
    const val RECENT_GROUP_PREFIX = "__rg:"

    /** [opened] goes to the front of [recent] (newest first), without duplicates, capped at [max]. */
    fun remember(recent: List<String>, opened: String, max: Int = RECENT_GROUPS): List<String> =
        (listOf(opened) + recent.filter { it != opened }).take(max)

    /** Only provider and custom groups count as "recent groups"; pinned rows and Search are already on top. */
    fun countsAsRecentGroup(special: GuideSpecial?): Boolean = special == null || special == GuideSpecial.GROUP

    /**
     * The column with copies of the recent groups inserted right after the pinned rows (the leading run of
     * synthetic rows). A copy keeps the group's name and kind, gets its own id ([RECENT_GROUP_PREFIX] + id, a
     * duplicate LazyColumn key is a crash) and points back with [GuideCategory.aliasOf]. A remembered group that is
     * no longer listed (hidden, deselected, gone) is skipped.
     */
    fun withRecentGroups(categories: List<GuideCategory>, recent: List<String>): List<GuideCategory> {
        if (recent.isEmpty()) return categories
        val byId = categories.associateBy { it.id }
        val copies = recent.mapNotNull { id ->
            byId[id]?.takeIf { countsAsRecentGroup(it.special) && it.aliasOf == null }
                ?.let { GuideCategory(RECENT_GROUP_PREFIX + it.id, it.name, it.special, aliasOf = it.id) }
        }
        if (copies.isEmpty()) return categories
        val pinnedCount = categories.indexOfFirst { countsAsRecentGroup(it.special) }.let { if (it < 0) categories.size else it }
        return categories.take(pinnedCount) + copies + categories.drop(pinnedCount)
    }

    /** The real category a row stands for. */
    fun resolve(category: GuideCategory): String = category.aliasOf ?: category.id

    /**
     * The group a fresh visit opens on: the remembered one while it is still listed, else Favorites when this
     * playlist has any, else All channels. (02 / 01: "row 1 of Favorites if there are any, else row 1 of All".)
     */
    fun initialCategory(
        visible: List<GuideCategory>,
        remembered: String?,
        hasFavorites: Boolean,
    ): String? {
        remembered?.let { id -> visible.firstOrNull { it.id == id && it.aliasOf == null && it.special != GuideSpecial.SEARCH } }
            ?.let { return it.id }
        if (hasFavorites) visible.firstOrNull { it.special == GuideSpecial.FAVORITES }?.let { return it.id }
        return visible.firstOrNull { it.special == GuideSpecial.ALL }?.id ?: visible.firstOrNull()?.id
    }

    /** The row to land on when a group opens: the one remembered for it while it is still in the list, else row 1. */
    fun rowOnOpen(remembered: String?, visibleEntityIds: List<String>): String? =
        remembered?.takeIf { it in visibleEntityIds } ?: visibleEntityIds.firstOrNull()
}

/**
 * B4: where the viewer left each playlist's guide, for this app session (group, row per group, recent groups).
 * Process memory on purpose: it outlives the hub's view model (Back to Home and in again) without a store or a sync.
 * Main-thread only (guide view model and focus callbacks).
 */
internal object GuideGroupMemory {
    private val lastGroup = HashMap<String, String>()
    private val recentGroups = HashMap<String, List<String>>()
    private val rowByGroup = HashMap<String, String>()

    fun lastGroup(accountId: String): String? = lastGroup[accountId]
    fun recentGroups(accountId: String): List<String> = recentGroups[accountId].orEmpty()
    fun row(accountId: String, groupId: String): String? = rowByGroup["$accountId|$groupId"]

    /** A group is on screen (focus rested on it, or it was opened). Search is not a place to come back to. */
    fun onGroupShown(accountId: String, groupId: String, special: GuideSpecial?) {
        if (special == GuideSpecial.SEARCH) return
        lastGroup[accountId] = groupId
    }

    /**
     * The viewer opened a group (OK / RIGHT). Only this counts as "recent": a group merely passed on the way down
     * the column must not reshuffle the rows under the cursor. Returns whether the recent list changed.
     */
    fun onGroupEntered(accountId: String, groupId: String, special: GuideSpecial?): Boolean {
        onGroupShown(accountId, groupId, special)
        if (!GuideGroupMemoryPolicy.countsAsRecentGroup(special)) return false
        val before = recentGroups(accountId)
        val after = GuideGroupMemoryPolicy.remember(before, groupId)
        recentGroups[accountId] = after
        return after != before
    }

    fun onRowFocused(accountId: String, groupId: String, entityId: String) {
        rowByGroup["$accountId|$groupId"] = entityId
    }

    internal fun clearForTest() {
        lastGroup.clear(); recentGroups.clear(); rowByGroup.clear()
    }
}

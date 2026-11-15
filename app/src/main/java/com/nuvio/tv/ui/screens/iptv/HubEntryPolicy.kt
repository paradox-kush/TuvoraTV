package com.nuvio.tv.ui.screens.iptv

/**
 * UX389: where the IPTV hub opens. It used to open on MOVIES by default and restored a remembered tab without
 * asking whether it had anything, so a playlist with no series opened on "Nothing here". Pure, so it tests
 * without Android (house pattern: [GuideFocusRestorePolicy]).
 */
internal object HubEntrySectionPolicy {
    /** Tab order, Live TV first. */
    private val ORDER = listOf(XtreamSection.LIVE, XtreamSection.MOVIES, XtreamSection.SERIES)

    /**
     * The tab a fresh entry opens on: the remembered one while it is enabled and not known to be empty, else the
     * first enabled tab not known to be empty, else the first enabled tab (every tab empty is the viewer's call).
     * [knownEmpty] holds only positive evidence (a portal that reported no series), never "not loaded yet".
     */
    fun initial(remembered: XtreamSection?, enabled: Set<XtreamSection>, knownEmpty: Set<XtreamSection>): XtreamSection {
        remembered?.takeIf { it in enabled && it !in knownEmpty }?.let { return it }
        return ORDER.firstOrNull { it in enabled && it !in knownEmpty }
            ?: ORDER.firstOrNull { it in enabled }
            ?: XtreamSection.LIVE
    }

    /** The tab to hand over to when the entry tab [current] proved empty; null once every enabled tab was [tried]. */
    fun advanceFrom(current: XtreamSection, enabled: Set<XtreamSection>, tried: Set<XtreamSection>): XtreamSection? =
        ORDER.firstOrNull { it in enabled && it != current && it !in tried }
}

/**
 * UX389: entry focus goes into the channel list, never onto the tab row. The target is the most recently watched
 * channel that is in this list, else row 1. Focus only: entry never tunes (a Stalker single-use link is spent by
 * a tune, and browsing must cost no provider connection).
 */
internal object GuideEntryFocusPolicy {
    /** [recentIds] newest first. */
    fun target(recentIds: List<String>, visibleIds: List<String>): String? {
        if (visibleIds.isEmpty()) return null
        val visible = visibleIds.toHashSet()
        return recentIds.firstOrNull { it in visible } ?: visibleIds.first()
    }

    /** One shot: when the list has landed, and only while the viewer has not already moved focus into the guide. */
    fun due(pending: Boolean, listLanded: Boolean, focusAlreadyInGuide: Boolean): Boolean =
        pending && listLanded && !focusAlreadyInGuide
}

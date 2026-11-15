package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UX13 + B11 (IPTV redesign 2026-11-15): every empty view names its cause and holds exactly one focused action.
 * JUnit: (message, expected, actual).
 */
class GuideEmptyStatePolicyTest {

    @Test
    fun `an empty Favorites view explains how to add a favourite and offers All channels`() {
        val kind = GuideEmptyStatePolicy.kind(GuideSpecial.FAVORITES)
        assertEquals("Favorites empty state teaches the gesture", GuideEmptyStatePolicy.Kind.FAVORITES_HINT, kind)
        assertEquals("its one action", GuideEmptyStatePolicy.Action.BROWSE_ALL, GuideEmptyStatePolicy.action(kind))
        assertEquals(
            "All favorites too",
            GuideEmptyStatePolicy.Kind.FAVORITES_HINT,
            GuideEmptyStatePolicy.kind(GuideSpecial.ALL_FAVORITES),
        )
    }

    @Test
    fun `an empty Recent view sends the viewer to All channels`() {
        val kind = GuideEmptyStatePolicy.kind(GuideSpecial.RECENT)
        assertEquals("Recent", GuideEmptyStatePolicy.Kind.RECENT_EMPTY, kind)
        assertEquals("Recent action", GuideEmptyStatePolicy.Action.BROWSE_ALL, GuideEmptyStatePolicy.action(kind))
    }

    @Test
    fun `search before typing opens the keyboard and a miss offers Clear search`() {
        val prompt = GuideEmptyStatePolicy.kind(GuideSpecial.SEARCH, searchQuery = "  ")
        assertEquals("blank query", GuideEmptyStatePolicy.Kind.SEARCH_PROMPT, prompt)
        assertEquals("blank query action", GuideEmptyStatePolicy.Action.TYPE_SEARCH, GuideEmptyStatePolicy.action(prompt))
        val miss = GuideEmptyStatePolicy.kind(GuideSpecial.SEARCH, searchQuery = "zzz")
        assertEquals("a miss", GuideEmptyStatePolicy.Kind.SEARCH_NO_MATCH, miss)
        assertEquals("a miss action", GuideEmptyStatePolicy.Action.CLEAR_SEARCH, GuideEmptyStatePolicy.action(miss))
    }

    @Test
    fun `an empty All channels is the playlist's doing and points at its settings`() {
        val kind = GuideEmptyStatePolicy.kind(GuideSpecial.ALL)
        assertEquals("All", GuideEmptyStatePolicy.Kind.PLAYLIST_EMPTY, kind)
        assertEquals("All action", GuideEmptyStatePolicy.Action.PLAYLIST_SETTINGS, GuideEmptyStatePolicy.action(kind))
    }

    @Test
    fun `an empty provider or custom group offers another group`() {
        listOf(null, GuideSpecial.GROUP).forEach { special ->
            val kind = GuideEmptyStatePolicy.kind(special)
            assertEquals("special=$special", GuideEmptyStatePolicy.Kind.NO_CHANNELS, kind)
            assertEquals("special=$special action", GuideEmptyStatePolicy.Action.PICK_GROUP, GuideEmptyStatePolicy.action(kind))
        }
    }

    @Test
    fun `every empty kind has exactly one action`() {
        GuideEmptyStatePolicy.Kind.entries.forEach { kind ->
            // A when without else: a new kind that forgets its action does not compile; this pins it at runtime too.
            GuideEmptyStatePolicy.action(kind)
        }
    }

    @Test
    fun `a failed load offers Try again first unless the provider blocked us`() {
        assertEquals(
            "plain failure",
            listOf(GuideFailureActionsPolicy.Action.TRY_AGAIN, GuideFailureActionsPolicy.Action.PLAYLIST_SETTINGS),
            GuideFailureActionsPolicy.actions(blockedByProvider = false),
        )
        assertEquals(
            "a retry will not help while the provider blocks us",
            listOf(GuideFailureActionsPolicy.Action.PLAYLIST_SETTINGS, GuideFailureActionsPolicy.Action.TRY_AGAIN),
            GuideFailureActionsPolicy.actions(blockedByProvider = true),
        )
    }
}

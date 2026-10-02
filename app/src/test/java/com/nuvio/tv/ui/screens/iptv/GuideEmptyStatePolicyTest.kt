package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** UX13: an empty Favorites view said only "No channels here" — it now says how to add one. */
class GuideEmptyStatePolicyTest {

    @Test
    fun `an empty Favorites view explains how to add a favourite`() {
        assertEquals(
            "Favorites empty state teaches hold-OK",
            GuideEmptyStatePolicy.Kind.FAVORITES_HINT,
            GuideEmptyStatePolicy.kind(GuideSpecial.FAVORITES),
        )
    }

    @Test
    fun `every other empty view keeps the generic message`() {
        listOf(null, GuideSpecial.RECENT, GuideSpecial.ALL, GuideSpecial.GROUP).forEach { special ->
            assertEquals("special=$special", GuideEmptyStatePolicy.Kind.NO_CHANNELS, GuideEmptyStatePolicy.kind(special))
        }
    }
}

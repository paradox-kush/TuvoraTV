package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.overlay.ChannelOverlay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression for "channel pins set on the web editor don't appear on TV". The default "All channels"
 * view capped the full catalog to ALL_CAP BEFORE applying the overlay, so a channel pinned past the
 * cap on a large provider was dropped before its pin could float it. The pin test fails on
 * cap-then-overlay and passes once the overlay is applied before the take.
 *
 * JUnit arg order: assertEquals(message, expected, actual).
 */
class GuideAllChannelsCapPolicyTest {

    // Ten provider channels c0..c9; this test caps "All channels" at 3.
    private val full = (0 until 10).map { "c$it" }

    @Test
    fun `no overlay caps to the first cap items in provider order`() {
        assertEquals(
            "an unpersonalized All-channels view is just the provider's first cap rows",
            listOf("c0", "c1", "c2"),
            GuideAllChannelsCapPolicy.capped(full, emptyMap(), cap = 3, entityId = { it }),
        )
    }

    @Test
    fun `a channel pinned beyond the cap floats to the front and a hidden one is dropped`() {
        val overlay = mapOf(
            "c8" to ChannelOverlay(pinned = true),   // provider index 8, well past cap 3
            "c1" to ChannelOverlay(hidden = true),
        )
        assertEquals(
            "pin must float from beyond the cap (overlay applied BEFORE the take); hidden absent",
            listOf("c8", "c0", "c2"),
            GuideAllChannelsCapPolicy.capped(full, overlay, cap = 3, entityId = { it }),
        )
    }
}

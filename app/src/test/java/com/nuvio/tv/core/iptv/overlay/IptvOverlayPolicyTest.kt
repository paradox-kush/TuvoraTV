package com.nuvio.tv.core.iptv.overlay

import com.nuvio.tv.core.iptv.overlay.IptvChannelOverlayPolicy.Tagged
import com.nuvio.tv.core.iptv.overlay.IptvCategoryOverlayPolicy.TaggedCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/** TV twin of NuvioMobile's IptvOverlayPolicyTest — the same pure hide/pin/reorder/custom-group decisions. */
class IptvOverlayPolicyTest {

    private fun ch(entity: String, idx: Int) = Tagged(entity, idx, entity)
    private val raw = listOf(ch("a", 0), ch("b", 1), ch("c", 2), ch("d", 3))

    @Test fun `no overlay preserves provider order`() =
        assertEquals(listOf("a", "b", "c", "d"), IptvChannelOverlayPolicy.displayed(raw, emptyMap()))

    @Test fun `hidden dropped`() =
        assertEquals(listOf("a", "c", "d"), IptvChannelOverlayPolicy.displayed(raw, mapOf("b" to ChannelOverlay(hidden = true))))

    @Test fun `pinned first`() =
        assertEquals(listOf("c", "d", "a", "b"), IptvChannelOverlayPolicy.displayed(raw, mapOf("c" to ChannelOverlay(pinned = true), "d" to ChannelOverlay(pinned = true))))

    @Test fun `manual position leads`() =
        assertEquals(listOf("d", "a", "b", "c"), IptvChannelOverlayPolicy.displayed(raw, mapOf("d" to ChannelOverlay(position = 0))))

    @Test fun `rename applied`() =
        assertEquals(listOf("Alpha", "b", "c", "d"), IptvChannelOverlayPolicy.displayed(raw, mapOf("a" to ChannelOverlay(rename = "Alpha")), withName = { _, n -> n }))

    @Test fun `custom groups above provider categories`() {
        val cats = listOf(TaggedCategory("k1", 0, "1", "News"), TaggedCategory("k2", 1, "2", "Sports"))
        val groups = listOf(
            CustomGroup("g2", "live", null, "Weekend", 1, memberEntityIds = listOf("x")),
            CustomGroup("g1", "live", null, "Favourites", 0, memberEntityIds = listOf("a")),
        )
        assertEquals(listOf("Favourites", "Weekend", "News", "Sports"), IptvCategoryOverlayPolicy.displayed(cats, emptyMap(), groups).map { it.name })
    }
}

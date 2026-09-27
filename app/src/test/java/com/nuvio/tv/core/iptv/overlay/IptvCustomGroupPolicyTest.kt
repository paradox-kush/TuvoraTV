package com.nuvio.tv.core.iptv.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TV twin of NuvioMobile's IptvCustomGroupPolicyTest (F02; JUnit: message first). */
class IptvCustomGroupPolicyTest {

    private fun group(id: String, type: String = "live", playlist: String? = "pl", members: List<String> = listOf("a")) =
        CustomGroup(id, type, playlist, "Group $id", 0, members)

    @Test
    fun `a playlist shows its own groups and those spanning playlists of the same type`() {
        val groups = listOf(group("mine"), group("span", playlist = null), group("other", playlist = "pl2"), group("vod", type = "movies"))
        assertEquals("own + spanning live groups", listOf("mine", "span"), IptvCustomGroupPolicy.groupsFor("pl", "live", groups).map { it.id })
    }

    @Test
    fun `members resolve in the group order skipping missing hidden and repeated ones`() {
        val lineup = mapOf("a" to "A", "b" to "B", "c" to "C", "d" to "D")
        val g = group("g", members = listOf("c", "gone", "a", "b", "c", "d"))
        val shown = IptvCustomGroupPolicy.members(g, lineup, channelOverlay = mapOf("b" to ChannelOverlay(hidden = true)))
        assertEquals("group order, no missing/hidden/repeats", listOf("C", "A", "D"), shown)
    }

    @Test
    fun `group rows carry a prefix so they never collide with provider category ids`() {
        val row = IptvCustomGroupPolicy.rowId("123")
        assertEquals("round trip", "123", IptvCustomGroupPolicy.groupIdOf(row))
        assertNull("a provider category id is not a group row", IptvCustomGroupPolicy.groupIdOf("123"))
    }
}

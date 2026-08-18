package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.ui.screens.iptv.LiveChannelZapPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JUnit here, so the argument order is (message, expected, actual). */
class XtreamLivePlaylistTest {

    private fun playlist(vararg ids: String) = XtreamLivePlaylist().apply {
        set(ids.map { LiveChannelRef(id = it, name = "Channel $it", logo = null, streamUrl = "http://x/$it") })
    }

    @Test
    fun `steps to the neighbour in each direction`() {
        val list = playlist("a", "b", "c")

        assertEquals("next", "c", list.relativeTo("b", 1)?.id)
        assertEquals("previous", "a", list.relativeTo("b", -1)?.id)
    }

    @Test
    fun `wraps past the last channel`() {
        val list = playlist("a", "b", "c")

        assertEquals("down from the last returns to the first", "a", list.relativeTo("c", 1)?.id)
    }

    @Test
    fun `wraps before the first channel`() {
        val list = playlist("a", "b", "c")

        assertEquals("up from the first lands on the last", "c", list.relativeTo("a", -1)?.id)
    }

    @Test
    fun `a single channel resolves to itself`() {
        val list = playlist("only")

        assertEquals("next", "only", list.relativeTo("only", 1)?.id)
        assertEquals("previous", "only", list.relativeTo("only", -1)?.id)
    }

    @Test
    fun `an unknown channel has no neighbour`() {
        assertNull("not in the published list", playlist("a", "b").relativeTo("zzz", 1))
    }

    @Test
    fun `an empty playlist has no neighbour`() {
        assertNull("nothing published yet", playlist().relativeTo("a", 1))
    }

    /**
     * The guide's fullscreen zap and the full player's UP/DOWN are two implementations of one
     * promise: a channel key means the same thing wherever the viewer pressed it. This is the test
     * that fails if only one of them is ever changed.
     */
    @Test
    fun `the guide policy and the player playlist agree on wrapping`() {
        val ids = listOf("a", "b", "c", "d")
        val list = playlist(*ids.toTypedArray())

        for (id in ids) {
            for (delta in listOf(-5, -3, -1, 1, 3, 5)) {
                assertEquals(
                    "from $id by $delta",
                    LiveChannelZapPolicyBridge.relativeTo(ids, id, delta),
                    list.relativeTo(id, delta)?.id
                )
            }
        }
    }
}

/**
 * The guide's index-based zap policy restated in the playlist's vocabulary (ids), so the two can be
 * compared directly. It delegates and must never grow a wrapping rule of its own — the moment it
 * does, the test above stops proving anything.
 */
private object LiveChannelZapPolicyBridge {
    fun relativeTo(ids: List<String>, id: String, delta: Int): String? {
        val index = ids.indexOf(id)
        // The playlist answers null for a channel it does not carry; the policy's "enter from the
        // end you are heading for" rule is for the guide, which always has a list on screen.
        if (index < 0) return null
        val target = LiveChannelZapPolicy.targetIndex(index, delta, ids.size) ?: return null
        return ids.getOrNull(target)
    }
}

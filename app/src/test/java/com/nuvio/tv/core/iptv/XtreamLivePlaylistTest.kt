package com.nuvio.tv.core.iptv

import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.ui.screens.iptv.LiveChannelZapPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit here, so the argument order is (message, expected, actual). */
class XtreamLivePlaylistTest {
    private val profile = PlaybackProfileId("2")

    private fun playlist(vararg ids: String) = XtreamLivePlaylist().apply {
        set(profile, ids.map { identity(it, "Channel $it") })
    }

    @Test
    fun `steps to the neighbour in each direction`() {
        val list = playlist("a", "b", "c")

        assertEquals("next", "c", list.relativeTo(profile, "b", 1)?.contentId?.value)
        assertEquals("previous", "a", list.relativeTo(profile, "b", -1)?.contentId?.value)
    }

    @Test
    fun `wraps past the last channel`() {
        val list = playlist("a", "b", "c")

        assertEquals(
            "down from the last returns to the first",
            "a",
            list.relativeTo(profile, "c", 1)?.contentId?.value,
        )
    }

    @Test
    fun `wraps before the first channel`() {
        val list = playlist("a", "b", "c")

        assertEquals(
            "up from the first lands on the last",
            "c",
            list.relativeTo(profile, "a", -1)?.contentId?.value,
        )
    }

    @Test
    fun `a single channel resolves to itself`() {
        val list = playlist("only")

        assertEquals("next", "only", list.relativeTo(profile, "only", 1)?.contentId?.value)
        assertEquals("previous", "only", list.relativeTo(profile, "only", -1)?.contentId?.value)
    }

    @Test
    fun `an unknown channel has no neighbour`() {
        assertNull("not in the published list", playlist("a", "b").relativeTo(profile, "zzz", 1))
    }

    @Test
    fun `an empty playlist has no neighbour`() {
        assertNull("nothing published yet", playlist().relativeTo(profile, "a", 1))
    }

    @Test
    fun `current and relative presentation share an immutable snapshot version without transport`() {
        val list = XtreamLivePlaylist().apply {
            set(
                profile,
                listOf(
                    identity("a", "Channel A", "https://images.invalid/a.png"),
                    identity("b", "Channel B"),
                ),
            )
        }

        val current = requireNotNull(list.presentationFor(profile, "a"))
        val relative = requireNotNull(list.relativePresentation(profile, "a", 1))

        assertEquals(current.playlistVersion, relative.playlistVersion)
        assertEquals("a", current.contentId.value)
        assertEquals("b", relative.contentId.value)
        assertEquals("Channel B", relative.title)
        val rendered = listOf(current, relative).joinToString()
        assertFalse(rendered.contains("Channel A"))
        assertFalse(rendered.contains("Channel B"))
        assertFalse(
            XtreamLiveChannelIdentity::class.java.declaredFields.any {
                it.name.contains("stream", ignoreCase = true) ||
                    it.name.contains("transport", ignoreCase = true)
            },
        )
        val snapshotType = requireNotNull(
            XtreamLivePlaylist::class.java.declaredClasses
                .firstOrNull { it.simpleName == "PlaylistSnapshot" },
        )
        assertTrue(
            snapshotType.declaredFields.any {
                it.name == "profileId" && it.type == PlaybackProfileId::class.java
            },
        )
        assertFalse(
            snapshotType.declaredFields.any {
                it.genericType.typeName.contains("LiveChannelRef") ||
                    it.name.contains("stream", ignoreCase = true) ||
                    it.name.contains("transport", ignoreCase = true)
            },
        )
    }

    @Test
    fun `presentation sanitizes transport shaped title logo secrets and increments snapshot version`() {
        val list = XtreamLivePlaylist()
        list.set(
            profile,
            listOf(
                identity(
                    id = "a",
                    title = "https://provider.invalid/live?token=secret",
                    logo = "https://images.invalid/logo?token=secret",
                ),
            ),
        )
        val first = requireNotNull(list.presentationFor(profile, "a"))
        list.set(profile, listOf(identity("a", "News\u0000  HD", "logo.png")))
        val second = requireNotNull(list.presentationFor(profile, "a"))

        assertEquals("Live TV", first.title)
        assertNull(first.logo)
        assertEquals("News HD", second.title)
        assertEquals("logo.png", second.logo)
        assertTrue(second.playlistVersion > first.playlistVersion)
    }

    @Test
    fun `all lookups fail closed for a different invalid or superseded profile`() {
        val list = playlist("a", "b")

        listOf(PlaybackProfileId("1"), PlaybackProfileId("0"), PlaybackProfileId("invalid"))
            .forEach { requestedProfile ->
                assertNull(list.presentationFor(requestedProfile, "a"))
                assertNull(list.relativePresentation(requestedProfile, "a", 1))
                assertNull(list.relativeTo(requestedProfile, "a", 1))
            }

        list.set(PlaybackProfileId("3"), listOf(identity("c", "Channel C")))
        assertNull(list.presentationFor(profile, "a"))
        assertEquals("c", list.presentationFor(PlaybackProfileId("3"), "c")?.contentId?.value)
    }

    @Test
    fun `publishing requires a positive numeric playback profile`() {
        listOf(PlaybackProfileId("0"), PlaybackProfileId("-1"), PlaybackProfileId("profile"))
            .forEach { invalidProfile ->
                assertTrue(
                    runCatching {
                        XtreamLivePlaylist().set(invalidProfile, listOf(identity("a", "A")))
                    }.isFailure,
                )
            }
    }

    @Test
    fun `publisher list mutation cannot alter the accepted snapshot`() {
        val identities = mutableListOf(identity("a", "Channel A"))
        val list = XtreamLivePlaylist()

        list.set(profile, identities)
        identities.clear()

        assertEquals("a", list.presentationFor(profile, "a")?.contentId?.value)
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
                    list.relativeTo(profile, id, delta)?.contentId?.value,
                )
            }
        }
    }

    private fun identity(
        id: String,
        title: String,
        logo: String? = null,
    ): XtreamLiveChannelIdentity = requireNotNull(
        XtreamLiveChannelIdentity.from(id, title, logo),
    )
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

package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.ui.screens.iptv.GuideHideUndoPolicy.Notice
import com.nuvio.tv.ui.screens.iptv.GuideHideUndoPolicy.Sentence
import com.nuvio.tv.ui.screens.iptv.GuideHideUndoPolicy.Target
import com.nuvio.tv.ui.screens.iptv.GuideHideUndoPolicy.Write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UX36/UX73 on TV: MENU in the Live TV guide used to toggle a channel's hide silently, with no way
 * back but Settings. A hide is now an explicit hide, confirmed with a notice that names where to
 * unhide it and offers Undo; a group hidden from the category column gets the same notice.
 */
class GuideHideUndoPolicyTest {

    @Test
    fun `MENU on a channel hides it and confirms with an undoable notice naming its playlist`() {
        val notice = GuideHideUndoPolicy.onChannelMenu(
            entityId = "fp:1:abc", playlistId = "pl", alreadyHidden = false,
            name = "BBC One", playlistName = "Home",
        )
        assertEquals(
            "a hide is confirmed with a notice",
            Notice(Target.Channel("fp:1:abc", "pl"), name = "BBC One", playlistName = "Home"),
            notice,
        )
        assertEquals("the notice names the playlist to unhide it in", Sentence.WITH_PLAYLIST, notice!!.sentence)
        assertEquals("the hide is an explicit hide, never a toggle", Write(Target.Channel("fp:1:abc", "pl"), hidden = true), GuideHideUndoPolicy.hideWrite(notice))
    }

    @Test
    fun `MENU on a channel that is already hidden writes nothing and says nothing`() {
        // The old toggle would have UN-hidden it here, silently.
        assertNull(
            "an already-hidden channel is not toggled back",
            GuideHideUndoPolicy.onChannelMenu("fp:1:abc", "pl", alreadyHidden = true, name = "BBC One", playlistName = "Home"),
        )
    }

    @Test
    fun `MENU on a row with no channel identity does not hide anything`() {
        // Favorites and Recent rows carry no identity; the old toggle wrote a hide keyed on "".
        assertNull("blank identity", GuideHideUndoPolicy.onChannelMenu("", "pl", alreadyHidden = false, name = "BBC One", playlistName = "Home"))
        assertNull("whitespace identity", GuideHideUndoPolicy.onChannelMenu("  ", "pl", alreadyHidden = false, name = "BBC One", playlistName = "Home"))
    }

    @Test
    fun `Undo puts the same channel or group back`() {
        val channel = Notice(Target.Channel("fp:1:abc", "pl"), "BBC One", "Home")
        assertEquals("channel undo", Write(channel.target, hidden = false), GuideHideUndoPolicy.undoWrite(channel))
        val group = GuideHideUndoPolicy.onGroupHidden(Target.Group("pl", "live", "cat-key"), name = "Sports", playlistName = "Home")
        assertEquals("group notice", Notice(Target.Group("pl", "live", "cat-key"), "Sports", "Home"), group)
        assertEquals("group undo", Write(Target.Group("pl", "live", "cat-key"), hidden = false), GuideHideUndoPolicy.undoWrite(group))
    }

    @Test
    fun `without a playlist name the notice falls back to the generic sentence`() {
        listOf(null, "", "  ").forEach { playlistName ->
            assertEquals("playlistName=$playlistName", Sentence.GENERIC, Notice(Target.Channel("e", "pl"), "BBC One", playlistName).sentence)
        }
    }

    @Test
    fun `the playlist is named only when it is the guide's own playlist`() {
        assertEquals("same playlist", "Home", GuideHideUndoPolicy.playlistName(playlistId = "pl", guideAccountId = "pl", guideAccountName = "Home"))
        assertNull("another playlist", GuideHideUndoPolicy.playlistName(playlistId = "other", guideAccountId = "pl", guideAccountName = "Home"))
        assertNull("unknown playlist", GuideHideUndoPolicy.playlistName(playlistId = null, guideAccountId = "pl", guideAccountName = "Home"))
    }
}

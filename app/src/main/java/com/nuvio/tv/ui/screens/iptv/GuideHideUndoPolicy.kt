package com.nuvio.tv.ui.screens.iptv

/**
 * UX36 + UX73 (TV): what a hide in the Live TV guide does and how it is confirmed. MENU on the
 * fullscreen channel used to TOGGLE its hide with no word on screen and no way back but Settings;
 * a group hidden from the category column also vanished with no undo. A hide is now always an
 * explicit hide, confirmed with a notice that names where to unhide it and offers Undo. Pure, so it
 * tests without the overlay store or Compose; the writes go through the existing
 * IptvOverlayRepository (no second store). Mirrors Mobile's IptvChannelQuickActionsPolicy.
 */
object GuideHideUndoPolicy {

    /** How long the notice (and its Undo) stays up — longer than a phone toast for a 10-foot read. */
    const val UNDO_WINDOW_MS = 8_000L

    /** What a hide, and the Undo after it, act on. */
    sealed interface Target {
        /** A channel by its canon-v1 identity and its playlist. */
        data class Channel(val entityId: String, val playlistId: String?) : Target

        /** A provider category by its overlay key. */
        data class Group(val playlistId: String, val contentType: String, val categoryKey: String) : Target
    }

    /** Which sentence the notice uses: with the playlist step in the Settings path, or without it. */
    enum class Sentence { WITH_PLAYLIST, GENERIC }

    /** The confirmation shown after a hide; [name] is what was hidden, as the viewer saw it. */
    data class Notice(val target: Target, val name: String, val playlistName: String?) {
        val sentence: Sentence
            get() = if (playlistName.isNullOrBlank()) Sentence.GENERIC else Sentence.WITH_PLAYLIST
    }

    /** One overlay write: [target] set to [hidden]. Always explicit, never a toggle. */
    data class Write(val target: Target, val hidden: Boolean)

    /**
     * MENU on a channel. Null = do nothing: the row has no identity to key a hide on (the synthetic
     * Favorites / Recent rows — the old toggle wrote a hide keyed on ""), or the channel is already
     * hidden (the old toggle silently un-hid it).
     */
    fun onChannelMenu(
        entityId: String,
        playlistId: String?,
        alreadyHidden: Boolean,
        name: String,
        playlistName: String?,
    ): Notice? {
        if (entityId.isBlank() || alreadyHidden) return null
        return Notice(Target.Channel(entityId, playlistId), name, playlistName)
    }

    /** A group the viewer confirmed hiding from the category column. */
    fun onGroupHidden(target: Target.Group, name: String, playlistName: String?): Notice =
        Notice(target, name, playlistName)

    fun hideWrite(notice: Notice): Write = Write(notice.target, hidden = true)

    fun undoWrite(notice: Notice): Write = Write(notice.target, hidden = false)

    /**
     * The playlist to name in the notice: the guide's own playlist when the hidden item belongs to
     * it. Anything else (an unknown or foreign playlist) gets the generic sentence rather than a
     * wrong name.
     */
    fun playlistName(playlistId: String?, guideAccountId: String?, guideAccountName: String?): String? =
        guideAccountName?.takeIf { playlistId != null && playlistId == guideAccountId && it.isNotBlank() }
}

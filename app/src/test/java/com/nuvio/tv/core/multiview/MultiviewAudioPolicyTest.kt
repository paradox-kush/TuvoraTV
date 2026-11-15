package com.nuvio.tv.core.multiview

import com.nuvio.tv.core.multiview.TileStatus.EMPTY
import com.nuvio.tv.core.multiview.TileStatus.FAILED
import com.nuvio.tv.core.multiview.TileStatus.LOADING
import com.nuvio.tv.core.multiview.TileStatus.PLAYING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiviewAudioPolicyTest {
    private fun state(
        vararg statuses: TileStatus,
        focused: Int? = 0,
        mode: AudioMode = AudioMode.FollowFocus,
        last: Int? = null,
        handoff: Int? = null,
    ) = AudioState(statuses.mapIndexed { i, s -> AudioTile(i, s) }, focused, mode, last, handoff)

    @Test
    fun `follow focus plays the focused tile`() {
        assertEquals(1, MultiviewAudioPolicy.audible(state(PLAYING, PLAYING, focused = 1)))
    }

    @Test
    fun `a pin survives focus moves`() {
        val pinned = AudioMode.Pinned(0)
        assertEquals(0, MultiviewAudioPolicy.audible(state(PLAYING, PLAYING, focused = 1, mode = pinned)))
        assertEquals(0, MultiviewAudioPolicy.audible(state(PLAYING, PLAYING, focused = 0, mode = pinned)))
    }

    @Test
    fun `a loading focused tile is not audible and the previous tile keeps sound`() {
        assertEquals(0, MultiviewAudioPolicy.audible(state(PLAYING, LOADING, focused = 1, last = 0)))
    }

    @Test
    fun `a failed focused tile is not audible and the previous tile keeps sound`() {
        assertEquals(0, MultiviewAudioPolicy.audible(state(PLAYING, FAILED, focused = 1, last = 0)))
    }

    @Test
    fun `a loading pinned tile does not steal sound`() {
        assertEquals(1, MultiviewAudioPolicy.audible(state(LOADING, PLAYING, focused = 1, mode = AudioMode.Pinned(0), last = 1)))
    }

    @Test
    fun `nothing is audible when nothing plays`() {
        assertNull(MultiviewAudioPolicy.audible(state(LOADING, FAILED, EMPTY, focused = 0)))
    }

    @Test
    fun `no previous tile and a loading focused tile means silence`() {
        assertNull(MultiviewAudioPolicy.audible(state(LOADING, PLAYING, focused = 0)))
    }

    @Test
    fun `when the audible tile itself fails sound goes to the next playing tile in reading order`() {
        assertEquals(2, MultiviewAudioPolicy.audible(state(PLAYING, FAILED, PLAYING, focused = 1, last = 1)))
        assertEquals("wraps to the first", 0, MultiviewAudioPolicy.audible(state(PLAYING, PLAYING, FAILED, focused = 2, last = 2)))
    }

    @Test
    fun `after the audible tile is removed the slot's new occupant is next in reading order`() {
        // tile 1 (audible) was removed, the old tile 2 now sits at 1; focus tile 0 is still loading.
        assertEquals(1, MultiviewAudioPolicy.audible(state(LOADING, PLAYING, PLAYING, focused = 0, last = null, handoff = 1)))
        assertEquals("wraps when removed last", 0, MultiviewAudioPolicy.audible(state(PLAYING, PLAYING, focused = null, last = null, handoff = 2)))
    }

    @Test
    fun `a playing focused tile wins over a pending hand-off`() {
        assertEquals(0, MultiviewAudioPolicy.audible(state(PLAYING, PLAYING, focused = 0, handoff = 1)))
    }

    @Test
    fun `removing the pinned tile clears the pin`() {
        assertEquals(AudioMode.FollowFocus, MultiviewAudioPolicy.afterRemoval(AudioMode.Pinned(1), 1))
    }

    @Test
    fun `removing an earlier tile shifts the pin down and a later one leaves it`() {
        assertEquals(AudioMode.Pinned(1), MultiviewAudioPolicy.afterRemoval(AudioMode.Pinned(2), 0))
        assertEquals(AudioMode.Pinned(1), MultiviewAudioPolicy.afterRemoval(AudioMode.Pinned(1), 2))
        assertEquals(AudioMode.FollowFocus, MultiviewAudioPolicy.afterRemoval(AudioMode.FollowFocus, 0))
    }

    @Test
    fun `clearing the pinned slot or shrinking past it clears the pin`() {
        assertEquals(AudioMode.FollowFocus, MultiviewAudioPolicy.afterClear(AudioMode.Pinned(2), 2))
        assertEquals(AudioMode.Pinned(2), MultiviewAudioPolicy.afterClear(AudioMode.Pinned(2), 1))
        assertEquals(AudioMode.FollowFocus, MultiviewAudioPolicy.afterShrink(AudioMode.Pinned(3), 3))
        assertEquals(AudioMode.Pinned(2), MultiviewAudioPolicy.afterShrink(AudioMode.Pinned(2), 3))
    }
}

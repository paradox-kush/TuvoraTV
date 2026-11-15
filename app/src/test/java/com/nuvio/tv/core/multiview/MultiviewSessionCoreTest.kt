package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewSessionCoreTest {
    private data class Ch(override val key: String, val rev: Int = 0) : MultiviewChannel

    /** Shared, ordered record of every call that matters for sound. */
    private val log = ArrayList<String>()

    private inner class FakePlayer(val slot: Int, val changed: () -> Unit) : MultiviewPanePlayer<Ch> {
        override var playback = MultiviewPanePlayback()
        var heard = false
        var playingFlag = false
        var released = false
        var prepared: Ch? = null
        override fun prepare(channel: Ch) { prepared = channel; log += "p$slot.prepare(${channel.key})" }
        override fun setAudible(audible: Boolean) { this.heard = audible; log += "p$slot.audible=$audible" }
        override fun setPlaying(playing: Boolean) { playingFlag = playing }
        override fun release() { released = true; log += "p$slot.release" }
        fun emit(p: MultiviewPanePlayback) { playback = p; changed() }
        fun play() = emit(MultiviewPanePlayback(isPlaying = true))
        fun fail(f: TileFailure = TileFailure.NoData) = emit(MultiviewPanePlayback(failure = f))
    }

    private inner class FakeFocus(var grant: Boolean = true) : MultiviewAudioFocus {
        var requests = 0
        var abandons = 0
        var callback: ((Boolean) -> Unit)? = null
        override fun request(onFocusChanged: (Boolean) -> Unit): Boolean {
            requests++; callback = onFocusChanged; log += "focus.request"; return grant
        }
        override fun abandon() { abandons++; log += "focus.abandon" }
    }

    private val created = ArrayList<FakePlayer>()
    private var changes = 0
    private var allowed: (Ch) -> Boolean = { true }
    private var failCreate = false
    private val focus = FakeFocus()

    private fun core(layout: MultiviewLayout = MultiviewLayout.GRID, count: Int = layout.selectTiles) =
        MultiviewSessionCore<Ch>(
            initialLayout = layout,
            initialTileCount = count,
            totalBufferBytes = 24 * 1024 * 1024,
            audioFocus = focus,
            createPlayer = { slot, _, changed ->
                if (failCreate) error("no decoder")
                FakePlayer(slot, changed).also { created += it }
            },
            onChanged = { changes++ },
            mayPlayChannel = { allowed(it) },
        )

    private fun MultiviewSessionCore<Ch>.fill(n: Int): MultiviewSessionCore<Ch> {
        for (i in 0 until n) assignChannel(i, Ch("c$i"))
        return this
    }

    private fun MultiviewSessionCore<Ch>.p(i: Int) = playerAt(i) as FakePlayer

    private fun audibleSlots(core: MultiviewSessionCore<Ch>, n: Int = 4) =
        (0 until n).filter { (core.playerAt(it) as FakePlayer?)?.heard == true }

    private fun started(layout: MultiviewLayout = MultiviewLayout.GRID, n: Int = 4): MultiviewSessionCore<Ch> {
        val c = core(layout, n).fill(n)
        c.onForeground()
        for (i in 0 until n) c.p(i).play()
        return c
    }

    // ---- assign / clear / remove ----

    @Test
    fun `assign in the background keeps the channel but starts no player`() {
        val c = core().fill(2)
        assertEquals("channel", Ch("c0"), c.channelAt(0))
        assertNull("no player until foreground", c.playerAt(0))
        assertTrue("no player created", created.isEmpty())
    }

    @Test
    fun `foreground prepares every assigned pane and asks for audio focus once`() {
        val c = core().fill(3)
        c.onForeground()
        assertEquals("players", 3, created.size)
        assertEquals("prepared channels", listOf("c0", "c1", "c2"), created.map { it.prepared?.key })
        assertEquals("one focus request", 1, focus.requests)
    }

    @Test
    fun `players are constructed silent and not playing before prepare`() {
        val c = core().fill(1)
        c.onForeground()
        val i = log.indexOf("p0.prepare(c0)")
        assertTrue("silent before prepare: $log", log.subList(0, i).contains("p0.audible=false"))
    }

    @Test
    fun `assigning to a pane replaces only that pane`() {
        val c = started()
        val others = (1..3).map { c.p(it) }
        val old = c.p(0)
        c.assignChannel(0, Ch("new"))
        assertTrue("old released", old.released)
        assertEquals("new prepared", "new", c.p(0).prepared?.key)
        (1..3).forEach { assertSame("pane $it untouched", others[it - 1], c.p(it)) }
    }

    @Test
    fun `assigning the same channel again does nothing`() {
        val c = started()
        val before = created.size
        c.assignChannel(0, Ch("c0"))
        assertEquals(before, created.size)
    }

    @Test
    fun `clearing a pane releases it and leaves the rest`() {
        val c = started()
        val keep = c.p(1)
        val gone = c.p(0)
        c.clearPane(0)
        assertTrue(gone.released)
        assertNull(c.channelAt(0))
        assertNull(c.playerAt(0))
        assertSame(keep, c.p(1))
    }

    @Test
    fun `removing a pane shifts the others down without restarting them`() {
        val c = started(MultiviewLayout.GRID, 4)
        val p0 = c.p(0); val p1 = c.p(1); val p2 = c.p(2); val p3 = c.p(3)
        val before = created.size
        c.removePane(1)
        assertTrue("removed released", p1.released)
        assertSame("0 stays", p0, c.p(0))
        assertSame("2 moved to 1", p2, c.p(1))
        assertSame("3 moved to 2", p3, c.p(2))
        assertNull("last slot empty", c.playerAt(3))
        assertFalse("moved player not released", p2.released || p3.released)
        assertEquals("no new players", before, created.size)
        assertEquals("channels shift", listOf("c0", "c2", "c3"), (0..2).map { c.channelAt(it)?.key })
        assertEquals("pane count", 3, c.paneCount)
        assertEquals("grid of three keeps its layout", MultiviewLayout.GRID, c.layout)
    }

    @Test
    fun `removing down to two panes falls back to a layout that holds two`() {
        val c = started(MultiviewLayout.GRID, 3)
        c.removePane(2)
        assertEquals(2, c.paneCount)
        assertEquals(MultiviewLayout.SIDE_BY_SIDE, c.layout)
    }

    @Test
    fun `one big holds two panes so removing down to two keeps it`() {
        val c = started(MultiviewLayout.FOCUS, 3)
        c.removePane(2)
        assertEquals(2, c.paneCount)
        assertEquals(MultiviewLayout.FOCUS, c.layout)
    }

    @Test
    fun `the last pane cannot be removed`() {
        val c = started(MultiviewLayout.SINGLE, 1)
        c.removePane(0)
        assertEquals(1, c.paneCount)
        assertNotNull(c.playerAt(0))
    }

    @Test
    fun `removing the focused pane moves focus to the pane that took its place`() {
        val c = started()
        c.focusPane(1)
        c.removePane(1)
        assertEquals(1, c.focusedPane)
        c.focusPane(2)
        c.removePane(0)
        assertEquals("focus follows its pane down", 1, c.focusedPane)
    }

    // ---- sound ----

    @Test
    fun `only the focused playing pane is audible`() {
        val c = started()
        assertEquals(listOf(0), audibleSlots(c))
        c.focusPane(2)
        assertEquals(listOf(2), audibleSlots(c))
    }

    @Test
    fun `focus change mutes every pane before unmuting the new one`() {
        val c = started()
        log.clear()
        c.focusPane(3)
        val audibleEvents = log.filter { it.contains(".audible=") }
        val unmute = audibleEvents.indexOf("p3.audible=true")
        assertTrue("unmute present: $audibleEvents", unmute >= 0)
        assertTrue("unmute is last: $audibleEvents", unmute == audibleEvents.lastIndex)
        assertEquals("exactly one unmute", 1, audibleEvents.count { it.endsWith("=true") })
        assertTrue("old pane muted first", audibleEvents.indexOf("p0.audible=false") < unmute)
    }

    @Test
    fun `at no point in the log are two panes audible`() {
        val c = started()
        c.focusPane(1); c.focusPane(2); c.pinAudio(3); c.focusPane(0); c.removePane(3); c.unpinAudio()
        val on = HashSet<Int>()
        for (e in log) {
            val m = Regex("p(\\d)\\.audible=(true|false)").matchEntire(e) ?: continue
            val slot = m.groupValues[1].toInt()
            if (m.groupValues[2] == "true") on += slot else on -= slot
            assertTrue("two audible at once after $e: $on", on.size <= 1)
        }
    }

    @Test
    fun `a pane that is still loading is not audible and the sound stays put`() {
        val c = core().fill(2)
        c.onForeground()
        c.p(0).play()
        c.focusPane(1)
        assertEquals("pane 1 still loading, pane 0 keeps sound", listOf(0), audibleSlots(c))
        c.p(1).play()
        assertEquals("pane 1 playing now takes it", listOf(1), audibleSlots(c))
    }

    @Test
    fun `a failed pane is not audible`() {
        val c = started()
        c.focusPane(1)
        c.p(1).fail()
        assertEquals("failed pane silent, sound moves on in reading order", listOf(2), audibleSlots(c))
        assertFalse(c.p(1).heard)
    }

    @Test
    fun `when the audible pane fails sound moves to the next playing pane`() {
        val c = started()
        c.p(0).fail()
        assertEquals(listOf(1), audibleSlots(c))
    }

    @Test
    fun `pin survives focusPane`() {
        val c = started()
        c.pinAudio(2)
        c.focusPane(0)
        c.focusPane(3)
        assertEquals(listOf(2), audibleSlots(c))
        assertEquals(AudioMode.Pinned(2), c.audioMode)
        c.unpinAudio()
        assertEquals("sound follows focus again", listOf(3), audibleSlots(c))
    }

    @Test
    fun `removing the pinned pane clears the pin and sound follows focus`() {
        val c = started()
        c.pinAudio(2)
        c.removePane(2)
        assertEquals(AudioMode.FollowFocus, c.audioMode)
        assertEquals(listOf(c.focusedPane), audibleSlots(c, 3))
    }

    @Test
    fun `removing a pane before the pinned one moves the pin with it`() {
        val c = started()
        c.pinAudio(3)
        c.removePane(0)
        assertEquals(AudioMode.Pinned(2), c.audioMode)
        assertEquals(listOf(2), audibleSlots(c, 3))
    }

    @Test
    fun `clearing the pinned pane clears the pin`() {
        val c = started()
        c.pinAudio(1)
        c.clearPane(1)
        assertEquals(AudioMode.FollowFocus, c.audioMode)
    }

    @Test
    fun `shrinking the layout past the pinned pane clears the pin`() {
        val c = started()
        c.pinAudio(3)
        c.setLayout(MultiviewLayout.SIDE_BY_SIDE)
        assertEquals(AudioMode.FollowFocus, c.audioMode)
        assertEquals(2, c.paneCount)
        assertTrue("dropped panes released", created.filter { it.slot >= 2 }.all { it.released })
    }

    @Test
    fun `removing the audible pane hands sound to the next playing pane when focus is loading`() {
        val c = core().fill(3)
        c.onForeground()
        c.p(0).play(); c.p(1).play(); c.p(2).play()
        c.focusPane(0)
        c.pinAudio(1)          // pinned pane 1 is audible
        c.unpinAudio()
        c.focusPane(1)
        c.assignChannel(0, Ch("again")) // pane 0 reloads: loading, not playing
        c.removePane(1)        // audible pane removed; pane 2 now sits in slot 1
        assertEquals("sound goes to the pane that took the slot", listOf(1), audibleSlots(c, 2))
    }

    @Test
    fun `pinning a hidden pane is ignored`() {
        val c = started(MultiviewLayout.SIDE_BY_SIDE, 2)
        c.pinAudio(3)
        assertEquals(AudioMode.FollowFocus, c.audioMode)
    }

    // ---- audio focus ----

    @Test
    fun `focus denied means nothing plays and nothing is audible`() {
        focus.grant = false
        val c = core().fill(2)
        c.onForeground()
        c.p(0).play(); c.p(1).play()
        assertFalse(c.hasAudioFocus)
        assertTrue("no player told to play", created.none { it.playingFlag })
        assertEquals(emptyList<Int>(), audibleSlots(c))
    }

    @Test
    fun `focus granted later starts playback and sound`() {
        focus.grant = false
        val c = core().fill(2)
        c.onForeground()
        c.p(0).play()
        focus.callback!!.invoke(true)
        assertTrue(created.all { it.playingFlag })
        assertEquals(listOf(0), audibleSlots(c))
    }

    @Test
    fun `focus lost mutes and pauses everything`() {
        val c = started()
        focus.callback!!.invoke(false)
        assertTrue(created.none { it.playingFlag })
        assertEquals(emptyList<Int>(), audibleSlots(c))
    }

    @Test
    fun `a stale focus callback after abandon is ignored`() {
        val c = started()
        val stale = focus.callback!!
        c.onBackground()
        stale(true)
        assertFalse(c.hasAudioFocus)
        assertTrue(created.none { it.playingFlag })
    }

    @Test
    fun `only one audio focus request while foreground`() {
        val c = started()
        c.focusPane(1); c.assignChannel(2, Ch("x")); c.removePane(3)
        assertEquals(1, focus.requests)
    }

    // ---- lifecycle ----

    @Test
    fun `background releases every player and abandons focus`() {
        val c = started()
        c.onBackground()
        assertTrue("all released", created.all { it.released })
        assertTrue((0..3).all { c.playerAt(it) == null })
        assertEquals("focus abandoned", 1, focus.abandons)
        assertFalse(c.hasAudioFocus)
        assertEquals("selection kept", Ch("c2"), c.channelAt(2))
    }

    @Test
    fun `foreground after background prepares fresh players for the kept channels`() {
        val c = started()
        c.onBackground()
        val before = created.size
        c.onForeground()
        assertEquals(4, created.size - before)
        assertEquals(2, focus.requests)
        c.p(0).play()
        assertEquals(listOf(0), audibleSlots(c))
    }

    @Test
    fun `release drops everything and ignores later calls`() {
        val c = started()
        c.release()
        assertTrue(created.all { it.released })
        assertNull(c.channelAt(0))
        val n = created.size
        c.onForeground(); c.assignChannel(0, Ch("z")); c.focusPane(1)
        assertEquals(n, created.size)
    }

    @Test
    fun `retry recreates only that pane`() {
        val c = started()
        val others = listOf(c.p(0), c.p(2), c.p(3))
        val old = c.p(1)
        old.fail()
        c.retryPane(1)
        assertTrue(old.released)
        assertTrue("new player", c.p(1) !== old)
        assertEquals("c1", c.p(1).prepared?.key)
        assertSame(others[0], c.p(0)); assertSame(others[1], c.p(2)); assertSame(others[2], c.p(3))
        assertEquals("failure cleared", null, c.playbackAt(1).failure)
    }

    @Test
    fun `retry on an empty pane does nothing`() {
        val c = started(MultiviewLayout.GRID, 4)
        c.clearPane(2)
        val n = created.size
        c.retryPane(2)
        assertEquals(n, created.size)
    }

    @Test
    fun `a player that cannot be created shows an honest generic failure and does not crash`() {
        failCreate = true
        val c = core().fill(1)
        c.onForeground()
        assertNull(c.playerAt(0))
        assertEquals("not blamed on the decoder", TileFailure.Other, c.playbackAt(0).failure)
        failCreate = false
        c.retryPane(0)
        assertNotNull(c.playerAt(0))
        assertNull(c.playbackAt(0).failure)
    }

    @Test
    fun `player failure is exposed as a typed failure`() {
        val c = started()
        c.p(2).fail(TileFailure.ProviderRefused)
        assertEquals(TileFailure.ProviderRefused, c.playbackAt(2).failure)
    }

    // ---- permissions ----

    @Test
    fun `validateAllowedChannels drops a pane whose channel is no longer allowed and keeps the rest`() {
        val c = started()
        val keep = c.p(0)
        c.validateAllowedChannels(listOf(Ch("c0"), Ch("c2"), Ch("c3")))
        assertNull(c.channelAt(1))
        assertTrue(created.first { it.slot == 1 }.released)
        assertSame(keep, c.p(0))
    }

    @Test
    fun `validateAllowedChannels restarts a pane whose channel record changed`() {
        val c = started()
        val old = c.p(1)
        c.validateAllowedChannels((0..3).map { Ch("c$it", rev = if (it == 1) 5 else 0) })
        assertTrue(old.released)
        assertEquals(5, c.p(1).prepared?.rev)
    }

    @Test
    fun `a channel the profile may not play is not prepared`() {
        allowed = { it.key != "c1" }
        val c = core().fill(3)
        c.onForeground()
        assertNull(c.channelAt(1))
        assertEquals(2, created.size)
    }

    // ---- layout ----

    @Test
    fun `setLayout to a bigger count adds empty slots and to a smaller one releases the extras`() {
        val c = started(MultiviewLayout.SIDE_BY_SIDE, 2)
        c.setLayout(MultiviewLayout.GRID)
        assertEquals(4, c.paneCount)
        assertNull(c.channelAt(3))
        c.assignChannel(3, Ch("n"))
        c.setLayout(MultiviewLayout.SIDE_BY_SIDE)
        assertEquals(2, c.paneCount)
        assertNull(c.channelAt(3))
        assertTrue(created.first { it.prepared?.key == "n" }.released)
    }

    @Test
    fun `changing arrangement at the same count keeps every player`() {
        val c = started(MultiviewLayout.SIDE_BY_SIDE, 2)
        val a = c.p(0); val b = c.p(1)
        c.setLayout(MultiviewLayout.TWO_STACKED)
        assertSame(a, c.p(0)); assertSame(b, c.p(1))
        assertEquals(MultiviewLayout.TWO_STACKED, c.layout)
    }

    @Test
    fun `focus is clamped when the layout shrinks`() {
        val c = started()
        c.focusPane(3)
        c.setLayout(MultiviewLayout.SIDE_BY_SIDE)
        assertEquals(1, c.focusedPane)
    }

    @Test
    fun `bounds follow layout and count`() {
        val c = core(MultiviewLayout.GRID, 3)
        assertEquals(3, c.bounds().size)
    }

    @Test
    fun `onChanged fires for state changes`() {
        val c = core().fill(1)
        val before = changes
        c.onForeground()
        assertTrue(changes > before)
    }
}

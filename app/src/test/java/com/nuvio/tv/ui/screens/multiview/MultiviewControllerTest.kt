package com.nuvio.tv.ui.screens.multiview

import com.nuvio.tv.core.multiview.CodecFacts
import com.nuvio.tv.core.multiview.DecoderCeilingStore
import com.nuvio.tv.core.multiview.DecoderFacts
import com.nuvio.tv.core.multiview.DecoderFactsPort
import com.nuvio.tv.core.multiview.MemoryClass
import com.nuvio.tv.core.multiview.TileCodec
import com.nuvio.tv.core.multiview.MultiviewAudioFocus
import com.nuvio.tv.core.multiview.MultiviewDirection
import com.nuvio.tv.core.multiview.MultiviewLayout
import com.nuvio.tv.core.multiview.MultiviewLayoutPolicy
import com.nuvio.tv.core.multiview.MultiviewNotice
import com.nuvio.tv.core.multiview.TileAction
import com.nuvio.tv.core.multiview.TileProgramme
import com.nuvio.tv.core.multiview.MultiviewGridStore
import com.nuvio.tv.core.multiview.MultiviewPanePlayback
import com.nuvio.tv.core.multiview.MultiviewPanePlayer
import com.nuvio.tv.core.multiview.MultiviewQueue
import com.nuvio.tv.core.multiview.SavedGrid
import com.nuvio.tv.core.multiview.TileFailure
import com.nuvio.tv.core.multiview.TileStatus
import com.nuvio.tv.playback.multiview.TileChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewControllerTest {
    private inner class FakePlayer(val slot: Int, val changed: () -> Unit) : MultiviewPanePlayer<TileChannel> {
        override var playback = MultiviewPanePlayback()
        var heard = false
        var released = false
        var prepared: TileChannel? = null
        val tileIdAtCreate: Int = controller.tileIdAt(slot)
        override fun prepare(channel: TileChannel) { prepared = channel }
        override fun setAudible(audible: Boolean) { heard = audible }
        override fun setPlaying(playing: Boolean) = Unit
        override fun release() { released = true }
        fun emit(p: MultiviewPanePlayback) { playback = p; changed() }
        fun play() = emit(MultiviewPanePlayback(isPlaying = true))
        fun fail(f: TileFailure) = emit(MultiviewPanePlayback(failure = f))
    }

    private class FakeFocus : MultiviewAudioFocus {
        override fun request(onFocusChanged: (Boolean) -> Unit) = true
        override fun abandon() = Unit
    }

    private class FakeStore(var saved: SavedGrid? = null) : MultiviewGridStore {
        var saves = 0
        override suspend fun load() = saved
        override suspend fun save(grid: SavedGrid?) { saved = grid; saves++ }
    }

    private val queue = MultiviewQueue()
    private val store = FakeStore()
    private val created = ArrayList<FakePlayer>()
    private val shown = ArrayList<MultiviewPanePlayer<TileChannel>?>()
    private var allowed: (TileChannel) -> Boolean = { true }
    private lateinit var controller: MultiviewController
    private var facts = DecoderFacts()
    private var memory = MemoryClass.HIGH
    private val nameSource = HashMap<String, String>()
    private val programmeSource = HashMap<String, List<TileProgramme>>()
    private var backupFinder: (String, Set<String>) -> TileChannel? = { _, _ -> null }
    private val ceilingStore = object : DecoderCeilingStore {
        val saved = HashMap<TileCodec, Int>()
        override suspend fun load(): Map<TileCodec, Int> = saved.toMap()
        override suspend fun save(codec: TileCodec, comfortable: Int) { saved[codec] = comfortable }
    }

    private fun controller(clock: () -> Long = { 0L }): MultiviewController = MultiviewController(
        queue = queue,
        store = store,
        scope = CoroutineScope(Dispatchers.Unconfined),
        audioFocus = FakeFocus(),
        createPlayer = { slot, _, changed -> FakePlayer(slot, changed).also { created += it } },
        showAudible = { shown += it },
        mayPlay = { allowed(it) },
        totalBufferBytes = 24 * 1024 * 1024,
        decoderFacts = DecoderFactsPort { facts },
        ceilings = ceilingStore,
        memoryClass = { memory },
        findBackup = { key, tried -> backupFinder(key, tried) },
        nameLookup = { key -> nameSource[key] },
        programmeLookup = { key, _, _ -> programmeSource[key].orEmpty() },
        nowMs = clock,
    ).also { controller = it }

    private fun started(startKey: String? = null, resumed: Boolean = true): MultiviewController {
        val c = controller()
        if (resumed) c.onResume()
        runBlocking { c.start(startKey) }
        return c
    }

    private val MultiviewController.capacity: Int? get() = (state.value.notice as? MultiviewNotice.Capacity)?.about
    private fun MultiviewController.tile(slot: Int) = state.value.tiles.first { it.slot == slot }
    private fun player(slot: Int, from: Int = 0) = created.drop(from).last { it.slot == slot && !it.released }

    // ---- entry: queue, start channel, resume last grid ----

    @Test
    fun `the start channel is tile 0 and queued channels follow`() {
        queue.add("q1"); queue.add("q2")
        val c = started(startKey = "now")
        assertEquals("keys", listOf("now", "q1", "q2"), c.state.value.tiles.map { it.channelKey })
        assertEquals("tiles", 3, c.state.value.tileCount)
        assertEquals("a new grid of three starts as Same size", MultiviewLayout.GRID, c.state.value.layout)
        assertTrue("the queue was taken", queue.take().isEmpty())
    }

    @Test
    fun `a single start channel gets a second empty tile to add to`() {
        val c = started(startKey = "now")
        assertEquals(2, c.state.value.tileCount)
        assertEquals("tile 0", "now", c.tile(0).channelKey)
        assertEquals("tile 1 is empty", TileStatus.EMPTY, c.tile(1).status)
        assertEquals("only one player", 1, created.size)
    }

    @Test
    fun `nothing queued restores the last grid`() {
        store.saved = SavedGrid(MultiviewLayout.GRID, listOf("a", null, "c", "d"))
        val c = started()
        assertEquals(MultiviewLayout.GRID, c.state.value.layout)
        assertEquals(listOf("a", null, "c", "d"), c.state.value.tiles.map { it.channelKey })
        assertEquals("a player per channel", 3, created.size)
    }

    @Test
    fun `what opened is remembered even if nothing changes`() {
        queue.add("x"); queue.add("y")
        started()
        assertEquals(SavedGrid(MultiviewLayout.SIDE_BY_SIDE, listOf("x", "y")), store.saved)
    }

    @Test
    fun `queued channels win over the remembered grid`() {
        store.saved = SavedGrid(MultiviewLayout.GRID, listOf("a", "b", "c", "d"))
        queue.add("x"); queue.add("y")
        val c = started()
        assertEquals(listOf("x", "y"), c.state.value.tiles.map { it.channelKey })
    }

    @Test
    fun `nothing queued and nothing remembered opens two empty tiles`() {
        val c = started()
        assertEquals(MultiviewLayout.SIDE_BY_SIDE, c.state.value.layout)
        assertTrue(c.state.value.tiles.all { it.status == TileStatus.EMPTY })
        assertTrue("no player yet", created.isEmpty())
    }

    @Test
    fun `a channel this profile may not watch is left out`() {
        allowed = { it.key != "blocked" }
        val c = started(startKey = "blocked")
        assertNull(c.tile(0).channelKey)
        assertTrue(created.isEmpty())
    }

    // ---- names, resume and the label rule ----

    @Test
    fun `a restored tile shows its saved name before anything has loaded`() {
        store.saved = SavedGrid(MultiviewLayout.SIDE_BY_SIDE, listOf("a", "b"), listOf("XC1 Archive Alpha", "XC2 Archive Beta"))
        val c = started()
        assertEquals("XC1 Archive Alpha", c.tile(0).name)
        assertEquals("XC2 Archive Beta", c.tile(1).name)
        assertEquals("still loading, but named", TileStatus.LOADING, c.tile(0).status)
    }

    @Test
    fun `the live lookup replaces the saved name`() {
        store.saved = SavedGrid(MultiviewLayout.SIDE_BY_SIDE, listOf("a", null), listOf("Old Name", null))
        nameSource["a"] = "New Name"
        val c = started()
        assertEquals("New Name", c.tile(0).name)
        assertEquals("and that is what is remembered", "New Name", store.saved?.names?.get(0))
    }

    @Test
    fun `a name the lookup finds names a tile that came from the queue`() {
        nameSource["q"] = "Queued Channel"
        queue.add("q")
        val c = started(startKey = "a")
        assertEquals("Queued Channel", c.tile(1).name)
        assertNull("a channel nothing knows stays unnamed until it plays", c.tile(0).name)
    }

    @Test
    fun `a failed tile keeps the name it was saved with`() {
        store.saved = SavedGrid(MultiviewLayout.SIDE_BY_SIDE, listOf("gone", null), listOf("Sky Sports", null))
        val c = started()
        player(0).fail(TileFailure.ChannelUnavailable)
        assertEquals("Sky Sports", c.tile(0).name)
        assertEquals(TileStatus.FAILED, c.tile(0).status)
    }

    @Test
    fun `line two is the programme airing now read at draw time and absent when there is none`() {
        var now = 1_000L
        programmeSource["a"] = listOf(TileProgramme("News", 0, 5_000), TileProgramme("Film", 5_000, 9_000))
        val c = controller { now }
        c.onResume()
        runBlocking { c.start("a") }
        assertEquals("News", c.tile(0).nowPlaying)
        assertNull("a tile whose channel has no guide data has no line two", c.tile(1).nowPlaying)
        now = 6_000
        c.focus(0); c.focus(1)
        assertEquals("the programme that has started, never the old one", "Film", c.tile(0).nowPlaying)
        now = 20_000
        c.focus(0)
        assertNull("nothing airs: absent, not stale", c.tile(0).nowPlaying)
    }

    @Test
    fun `the name of a pick is kept with the key even when the lookup knows nothing`() {
        val c = started(startKey = "a")
        c.assign(1, ChannelPick("b", "Picked B"))
        assertEquals(listOf(null, "Picked B"), store.saved?.names)
    }

    // ---- a guide channel id is a valid multiview channel ----

    @Test
    fun `a content id shaped like the guide's live channel id is queued and survives the allowed channel check`() {
        val accountId = "http://host.example:8080|someone"
        val id = com.nuvio.tv.core.iptv.XtreamItemRegistry.liveId(accountId, 4242)
        assertTrue("the guide's id is a live content id", com.nuvio.tv.core.iptv.XtreamItemRegistry.isLiveContentId(id))
        assertEquals(com.nuvio.tv.core.multiview.QueueResult.Added(1), queue.add(id))
        val prefix = com.nuvio.tv.core.iptv.XtreamItemRegistry.accountPrefix(accountId)
        allowed = { it.key.startsWith(prefix) }
        val c = started()
        assertEquals("the channel took tile 0", id, c.tile(0).channelKey)
        assertEquals("a player opened for it", id, created.single().prepared?.key)
        c.assign(1, ChannelPick(com.nuvio.tv.core.iptv.XtreamItemRegistry.liveId("other", 1), "Other"))
        assertNull("a channel of another playlist is still refused", c.tile(1).channelKey)
    }

    // ---- leaving ----

    @Test
    fun `leaving with two channels takes a second back and says so`() {
        var now = 0L
        queue.add("b")
        val c = controller { now }
        c.onResume()
        runBlocking { c.start("a") }
        assertFalse("first back only arms", c.requestLeave())
        assertEquals(MultiviewNotice.LeaveGuard, c.state.value.notice)
        now = 2_000
        assertTrue("second back inside 3.5 s leaves", c.requestLeave())
    }

    @Test
    fun `a back handled late still counts from when it was pressed`() {
        // The main thread was busy redrawing three tiles: the second key is handled 6 s after the first was, but the
        // viewer pressed it 0.5 s after (the input clock). It must leave, not arm again.
        var handledAt = 0L
        queue.add("b")
        val c = controller { handledAt }
        c.onResume()
        runBlocking { c.start("a") }
        assertFalse("first back only arms", c.requestLeave(atMs = 10_000))
        handledAt = 16_000
        assertTrue("second back pressed within 3.5 s of the first leaves", c.requestLeave(atMs = 10_500))
    }

    @Test
    fun `a back pressed after the window arms again`() {
        var handledAt = 0L
        queue.add("b")
        val c = controller { handledAt }
        c.onResume()
        runBlocking { c.start("a") }
        assertFalse(c.requestLeave(atMs = 10_000))
        assertFalse("4 s later is a new first back", c.requestLeave(atMs = 14_000))
    }

    @Test
    fun `moving the highlight cancels the armed back`() {
        var now = 0L
        queue.add("b")
        val c = controller { now }
        c.onResume()
        runBlocking { c.start("a") }
        c.requestLeave()
        c.move(MultiviewDirection.RIGHT)
        assertNull("the notice goes with it", c.state.value.notice)
        now = 100
        assertFalse("armed afresh", c.requestLeave())
    }

    @Test
    fun `one channel leaves at once`() {
        val c = started(startKey = "a")
        assertTrue(c.requestLeave())
    }

    @Test
    fun `back from a grid opened from the player returns to the channel being heard`() {
        queue.add("b")
        val c = started(startKey = "a")
        player(0).play(); player(1).play()
        c.focus(1)
        assertEquals("b", c.audibleChannelKey())
        c.pinAudio(0)
        assertEquals("the kept tile", "a", c.audibleChannelKey())
    }

    // ---- lifecycle ----

    @Test
    fun `tiles open on resume and not before`() {
        val c = started(startKey = "a", resumed = false)
        assertTrue("nothing runs before the screen is resumed", created.isEmpty())
        c.onResume()
        assertEquals(1, created.size)
    }

    @Test
    fun `stop closes every tile and resume reopens them on fresh surfaces`() {
        queue.add("b")
        val c = started(startKey = "a")
        val firstIds = c.state.value.tiles.map { it.tileId }
        c.onStop()
        assertTrue("every player released", created.all { it.released })
        c.onResume()
        val reopened = created.filter { !it.released }
        assertEquals("a player per channel again", 2, reopened.size)
        assertTrue("fresh surface ids", c.state.value.tiles.map { it.tileId }.none { it in firstIds })
        assertEquals("a reopened player is built for its tile's current id", c.tile(0).tileId, reopened.first { it.slot == 0 }.tileIdAtCreate)
    }

    @Test
    fun `release frees the players and clears the media session`() {
        val c = started(startKey = "a")
        player(0).play()
        c.release()
        assertTrue(created.all { it.released })
        assertNull("no audible tile is shown", shown.last())
    }

    // ---- sound / media session ----

    @Test
    fun `the media session follows the audible tile after every change`() {
        queue.add("b")
        val c = started(startKey = "a")
        player(0).play()
        player(1).play()
        assertSame("audible tile 0 shown", player(0), shown.last())
        c.focus(1)
        assertSame("sound followed focus", player(1), shown.last())
        c.pinAudio(0)
        c.focus(1)
        assertSame("pinned tile keeps the session", player(0), shown.last())
        assertEquals(0, c.state.value.pinnedSlot)
        assertTrue(c.tile(0).pinned)
        c.unpinAudio()
        assertSame("back to following focus", player(1), shown.last())
    }

    // ---- states ----

    @Test
    fun `a failed tile shows its typed failure and actions`() {
        val c = started(startKey = "a")
        player(0).fail(TileFailure.ProviderRefused)
        assertEquals(TileStatus.FAILED, c.tile(0).status)
        assertEquals(TileFailure.ProviderRefused, c.tile(0).failure)
        assertEquals(TileFailure.ProviderRefused.actions, c.tile(0).failureActions)
        assertTrue("no actions while not failed", c.tile(1).failureActions.isEmpty())
    }

    @Test
    fun `a tile is loading until its first picture then playing`() {
        val c = started(startKey = "a")
        assertEquals(TileStatus.LOADING, c.tile(0).status)
        player(0).play()
        assertEquals(TileStatus.PLAYING, c.tile(0).status)
    }

    // ---- focus / D-pad ----

    @Test
    fun `the d-pad moves focus between tiles and stops at an edge`() {
        queue.add("b")
        val c = started(startKey = "a")
        assertTrue("right from 0", c.move(MultiviewDirection.RIGHT))
        assertEquals(1, c.state.value.focusedSlot)
        assertFalse("right from the last", c.move(MultiviewDirection.RIGHT))
        assertFalse("up has nothing", c.move(MultiviewDirection.UP))
        assertEquals(1, c.state.value.focusedSlot)
        assertTrue(c.tile(1).focused)
    }

    // ---- full screen ----

    @Test
    fun `full screen closes the other tiles and back reopens them`() {
        queue.add("b"); queue.add("c")
        val c = started(startKey = "a")
        val keep = player(1)
        keep.play()
        val before = created.size
        c.enterFullscreen(1)
        assertEquals("one tile shown", listOf(1), c.state.value.tiles.map { it.slot })
        assertEquals(1, c.state.value.fullscreenSlot)
        assertEquals("shown across the whole area", 1f, c.tile(1).rect.width, 0f)
        assertTrue("tile 0 closed", created[0].released)
        assertTrue("tile 2 closed", created[2].released)
        assertFalse("the chosen tile keeps playing, no reconnect", keep.released)
        assertEquals("nothing new opened", before, created.size)

        c.exitFullscreen()
        assertNull(c.state.value.fullscreenSlot)
        assertEquals("three tiles again", listOf(0, 1, 2), c.state.value.tiles.map { it.slot })
        assertEquals("the two closed tiles reconnect", before + 2, created.size)
        assertFalse("the chosen tile still the same player", keep.released)
        assertEquals("keys back in place", listOf("a", "b", "c"), c.state.value.tiles.map { it.channelKey })
    }

    @Test
    fun `reopened tiles get fresh surfaces`() {
        queue.add("b")
        val c = started(startKey = "a")
        val idBefore = c.tile(0).tileId
        c.enterFullscreen(1)
        c.exitFullscreen()
        assertNotEquals(idBefore, c.tile(0).tileId)
        assertEquals(c.tile(0).tileId, created.last { it.slot == 0 }.tileIdAtCreate)
    }

    @Test
    fun `the grid is not remembered as its full screen view`() {
        val c = started(startKey = "a")
        c.assign(1, ChannelPick("b", "B"))
        val saved = store.saved
        assertEquals("the grid as laid out", listOf("a", "b"), saved?.keys)
        c.enterFullscreen(0)
        assertEquals("nothing saved while full screen", saved, store.saved)
        c.exitFullscreen()
        assertEquals("the full grid again", listOf("a", "b"), store.saved?.keys)
    }

    @Test
    fun `layout changes and removals are ignored while a tile is full screen`() {
        queue.add("b")
        val c = started(startKey = "a")
        c.enterFullscreen(0)
        c.setLayout(MultiviewLayoutPolicy.Family.ONE_BIG)
        c.addSlot()
        c.remove(0)
        assertEquals(MultiviewLayout.SIDE_BY_SIDE, c.state.value.layout)
        assertEquals(2, c.state.value.tileCount)
    }

    @Test
    fun `a tile that was closed by full screen says rejoining until it plays`() {
        queue.add("b")
        val c = started(startKey = "a")
        player(0).play(); player(1).play()
        c.enterFullscreen(1)
        c.exitFullscreen()
        assertTrue("the closed tile rejoins", c.tile(0).rejoining)
        assertFalse("the one that never stopped does not", c.tile(1).rejoining)
        created.last { it.slot == 0 }.play()
        assertFalse("playing again", c.tile(0).rejoining)
    }

    // ---- assign / remove / retry / layout ----

    @Test
    fun `assigning fills the slot persists the grid with the name and moves the highlight to the tile`() {
        val c = started(startKey = "a")
        c.assign(1, ChannelPick("b", "Channel B"))
        assertEquals("Channel B", c.tile(1).name)
        assertEquals(1, c.state.value.focusedSlot)
        assertEquals(SavedGrid(MultiviewLayout.SIDE_BY_SIDE, listOf("a", "b"), listOf(null, "Channel B")), store.saved)
    }

    @Test
    fun `a channel already on screen moves focus to its tile instead of opening twice`() {
        queue.add("b")
        val c = started(startKey = "a")
        val opened = created.size
        c.assign(1, ChannelPick("a", "A"))
        assertEquals("focus moved to the tile that has it", 0, c.state.value.focusedSlot)
        assertEquals("no second player", opened, created.size)
        assertEquals("b", c.tile(1).channelKey)
    }

    @Test
    fun `removing a tile keeps the others playing and their surfaces`() {
        queue.add("b"); queue.add("c")
        val c = started(startKey = "a")
        val p0 = player(0); val p2 = player(2)
        val idOfC = c.tile(2).tileId
        c.remove(1)
        assertEquals(listOf("a", "c"), c.state.value.tiles.map { it.channelKey })
        assertFalse("tile 0 untouched", p0.released)
        assertFalse("tile c untouched", p2.released)
        assertEquals("c moved to slot 1 with its own surface", idOfC, c.tile(1).tileId)
        assertEquals(listOf("a", "c"), store.saved?.keys)
    }

    @Test
    fun `with two tiles remove empties the tile because a grid never has fewer than two slots`() {
        queue.add("b")
        val c = started(startKey = "a")
        c.remove(0)
        assertEquals(2, c.state.value.tileCount)
        assertEquals(TileStatus.EMPTY, c.tile(0).status)
        assertEquals("b", c.tile(1).channelKey)
    }

    @Test
    fun `removing the only tile empties it instead`() {
        store.saved = SavedGrid(MultiviewLayout.SINGLE, listOf("a"))
        val c = started()
        c.remove(0)
        assertEquals(1, c.state.value.tileCount)
        assertEquals(TileStatus.EMPTY, c.tile(0).status)
    }

    @Test
    fun `retry recreates only that tile on a fresh surface`() {
        queue.add("b")
        val c = started(startKey = "a")
        val p1 = player(1)
        val idBefore = c.tile(0).tileId
        val before = created.size
        c.retry(0)
        assertTrue("old player released", created[0].released)
        assertEquals("one new player", before + 1, created.size)
        assertFalse("other tile untouched", p1.released)
        assertNotEquals("fresh surface", idBefore, c.tile(0).tileId)
    }

    @Test
    fun `the layout only arranges the tiles that are there and never changes their number`() {
        queue.add("b"); queue.add("c")
        val c = started(startKey = "a")
        val p0 = player(0)
        c.setLayout(MultiviewLayoutPolicy.Family.ONE_BIG)
        assertEquals(3, c.state.value.tileCount)
        assertEquals(MultiviewLayout.FOCUS, c.state.value.layout)
        assertFalse("nothing restarts", p0.released)
        assertEquals(MultiviewLayout.FOCUS, store.saved?.layout)
        c.setLayout(MultiviewLayoutPolicy.Family.SAME_SIZE)
        assertEquals(MultiviewLayout.GRID, c.state.value.layout)
        assertEquals(3, c.state.value.tileCount)
    }

    @Test
    fun `add another channel appends an empty slot in the same layout family`() {
        queue.add("b")
        val c = started(startKey = "a")
        c.setLayout(MultiviewLayoutPolicy.Family.ONE_BIG)
        val p0 = player(0)
        assertEquals("the new slot", 2, c.addSlot())
        assertEquals(3, c.state.value.tileCount)
        assertEquals("One big is kept", MultiviewLayout.FOCUS, c.state.value.layout)
        assertEquals(TileStatus.EMPTY, c.tile(2).status)
        assertEquals(2, c.state.value.pendingSlot)
        assertFalse("the others keep playing", p0.released)
    }

    @Test
    fun `no fifth slot`() {
        queue.add("b"); queue.add("c"); queue.add("d")
        val c = started(startKey = "a")
        assertNull(c.addSlot())
        assertEquals(4, c.state.value.tileCount)
    }

    @Test
    fun `backing out of the picker takes the added slot away again`() {
        queue.add("b")
        val c = started(startKey = "a")
        c.addSlot()
        c.cancelPendingSlot()
        assertEquals("two tiles again, no stray empty one", 2, c.state.value.tileCount)
        assertNull(c.state.value.pendingSlot)
        assertEquals(listOf("a", "b"), store.saved?.keys)
    }

    @Test
    fun `a pick on the added slot keeps it`() {
        queue.add("b")
        val c = started(startKey = "a")
        val slot = c.addSlot()!!
        c.assign(slot, ChannelPick("c", "C"))
        c.cancelPendingSlot()
        assertEquals("three tiles", 3, c.state.value.tileCount)
        assertEquals("c", c.tile(2).channelKey)
        assertNull(c.state.value.pendingSlot)
        assertEquals(2, c.state.value.focusedSlot)
    }

    @Test
    fun `picking a channel that is already on screen drops the added slot and says where it is`() {
        queue.add("b")
        val c = started(startKey = "a")
        val slot = c.addSlot()!!
        c.assign(slot, ChannelPick("a", "A"))
        assertEquals("the added slot is gone", 2, c.state.value.tileCount)
        assertEquals("focus moved to the tile that has it", 0, c.state.value.focusedSlot)
        assertEquals(MultiviewNotice.AlreadyOnScreen("A"), c.state.value.notice)
    }

    @Test
    fun `a pick this profile may not watch is refused with a notice and no tile is opened`() {
        allowed = { it.key != "blocked" }
        val c = started(startKey = "a")
        val opened = created.size
        c.assign(1, ChannelPick("blocked", "Blocked One"))
        assertEquals(TileStatus.EMPTY, c.tile(1).status)
        assertEquals(opened, created.size)
        assertEquals(MultiviewNotice.CannotAdd("Blocked One"), c.state.value.notice)
    }

    @Test
    fun `a pick that fails at once shows the failure on the tile and says so in a notice`() {
        val c = started(startKey = "a")
        c.assign(1, ChannelPick("b", "Channel B"))
        assertEquals("loading with the name first", TileStatus.LOADING, c.tile(1).status)
        assertEquals("Channel B", c.tile(1).name)
        player(1).fail(TileFailure.ChannelUnavailable)
        assertEquals(TileStatus.FAILED, c.tile(1).status)
        assertEquals(TileFailure.ChannelUnavailable, c.tile(1).failure)
        assertEquals("b", c.tile(1).channelKey)
        assertEquals(MultiviewNotice.PickFailed("Channel B", "Not in your playlist anymore"), c.state.value.notice)
    }

    @Test
    fun `a failure long after the pick gets no notice`() {
        var now = 0L
        val c = controller(clock = { now })
        c.onResume()
        runBlocking { c.start("a") }
        c.assign(1, ChannelPick("b", "Channel B"))
        now = 3_000
        player(1).fail(TileFailure.NoData)
        assertNull("the card is enough", c.state.value.notice)
    }

    @Test
    fun `a channel missing from the playlist is never swapped for a backup`() {
        backupFinder = { _, tried -> listOf("bk1").map(::backup).firstOrNull { it.key !in tried } }
        val c = started(startKey = "a")
        player(0).fail(TileFailure.ChannelUnavailable)
        assertEquals("the tile keeps the channel it could not find", "a", c.tile(0).channelKey)
        assertEquals(TileStatus.FAILED, c.tile(0).status)
    }

    @Test
    fun `retry is the main action and replaces the tile's player`() {
        val c = started(startKey = "a")
        player(0).fail(TileFailure.Network)
        assertEquals(TileAction.RETRY, c.tile(0).failure?.primary)
        c.retry(0)
        assertEquals(TileStatus.LOADING, c.tile(0).status)
    }

    @Test
    fun `tile pixel size comes from the measured area and the tile's rectangle`() {
        queue.add("b")
        val c = started(startKey = "a")
        assertNull("not measured yet", c.tilePixelSize(0))
        c.setStage(widthDp = 864f, heightDp = 486f, widthPx = 1728, heightPx = 972)
        val (w, h) = c.tilePixelSize(0)!!
        assertEquals("428 dp at 2 px per dp", 856f, w.toFloat(), 2f)
        assertEquals("16:9", 16f / 9f, w.toFloat() / h, 0.02f)
    }

    // ---- capacity notice and ledger ----

    private val onnFacts = DecoderFacts(avc = CodecFacts(declaredInstances = 9, throughputFps1080 = 134f))

    @Test
    fun `adding a tile that makes the box tight says so once and the add goes ahead`() {
        facts = onnFacts
        queue.add("b")
        val c = started(startKey = "a")
        assertNull("two tiles fit", c.capacity)
        assertEquals(2, c.addSlot())
        c.assign(2, ChannelPick("c", "C"))
        assertEquals("the add went ahead", "c", c.tile(2).channelKey)
        assertEquals("about two at full quality", 2, c.capacity)
        c.dismissNotice(c.state.value.noticeSerial)
        assertNull(c.capacity)
        assertEquals(3, c.addSlot())
        c.assign(3, ChannelPick("d", "D"))
        assertEquals("4 tiles added", "d", c.tile(3).channelKey)
        assertNull("not shown twice in a session", c.capacity)
    }

    @Test
    fun `no notice when the box cannot be judged`() {
        queue.add("b"); queue.add("c"); queue.add("d")
        val c = started(startKey = "a")
        assertNull(c.capacity)
    }

    @Test
    fun `opening with several channels on a tight box shows the notice`() {
        facts = onnFacts
        queue.add("b"); queue.add("c"); queue.add("d")
        val c = started(startKey = "a")
        assertEquals(2, c.capacity)
    }

    @Test
    fun `a decoder failure with others open teaches the ledger once`() {
        queue.add("b"); queue.add("c")
        val c = started(startKey = "a")
        player(2).fail(TileFailure.DecoderUnavailable)
        assertEquals(mapOf(TileCodec.AVC to 2), ceilingStore.saved)
        player(2).fail(TileFailure.DecoderUnavailable)
        assertEquals("same tile again teaches nothing new", mapOf(TileCodec.AVC to 2), ceilingStore.saved)
    }

    @Test
    fun `other failures do not teach the ledger`() {
        queue.add("b")
        started(startKey = "a")
        player(1).fail(TileFailure.NoData)
        assertTrue(ceilingStore.saved.isEmpty())
    }

    @Test
    fun `a remembered ceiling makes the next add tight`() {
        ceilingStore.saved[TileCodec.AVC] = 1
        facts = onnFacts
        queue.add("b")
        val c = started(startKey = "a")
        assertEquals(1, c.capacity)
    }

    // ---- backup chain ----

    private fun backup(key: String) = TileChannel(key, com.nuvio.tv.playback.core.ProviderSelectionId(key))

    @Test
    fun `a gone stream moves to a backup channel at once`() {
        backupFinder = { _, tried -> listOf("bk1", "bk2").map(::backup).firstOrNull { it.key !in tried } }
        val c = started(startKey = "a")
        player(0).fail(TileFailure.StreamGone)
        assertEquals("backup took the slot", "bk1", c.tile(0).channelKey)
        assertEquals("a new player opened for it", "bk1", created.last { it.slot == 0 }.prepared?.key)
        assertTrue("old player released", created[0].released)
    }

    @Test
    fun `an unplayable format moves to a backup and the chain does not return to a tried channel`() {
        backupFinder = { _, tried -> listOf("bk1", "bk2").map(::backup).firstOrNull { it.key !in tried } }
        val c = started(startKey = "a")
        player(0).fail(TileFailure.Unsupported)
        assertEquals("bk1", c.tile(0).channelKey)
        player(0).fail(TileFailure.Unsupported)
        assertEquals("next backup, never back to a or bk1", "bk2", c.tile(0).channelKey)
    }

    @Test
    fun `without a backup the failure shows with its actions`() {
        val c = started(startKey = "a")
        player(0).fail(TileFailure.StreamGone)
        assertEquals(TileStatus.FAILED, c.tile(0).status)
        assertEquals(listOf(com.nuvio.tv.core.multiview.TileAction.REPLACE, com.nuvio.tv.core.multiview.TileAction.REMOVE), c.tile(0).failureActions)
        assertEquals("a", c.tile(0).channelKey)
    }

    @Test
    fun `a backup the profile may not watch is skipped`() {
        allowed = { it.key != "bk1" }
        backupFinder = { _, tried -> listOf("bk1", "bk2").map(::backup).firstOrNull { it.key !in tried } }
        val c = started(startKey = "a")
        player(0).fail(TileFailure.NoData)
        assertEquals("bk2", c.tile(0).channelKey)
    }
}

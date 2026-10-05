package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.LiveFavouritesOrder.Fav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F03 — favourites order = the synced added_at, newest first; a move rewrites as little as it can. */
class LiveFavouritesOrderTest {

    private val a = Fav("a", 9_000)
    private val b = Fav("b", 6_000)
    private val c = Fav("c", 3_000)
    private val list = LiveFavouritesOrder.ordered(listOf(c, a, b))

    private fun apply(changes: Map<String, Long>, favs: List<Fav>) =
        LiveFavouritesOrder.ordered(favs.map { f -> changes[f.id]?.let { Fav(f.id, it) } ?: f }).map { it.id }

    @Test
    fun newestIsFirst() {
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
    }

    @Test
    fun aMoveWritesOnlyTheMovedFavouriteWhenThereIsRoom() {
        val changes = LiveFavouritesOrder.move(list, "c", 0)
        assertEquals(setOf("c"), changes.keys)
        assertEquals(listOf("c", "a", "b"), apply(changes, list))
        val middle = LiveFavouritesOrder.move(list, "a", 1)
        assertEquals(setOf("a"), middle.keys)
        assertEquals(listOf("b", "a", "c"), apply(middle, list))
        assertEquals(listOf("a", "c", "b"), apply(LiveFavouritesOrder.move(list, "b", 2), list))
    }

    @Test
    fun noRoomRenumbersInTheRequestedOrder() {
        val tight = LiveFavouritesOrder.ordered(listOf(Fav("x", 5), Fav("y", 5), Fav("z", 4)))
        val changes = LiveFavouritesOrder.move(tight, "z", 1)
        assertEquals(listOf("x", "z", "y"), apply(changes, tight))
    }

    @Test
    fun nudgeAtAnEndIsANoOp() {
        assertTrue(LiveFavouritesOrder.nudge(list, "a", -1).isEmpty())
        assertTrue(LiveFavouritesOrder.nudge(list, "c", +1).isEmpty())
        assertEquals(listOf("b", "a", "c"), apply(LiveFavouritesOrder.nudge(list, "b", -1), list))
    }

    @Test
    fun pinnedMoveAssignsPositionsInTheNewOrder() {
        assertEquals(listOf("p3" to 0, "p1" to 1, "p2" to 2), PinnedChannelOrder.move(listOf("p1", "p2", "p3"), "p3", 0))
        assertTrue(PinnedChannelOrder.move(listOf("p1"), "p1", 0).isEmpty())
    }
}

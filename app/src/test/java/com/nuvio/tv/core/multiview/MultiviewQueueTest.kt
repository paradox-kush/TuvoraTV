package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewQueueTest {
    @Test
    fun `adding counts up`() {
        val q = MultiviewQueue()
        assertEquals(QueueResult.Added(1), q.add("a"))
        assertEquals(QueueResult.Added(2), q.add("b"))
    }

    @Test
    fun `a duplicate is reported and not added twice`() {
        val q = MultiviewQueue()
        q.add("a"); q.add("b")
        assertEquals(QueueResult.AlreadyQueued(2), q.add("a"))
        assertEquals(listOf("a", "b"), q.take())
    }

    @Test
    fun `the fifth channel is refused`() {
        val q = MultiviewQueue()
        listOf("a", "b", "c", "d").forEach { q.add(it) }
        assertEquals(QueueResult.Full, q.add("e"))
        assertEquals("a duplicate on a full queue is still a duplicate", QueueResult.AlreadyQueued(4), q.add("a"))
        assertEquals(4, q.take().size)
    }

    @Test
    fun `take hands over in order and empties the queue`() {
        val q = MultiviewQueue()
        q.add("a"); q.add("b")
        assertEquals(listOf("a", "b"), q.take())
        assertTrue(q.take().isEmpty())
        assertEquals("room again", QueueResult.Added(1), q.add("z"))
    }

    @Test
    fun `every toast offers Open now so a duplicate or a full queue is never a dead end`() {
        assertTrue("added", QueueResult.Added(1).offersOpenNow)
        assertTrue("already set aside", QueueResult.AlreadyQueued(2).offersOpenNow)
        assertTrue("full", QueueResult.Full.offersOpenNow)
    }

    @Test
    fun `max is four`() {
        assertEquals(4, MultiviewQueue.MAX)
    }

    @Test
    fun `a content id shaped like the guide's live channel id is accepted and handed over unchanged`() {
        // Playlist ids hold ':' and '|'; the game screen will add channels the same way later.
        val id = com.nuvio.tv.core.iptv.XtreamItemRegistry.liveId("http://host.example:8080|someone", 4242)
        val q = MultiviewQueue()
        assertEquals(QueueResult.Added(1), q.add(id))
        assertEquals(QueueResult.AlreadyQueued(1), q.add(id))
        assertEquals(listOf(id), q.take())
    }
}

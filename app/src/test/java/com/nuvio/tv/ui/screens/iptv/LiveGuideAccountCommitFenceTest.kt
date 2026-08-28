package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGuideAccountCommitFenceTest {
    @Test
    fun `switching account rejects every token captured by the previous account`() {
        val fence = LiveGuideAccountCommitFence()
        val first = fence.activate("account-a")

        val second = fence.activate("account-b")

        assertFalse(fence.accepts(first))
        assertTrue(fence.accepts(second))
        assertNull(fence.capture("account-a"))
        assertEquals(second, fence.capture("account-b"))
    }

    @Test
    fun `rapid switches leave only the latest account generation authorized`() {
        val fence = LiveGuideAccountCommitFence()
        val first = fence.activate("account-a")
        val second = fence.activate("account-b")
        val latest = fence.activate("account-c")

        assertFalse(fence.accepts(first))
        assertFalse(fence.accepts(second))
        assertTrue(fence.accepts(latest))
        assertEquals(3L, latest.generation)
    }

    @Test
    fun `option update for the same account retains its commit generation`() {
        val fence = LiveGuideAccountCommitFence()
        val beforeUpdate = fence.activate("account-a")

        val afterUpdate = fence.activate("account-a")

        assertEquals(beforeUpdate, afterUpdate)
        assertTrue(fence.accepts(beforeUpdate))
    }
}

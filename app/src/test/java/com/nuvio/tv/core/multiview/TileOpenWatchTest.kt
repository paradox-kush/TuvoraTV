package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileOpenWatchTest {
    private val stall = 20_000L
    private fun watch() = TileOpenWatch(stall)

    @Test
    fun `an open that gets a picture reports loaded with its duration once`() {
        val w = watch()
        assertNull(w.observe(TilePhase.WAITING, 10, null, 1_000))
        assertEquals(21_000L, w.deadlineAtMs)
        assertEquals(TileOutcome.Loaded(3_500), w.observe(TilePhase.PLAYING, 50, null, 4_500))
        assertNull("a later frame is not a second outcome", w.observe(TilePhase.PLAYING, 50, null, 5_000))
        assertNull(w.deadlineAtMs)
        assertFalse(w.stalled)
    }

    @Test
    fun `no progress for the stall window ends as timed out exactly once`() {
        val w = watch()
        w.observe(TilePhase.WAITING, 10, null, 0)
        assertNull("not yet", w.expire(19_999))
        assertEquals(TileOutcome.TimedOut(20_000), w.expire(20_000))
        assertTrue(w.stalled)
        assertNull("one report per incident", w.expire(40_000))
        assertNull("deadline is spent", w.deadlineAtMs)
    }

    @Test
    fun `progress pushes the deadline out but repeating a stage does not`() {
        val w = watch()
        w.observe(TilePhase.WAITING, 10, null, 0)
        w.observe(TilePhase.WAITING, 10, null, 15_000)
        assertEquals("same stage is not progress", 20_000L, w.deadlineAtMs)
        w.observe(TilePhase.WAITING, 41, null, 15_000)
        assertEquals("bytes arrived", 35_000L, w.deadlineAtMs)
        w.observe(TilePhase.WAITING, 12, null, 30_000)
        assertEquals("a reconnect falling back is not progress", 35_000L, w.deadlineAtMs)
        assertEquals(TileOutcome.TimedOut(35_000), w.expire(35_000))
    }

    @Test
    fun `a failure is its own outcome with its typed cause and is reported once`() {
        val w = watch()
        w.observe(TilePhase.WAITING, 10, null, 0)
        assertEquals(
            TileOutcome.Failed(TileFailure.ProviderRefused, 800),
            w.observe(TilePhase.FAILED, 0, TileFailure.ProviderRefused, 800),
        )
        assertNull(w.observe(TilePhase.FAILED, 0, TileFailure.ProviderRefused, 900))
        assertNull("no deadline after a failure", w.deadlineAtMs)
    }

    @Test
    fun `a failure with no cause is the generic failure`() {
        val w = watch()
        assertEquals(
            TileOutcome.Failed(TileFailure.Other, 0),
            w.observe(TilePhase.FAILED, 0, null, 100),
        )
    }

    @Test
    fun `a picture after the stall verdict clears it and a later loss is a new incident`() {
        val w = watch()
        w.observe(TilePhase.WAITING, 10, null, 0)
        w.expire(20_000)
        assertNull("already reported", w.observe(TilePhase.PLAYING, 50, null, 21_000))
        assertFalse(w.stalled)
        w.observe(TilePhase.WAITING, 40, null, 30_000)
        assertEquals(50_000L, w.deadlineAtMs)
        assertEquals(TileOutcome.Loaded(1_000), w.observe(TilePhase.PLAYING, 50, null, 31_000))
    }

    @Test
    fun `a user retry starts a fresh incident`() {
        val w = watch()
        w.observe(TilePhase.WAITING, 10, null, 0)
        w.expire(20_000)
        w.reset()
        assertFalse(w.stalled)
        w.observe(TilePhase.WAITING, 10, null, 25_000)
        assertEquals(45_000L, w.deadlineAtMs)
    }

    @Test
    fun `idle neither starts nor ends a wait`() {
        val w = watch()
        assertNull(w.observe(TilePhase.IDLE, 0, null, 0))
        assertNull(w.deadlineAtMs)
    }
}

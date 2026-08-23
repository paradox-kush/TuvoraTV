package com.nuvio.tv.core.analytics

import com.nuvio.tv.core.analytics.LiveRecoveryCoordinator.Engine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LiveEngineMemoryTest {

    @Before fun setUp() = LiveEngineMemory.clearAll()
    @After fun tearDown() = LiveEngineMemory.clearAll()

    @Test
    fun `an unlearned channel has no preference`() {
        assertNull(
            "a channel we have never escalated must start on the default engine",
            LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE),
        )
    }

    @Test
    fun `a learned channel is remembered so the next open skips the freeze`() {
        LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        assertEquals(
            "the next open must start directly on the learned engine",
            Engine.MPV,
            LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE),
        )
    }

    @Test
    fun `live and catch-up lanes are learned independently`() {
        LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        assertNull(
            "a channel's catch-up recording must not inherit its live engine (F6)",
            LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.CATCHUP),
        )
    }

    @Test
    fun `forget clears a learned channel - the re-validation hook`() {
        LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        LiveEngineMemory.forget("xtream:7tv", LiveEngineMemory.Lane.LIVE)
        assertNull(LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE))
    }

    @Test
    fun `a blank channel id is ignored, never mis-keyed`() {
        LiveEngineMemory.remember("", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        assertNull(LiveEngineMemory.preferredEngine("", LiveEngineMemory.Lane.LIVE))
        assertNull(LiveEngineMemory.preferredEngine(null, LiveEngineMemory.Lane.LIVE))
    }

    @Test
    fun `snapshot then restore round-trips the learnings for the persistence store`() {
        LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
        LiveEngineMemory.remember("xtream:etv", LiveEngineMemory.Lane.CATCHUP, Engine.MPV)
        val snap = LiveEngineMemory.snapshot()
        LiveEngineMemory.clearAll() // simulate an app restart with an empty cache
        assertNull(LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE))
        LiveEngineMemory.restore(snap) // the store reloads from disk at startup
        assertEquals(
            "a learned channel must survive a restart via snapshot/restore",
            Engine.MPV,
            LiveEngineMemory.preferredEngine("xtream:7tv", LiveEngineMemory.Lane.LIVE),
        )
        assertEquals(Engine.MPV, LiveEngineMemory.preferredEngine("xtream:etv", LiveEngineMemory.Lane.CATCHUP))
    }

    @Test
    fun `onChange fires on remember and forget so the store can persist`() {
        var changes = 0
        LiveEngineMemory.onChange = { changes++ }
        try {
            LiveEngineMemory.remember("xtream:7tv", LiveEngineMemory.Lane.LIVE, Engine.MPV)
            LiveEngineMemory.forget("xtream:7tv", LiveEngineMemory.Lane.LIVE)
            LiveEngineMemory.forget("xtream:7tv", LiveEngineMemory.Lane.LIVE) // no-op: nothing to remove
            assertEquals("write-through fires once per real change, not on a no-op forget", 2, changes)
        } finally {
            LiveEngineMemory.onChange = null
        }
    }
}

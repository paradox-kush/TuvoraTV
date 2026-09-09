package com.nuvio.tv.core.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for the journal + recovery-gate lifecycle, driven by an in-memory store and a
 * controllable clock — no process/OOM/heap. Twin of the KMP StartupJournalControllerTest. Tests the
 * production wiring logic (the controller the singleton delegates to), distinct from pure policy.
 */
class StartupJournalControllerTest {

    private class FakeStore(var failWrites: Boolean = false) : JournalStore {
        var content: String? = null
        var writes = 0
        override fun read(): String? = content
        override fun writeVerified(content: String): Boolean {
            if (failWrites) return false
            this.content = content
            writes++
            return true
        }
    }

    private class FakeClock(var now: Long = 1_000L) {
        fun ms() = now
    }

    private fun controller(store: FakeStore, clock: FakeClock, build: String = "1.0") =
        StartupJournalController(store, clock::ms) { build }

    private fun restart(store: FakeStore, clock: FakeClock) = controller(store, clock)

    private val idx = StartupJournalPolicy.OP_INDEX_BUILD
    private val sub = "acct#movie"

    @Test
    fun `interrupted build defers on the next run then a completion clears it`() {
        val store = FakeStore()
        val clock = FakeClock(1_000L)
        assertNotNull("attempt persisted", controller(store, clock).beginAttempt(idx, sub))
        clock.now = 2_000L
        val c2 = restart(store, clock)
        assertTrue("interrupted build is deferred next run", c2.shouldDefer(idx, sub))
        clock.now = 2_000L + 61_000L
        assertFalse("deferral clears after the interrupt window", c2.shouldDefer(idx, sub))
        val t = c2.beginAttempt(idx, sub)!!
        c2.record(idx, sub, t, JournalOutcome.COMPLETED)
        assertFalse("completed build no longer defers", c2.shouldDefer(idx, sub))
    }

    @Test
    fun `repeated headless launches never enter safe mode`() {
        val store = FakeStore()
        val clock = FakeClock()
        repeat(5) {
            clock.now += 1_000L
            assertFalse("headless-only run is never safe mode", restart(store, clock).decideStartupMode())
        }
    }

    @Test
    fun `two consecutive pre-interactive ui crashes enter safe mode`() {
        val store = FakeStore()
        val clock = FakeClock()
        repeat(2) {
            clock.now += 1_000L
            restart(store, clock).markUiLaunchStarted()
        }
        clock.now += 1_000L
        restart(store, clock).decideStartupMode() // a headless run between crashes must not reset
        clock.now += 1_000L
        assertTrue("two ui crashes then safe mode", restart(store, clock).decideStartupMode())
    }

    @Test
    fun `a ui launch after headless opens the attempt only at ui launch`() {
        val store = FakeStore()
        val c = controller(store, FakeClock())
        c.decideStartupMode()
        val before = store.writes
        c.markUiLaunchStarted()
        assertTrue("the ui launch opened and persisted the attempt", store.writes > before)
    }

    @Test
    fun `recovery works without any analytics`() {
        val store = FakeStore()
        controller(store, FakeClock()).markUiLaunchStarted()
        assertTrue("persistence happens with no analytics involved", store.writes > 0)
    }

    @Test
    fun `persistence failure withholds risky work`() {
        val c = controller(FakeStore(failWrites = true), FakeClock())
        assertFalse("a failed initial write marks the journal unhealthy", c.isHealthy)
        assertNull("no attempt token so the caller withholds the build", c.beginAttempt(idx, sub))
    }

    @Test
    fun `a stale completion cannot clobber a newer attempt`() {
        val store = FakeStore()
        val clock = FakeClock()
        val c = controller(store, clock)
        val gen1 = c.beginAttempt(idx, sub)!!
        val gen2 = c.beginAttempt(idx, sub)!!
        assertTrue(gen2 > gen1)
        c.record(idx, sub, gen1, JournalOutcome.COMPLETED)
        clock.now += 10_000L
        assertTrue("stale completion did not clear the newer attempt", restart(store, clock).shouldDefer(idx, sub))
    }

    @Test
    fun `safe mode stays active for the run after the interactive milestone`() {
        val store = FakeStore()
        val clock = FakeClock()
        repeat(2) { clock.now += 1_000L; restart(store, clock).markUiLaunchStarted() }
        clock.now += 1_000L
        val c = restart(store, clock)
        c.markUiLaunchStarted()
        assertTrue("safe mode entered", c.isSafeMode)
        c.markInteractiveReached()
        assertTrue("still safe mode this run — withheld work is not restarted", c.isSafeMode)
    }

    @Test
    fun `distinct ops both persist without clobbering`() {
        val store = FakeStore()
        val clock = FakeClock()
        val c = controller(store, clock)
        c.beginAttempt(idx, "a")
        c.beginAttempt(idx, "b")
        clock.now += 10_000L
        val r = restart(store, clock)
        assertTrue("op a survived", r.shouldDefer(idx, "a"))
        assertTrue("op b survived", r.shouldDefer(idx, "b"))
    }
}

package com.nuvio.tv.core.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit (TV): assertEquals(message, expected, actual). Twin of the commonTest EffectScopeTest. */
class EffectScopeTest {
    private fun scope() = EffectScope { throw AssertionError("unexpected revert failure: $it") }

    @Test
    fun revertsRunLifo() {
        val order = mutableListOf<Int>()
        val s = scope()
        s.onRevert { order.add(1) }
        s.onRevert { order.add(2) }
        s.onRevert { order.add(3) }
        s.dispose()
        assertEquals("LIFO", listOf(3, 2, 1), order)
    }

    @Test
    fun throwingRevertDoesNotAbortRest() {
        val ran = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        val s = EffectScope { failures.add(it) }
        s.onRevert { ran.add(1) }
        s.onRevert { throw IllegalStateException("boom") }
        s.onRevert { ran.add(3) }
        s.dispose()
        assertEquals("both non-throwing reverts ran", listOf(3, 1), ran)
        assertEquals("throw reported to sink", 1, failures.size)
    }

    @Test
    fun registerAfterDisposeRunsImmediately() {
        val s = scope()
        s.dispose()
        var ran = 0
        val handle = s.onRevert { ran++ }
        assertEquals("late revert ran immediately", 1, ran)
        handle.dispose()
        assertEquals("handle inert", 1, ran)
    }

    @Test
    fun disposeTwiceIsNoOp() {
        var ran = 0
        val s = scope()
        s.onRevert { ran++ }
        s.dispose()
        s.dispose()
        assertEquals("dispose fires once", 1, ran)
    }

    @Test
    fun earlyDisposedHandleFiresOnce() {
        var ran = 0
        val s = scope()
        val h = s.onRevert { ran++ }
        h.dispose()
        h.dispose()
        s.dispose()
        assertEquals("fired exactly once", 1, ran)
    }
}

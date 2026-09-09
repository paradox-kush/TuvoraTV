package com.nuvio.tv.core.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JUnit (TV) — assertEquals(message, expected, actual). */
class CoroutineGuardsTest {
    @Test
    fun `guarded returns Ok for a value`() {
        val g = guarded(onError = { }) { 42 }
        assertTrue(g is Guarded.Ok)
        assertEquals(42, (g as Guarded.Ok).value)
    }

    @Test
    fun `guarded preserves a null value as Ok not Failed`() {
        val g = guarded<Int?>(onError = { }) { null }
        assertTrue("null is a value, not a failure", g is Guarded.Ok)
        assertNull((g as Guarded.Ok).value)
    }

    @Test
    fun `guarded reports and returns Failed on a thrown error`() {
        var seen: Throwable? = null
        val g = guarded(onError = { seen = it }) { throw IllegalStateException("boom") }
        assertTrue(g is Guarded.Failed)
        assertTrue(seen is IllegalStateException)
    }

    @Test
    fun `guarded re-raises CancellationException without reporting`() {
        var reported = false
        try {
            guarded(onError = { reported = true }) { throw CancellationException("cancel") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        assertFalse("cancellation is never swallowed or reported", reported)
    }

    @Test
    fun `containTask contains a thrown error`() {
        var seen: Throwable? = null
        containTask(onError = { seen = it }) { throw RuntimeException("x") }
        assertTrue("the task boundary contained the failure", seen is RuntimeException)
    }

    @Test
    fun `containTask re-raises CancellationException`() {
        var reported = false
        try {
            containTask(onError = { reported = true }) { throw CancellationException("c") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
        assertFalse(reported)
    }

    @Test
    fun `containTask runs the body on the happy path`() {
        var ran = false
        containTask(onError = { }) { ran = true }
        assertTrue(ran)
    }
}

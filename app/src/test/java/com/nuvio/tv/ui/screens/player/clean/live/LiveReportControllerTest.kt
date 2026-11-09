package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.core.telemetry.DiagEntry
import com.nuvio.tv.core.telemetry.ReportCategory
import com.nuvio.tv.core.telemetry.ReportResult
import com.nuvio.tv.core.telemetry.ReportSurface
import com.nuvio.tv.core.telemetry.TelemetryPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telemetry v2: one press of the live "Send report" = one send, and the overlay says what happened. */
class LiveReportControllerTest {

    private class FakeTelemetry(var answer: suspend () -> ReportResult) : TelemetryPort {
        val calls = mutableListOf<Triple<ReportCategory, ReportSurface, String?>>()
        override val supportCode = "K7Q29MPX"
        override fun record(entry: DiagEntry) = Unit
        override suspend fun sendUserReport(category: ReportCategory, surface: ReportSurface, failureCode: String?): ReportResult {
            calls += Triple(category, surface, failureCode)
            return answer()
        }
    }

    private fun TestScope.controller(port: TelemetryPort): LiveReportController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LiveReportController(port, backgroundScope, dispatcher, resultVisibleMs = 8_000, sendDeadlineMs = 15_000)
    }

    @Test
    fun `one press sends once, as live, with the failure code`() = runTest {
        val port = FakeTelemetry { ReportResult.Sent("K7Q29MPX") }
        val c = controller(port)
        assertTrue("press accepted", c.send(ReportCategory.FREEZES, "playback_failed:NO_PROGRESS"))
        runCurrent()
        assertEquals("one call", listOf(Triple(ReportCategory.FREEZES, ReportSurface.LIVE, "playback_failed:NO_PROGRESS")), port.calls)
    }

    @Test
    fun `sending shows at once and a second press while sending does nothing`() = runTest {
        val gate = CompletableDeferred<ReportResult>()
        val port = FakeTelemetry { gate.await() }
        val c = controller(port)
        c.send(ReportCategory.WONT_PLAY, null)
        assertEquals("sending before any dispatch", LiveReportState.Sending, c.state.value)
        runCurrent()
        assertFalse("double OK refused", c.send(ReportCategory.WONT_PLAY, null))
        runCurrent()
        assertEquals("still one call", 1, port.calls.size)
        gate.complete(ReportResult.Sent("K7Q29MPX"))
        runCurrent()
        assertEquals("sent with the code", LiveReportState.Sent("K7Q2-9MPX"), c.state.value)
    }

    @Test
    fun `each result maps to its own state and none but sent is success`() = runTest {
        listOf(
            ReportResult.Sent("ABCDEFGH") to LiveReportState.Sent("ABCD-EFGH"),
            ReportResult.ConsentOff to LiveReportState.ConsentOff,
            ReportResult.RateLimited(42) to LiveReportState.RateLimited(42),
            ReportResult.Failed to LiveReportState.Failed,
        ).forEach { (result, expected) ->
            val c = controller(FakeTelemetry { result })
            c.send(ReportCategory.OTHER, null)
            runCurrent()
            assertEquals("$result", expected, c.state.value)
        }
    }

    @Test
    fun `a thrown send is failed, not success and not stuck sending`() = runTest {
        val c = controller(FakeTelemetry { error("boom") })
        c.send(ReportCategory.OTHER, null)
        runCurrent()
        assertEquals("failed", LiveReportState.Failed, c.state.value)
    }

    @Test
    fun `a send that never answers ends as failed at its deadline`() = runTest {
        val c = controller(FakeTelemetry { awaitCancellation() })
        c.send(ReportCategory.OTHER, null)
        advanceTimeBy(14_999); runCurrent()
        assertEquals("still sending before the deadline", LiveReportState.Sending, c.state.value)
        advanceTimeBy(2); runCurrent()
        assertEquals("failed at the deadline", LiveReportState.Failed, c.state.value)
    }

    @Test
    fun `the result clears by itself after eight seconds`() = runTest {
        val c = controller(FakeTelemetry { ReportResult.Failed })
        c.send(ReportCategory.OTHER, null)
        runCurrent()
        advanceTimeBy(7_999); runCurrent()
        assertEquals("still shown", LiveReportState.Failed, c.state.value)
        advanceTimeBy(2); runCurrent()
        assertEquals("cleared", LiveReportState.Idle, c.state.value)
    }

    @Test
    fun `clearing mid-send drops the late answer`() = runTest {
        val gate = CompletableDeferred<ReportResult>()
        val c = controller(FakeTelemetry { gate.await() })
        c.send(ReportCategory.OTHER, null)
        runCurrent()
        c.clear() // the error cleared, or the viewer zapped
        gate.complete(ReportResult.Sent("K7Q29MPX"))
        runCurrent()
        assertEquals("nothing survives the clear", LiveReportState.Idle, c.state.value)
    }

    @Test
    fun `after a result a new press may send again`() = runTest {
        val port = FakeTelemetry { ReportResult.Failed }
        val c = controller(port)
        c.send(ReportCategory.OTHER, null); runCurrent()
        assertTrue("second press after a failure", c.send(ReportCategory.OTHER, null)); runCurrent()
        assertEquals("two calls", 2, port.calls.size)
    }
}

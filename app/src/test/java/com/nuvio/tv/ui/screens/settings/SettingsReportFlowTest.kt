package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.telemetry.DiagEntry
import com.nuvio.tv.core.telemetry.ReportCategory
import com.nuvio.tv.core.telemetry.ReportResult
import com.nuvio.tv.core.telemetry.ReportSurface
import com.nuvio.tv.core.telemetry.TelemetryPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsReportFlowTest {

    private class FakePort(var next: suspend () -> ReportResult) : TelemetryPort {
        override val supportCode = "K7Q29MPX"
        val calls = mutableListOf<Triple<ReportCategory, ReportSurface, String?>>()
        override fun record(entry: DiagEntry) = Unit
        override suspend fun sendUserReport(
            category: ReportCategory,
            surface: ReportSurface,
            failureCode: String?,
        ): ReportResult {
            calls += Triple(category, surface, failureCode)
            return next()
        }
    }

    private fun TestScope.flow(port: FakePort, timeoutMs: Long = 20_000L) =
        SettingsReportFlow(port, this, timeoutMs)

    @Test
    fun `support code is exposed formatted`() = runTest {
        assertEquals("K7Q2-9MPX", flow(FakePort { ReportResult.Failed }).displaySupportCode)
    }

    @Test
    fun `one pick makes exactly one call from the settings surface`() = runTest {
        val port = FakePort { ReportResult.Sent("K7Q29MPX") }
        val f = flow(port)
        f.open()
        f.pick(ReportCategory.FREEZES)
        runCurrent()
        assertEquals(listOf(Triple(ReportCategory.FREEZES, ReportSurface.SETTINGS, null)), port.calls)
        assertEquals(SettingsReportUiState.Sent("K7Q2-9MPX"), f.state.value)
    }

    @Test
    fun `a second pick while sending does nothing`() = runTest {
        val gate = CompletableDeferred<ReportResult>()
        val port = FakePort { gate.await() }
        val f = flow(port)
        f.open()
        f.pick(ReportCategory.WONT_PLAY)
        runCurrent()
        assertEquals(SettingsReportUiState.Sending, f.state.value)
        f.pick(ReportCategory.OTHER)
        f.open() // also ignored while in flight
        runCurrent()
        assertEquals(1, port.calls.size)
        assertEquals(SettingsReportUiState.Sending, f.state.value)
        gate.complete(ReportResult.Sent("K7Q29MPX"))
        runCurrent()
        assertEquals(1, port.calls.size)
    }

    @Test
    fun `pick without opening the chooser does nothing`() = runTest {
        val port = FakePort { ReportResult.Sent("K7Q29MPX") }
        val f = flow(port)
        f.pick(ReportCategory.OTHER)
        runCurrent()
        assertEquals(0, port.calls.size)
        assertEquals(SettingsReportUiState.Idle, f.state.value)
    }

    @Test
    fun `each result maps to its own state and only Sent is success`() = runTest {
        val cases = listOf(
            ReportResult.ConsentOff to SettingsReportUiState.ConsentOff,
            ReportResult.RateLimited(90) to SettingsReportUiState.RateLimited(90),
            ReportResult.Failed to SettingsReportUiState.Failed,
        )
        for ((result, expected) in cases) {
            val f = flow(FakePort { result })
            f.open()
            f.pick(ReportCategory.BLACK_SCREEN)
            runCurrent()
            assertEquals(expected, f.state.value)
        }
    }

    @Test
    fun `a throwing port ends as Failed not Sending`() = runTest {
        val f = flow(FakePort { error("boom") })
        f.open()
        f.pick(ReportCategory.APP_CLOSED)
        runCurrent()
        assertEquals(SettingsReportUiState.Failed, f.state.value)
    }

    @Test
    fun `a send that never returns hits the deadline and ends as Failed`() = runTest {
        val f = flow(FakePort { CompletableDeferred<ReportResult>().await() }, timeoutMs = 5_000L)
        f.open()
        f.pick(ReportCategory.OTHER)
        runCurrent()
        assertEquals(SettingsReportUiState.Sending, f.state.value)
        advanceTimeBy(5_001L)
        runCurrent()
        assertEquals(SettingsReportUiState.Failed, f.state.value)
    }

    @Test
    fun `dismiss returns to idle but not while sending`() = runTest {
        val gate = CompletableDeferred<ReportResult>()
        val f = flow(FakePort { gate.await() })
        f.open()
        f.dismiss()
        assertEquals(SettingsReportUiState.Idle, f.state.value)
        f.open()
        f.pick(ReportCategory.OTHER)
        runCurrent()
        f.dismiss()
        assertEquals(SettingsReportUiState.Sending, f.state.value)
    }
}

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.telemetry.DiagEntry
import com.nuvio.tv.core.telemetry.ReportCategory
import com.nuvio.tv.core.telemetry.ReportResult
import com.nuvio.tv.core.telemetry.ReportSurface
import com.nuvio.tv.core.telemetry.TelemetryPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportTest {

    private class FakePort(var result: () -> ReportResult = { ReportResult.Sent("K7Q29MPX") }) : TelemetryPort {
        override val supportCode = "K7Q29MPX"
        val calls = mutableListOf<Triple<ReportCategory, ReportSurface, String?>>()
        var gate: CompletableDeferred<Unit>? = null
        override fun record(entry: DiagEntry) = Unit
        override suspend fun sendUserReport(
            category: ReportCategory,
            surface: ReportSurface,
            failureCode: String?,
        ): ReportResult {
            calls += Triple(category, surface, failureCode)
            gate?.await()
            return result()
        }
    }

    // --- category policy ---

    @Test
    fun cannotPlayAndFailedMapToWontPlay() {
        assertEquals(ReportCategory.WONT_PLAY, PlaybackReportCategoryPolicy.categoryFor("Cannot play this video", false))
        assertEquals(ReportCategory.WONT_PLAY, PlaybackReportCategoryPolicy.categoryFor("Playback failed", false))
    }

    @Test
    fun stalledAndLongLoadingMapToFreezes() {
        assertEquals(ReportCategory.FREEZES, PlaybackReportCategoryPolicy.categoryFor("Stream stalled", false))
        assertEquals(ReportCategory.FREEZES, PlaybackReportCategoryPolicy.categoryFor("Request timed out", false))
        assertEquals(ReportCategory.FREEZES, PlaybackReportCategoryPolicy.categoryFor(null, loadingStalled = true))
    }

    @Test
    fun blackScreenAndNoVideoMapToBlackScreen() {
        assertEquals(ReportCategory.BLACK_SCREEN, PlaybackReportCategoryPolicy.categoryFor("Black screen, audio only", false))
        assertEquals(ReportCategory.BLACK_SCREEN, PlaybackReportCategoryPolicy.categoryFor("No video track", false))
    }

    @Test
    fun anythingElseMapsToOther() {
        assertEquals(ReportCategory.OTHER, PlaybackReportCategoryPolicy.categoryFor(null, false))
        assertEquals(ReportCategory.OTHER, PlaybackReportCategoryPolicy.categoryFor("  ", false))
        assertEquals(ReportCategory.OTHER, PlaybackReportCategoryPolicy.categoryFor("Hmm", false))
    }

    // --- submitter ---

    @Test
    fun oneTapIsOneCallOnVodSurfaceAndEndsInSent() = runBlocking {
        val port = FakePort()
        val submitter = PlaybackReportSubmitter(port)
        val seen = mutableListOf<PlaybackReportState>()
        submitter.submit(ReportCategory.WONT_PLAY, "ERR_X") { seen += it }
        assertEquals(listOf(Triple(ReportCategory.WONT_PLAY, ReportSurface.VOD, "ERR_X" as String?)), port.calls)
        assertEquals(listOf(PlaybackReportState.Sending, PlaybackReportState.Sent("K7Q2-9MPX")), seen)
    }

    @Test
    fun secondTapWhileSendingDoesNothing() = runBlocking {
        val port = FakePort().apply { gate = CompletableDeferred() }
        val submitter = PlaybackReportSubmitter(port)
        val first = async { submitter.submit(ReportCategory.OTHER, null) {} }
        while (port.calls.isEmpty()) delay(1)
        submitter.submit(ReportCategory.OTHER, null) {}
        assertEquals(1, port.calls.size)
        assertTrue(submitter.current is PlaybackReportState.Sending)
        port.gate!!.complete(Unit)
        first.await()
        assertEquals(1, port.calls.size)
    }

    @Test
    fun tapAfterSentDoesNothing() = runBlocking {
        val port = FakePort()
        val submitter = PlaybackReportSubmitter(port)
        submitter.submit(ReportCategory.OTHER, null) {}
        submitter.submit(ReportCategory.OTHER, null) {}
        assertEquals(1, port.calls.size)
    }

    private fun terminal(result: ReportResult): PlaybackReportState = runBlocking {
        val submitter = PlaybackReportSubmitter(FakePort { result })
        submitter.submit(ReportCategory.OTHER, null) {}
        submitter.current
    }

    @Test
    fun eachResultMapsToItsState() {
        assertEquals(PlaybackReportState.ConsentOff, terminal(ReportResult.ConsentOff))
        assertEquals(PlaybackReportState.RateLimited(30), terminal(ReportResult.RateLimited(30)))
        assertEquals(PlaybackReportState.Failed, terminal(ReportResult.Failed))
        assertEquals(PlaybackReportState.Sent("K7Q2-9MPX"), terminal(ReportResult.Sent("K7Q29MPX")))
    }

    @Test
    fun failureIsRetryableButNeverShownAsSuccess() = runBlocking {
        val port = FakePort { ReportResult.Failed }
        val submitter = PlaybackReportSubmitter(port)
        submitter.submit(ReportCategory.OTHER, null) {}
        assertFalse(submitter.current is PlaybackReportState.Sent)
        port.result = { ReportResult.Sent("K7Q29MPX") }
        submitter.submit(ReportCategory.OTHER, null) {}
        assertEquals(2, port.calls.size)
        assertTrue(submitter.current is PlaybackReportState.Sent)
    }

    @Test
    fun portThrowingOrHangingEndsInFailed() = runBlocking {
        val throwing = PlaybackReportSubmitter(FakePort { error("boom") })
        throwing.submit(ReportCategory.OTHER, null) {}
        assertEquals(PlaybackReportState.Failed, throwing.current)

        val hang = FakePort().apply { gate = CompletableDeferred() }
        val hanging = PlaybackReportSubmitter(hang, timeoutMs = 50)
        hanging.submit(ReportCategory.OTHER, null) {}
        assertEquals(PlaybackReportState.Failed, hanging.current)
    }

    @Test
    fun resetReturnsToIdleExceptWhileSending() = runBlocking {
        val port = FakePort()
        val submitter = PlaybackReportSubmitter(port)
        submitter.submit(ReportCategory.OTHER, null) {}
        assertTrue(submitter.reset())
        assertEquals(PlaybackReportState.Idle, submitter.current)
        assertNull(port.calls.firstOrNull()?.third)
    }
}

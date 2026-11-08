package com.nuvio.tv.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class CrashHandlerAndBoundaryTest {

    /** The installed handler, not just the writer: it records the crash and ALWAYS hands it on. */
    @Test
    fun installedHandlerWritesTheRecordHandsOnAndRespectsConsent() {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = File(ctx.filesDir, "telemetry").also { it.mkdirs(); it.listFiles()?.forEach { f -> f.delete() } }
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        try {
            var delegated = 0
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated++ }
            TelemetryHolder.consentEnabled = true
            CrashRecorder.install(ctx, "1.18.1")
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!

            handler.uncaughtException(Thread.currentThread(), IllegalStateException("provider said hunter2"))
            assertEquals("crash handed on", 1, delegated)
            val waiting = CrashOutbox.collect(dir)
            assertEquals("one record written", 1, waiting.size)
            assertTrue("no message text stored", !waiting.single().file.readText().contains("hunter2"))

            dir.listFiles()?.forEach { it.delete() }
            TelemetryHolder.consentEnabled = false
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("x"))
            assertEquals("still handed on with reports off", 2, delegated)
            assertEquals("nothing written with reports off", 0, CrashOutbox.collect(dir).size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved)
            TelemetryHolder.consentEnabled = false
        }
    }

    @Test
    fun aFailedSendGivesTheRateWindowBack() {
        val limit = ReportRateLimit(minIntervalMs = 120_000)
        assertEquals(0L, limit.tryAcquire(1_000))
        limit.release()
        assertEquals("the failed send did not use up the window", 0L, limit.tryAcquire(2_000))
        assertTrue("a real send does", limit.tryAcquire(3_000) > 0)
    }

    /**
     * Ratchet: every direct `PostHog.capture` call site is listed here. All of them pass through the
     * beforeSend gate (so none bypasses the policy), but a NEW one must be added on purpose, which is the
     * moment to ask whether it should go through `core/telemetry` instead.
     */
    @Test
    fun directPostHogCallSitesAreTheKnownOnes() {
        val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
        val sites = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> !f.path.contains("/core/telemetry/") }
            .filter { f -> f.readText().let { "PostHog.capture(" in it || "PostHog.captureException(" in it } }
            .map { it.name }.toSortedSet()
        val known = sortedSetOf(
            "LivePlaybackTelemetry.kt", "SportsHubViewModel.kt", "ProviderSetupRepository.kt", "DeviceClaimCoordinator.kt",
            "BoundedLoad.kt", "XtreamTmdbResolver.kt", "EpgTelemetry.kt", "Breadcrumbs.kt", "AudioOutputTelemetry.kt",
            "LivePlaybackFreezeReporter.kt", "AppExitReporter.kt", "PlaybackDiagnosticsAdapter.kt", "SimklLibraryService.kt",
        )
        assertEquals("a new direct PostHog call site appeared", known, sites)
    }
}

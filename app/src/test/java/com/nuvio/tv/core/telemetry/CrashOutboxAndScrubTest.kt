package com.nuvio.tv.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class CrashOutboxAndScrubTest {

    private fun record(id: String, at: Long) =
        CrashRecord(id, at, "1.18.1", "main", "java.lang.IllegalStateException", null, listOf("a.B.c(B.kt:1)"))

    private fun tmp() = Files.createTempDirectory("outbox").toFile()

    /** Regression for the blocker: a crash loop used to report only its FIRST crash, ever. */
    @Test
    fun everyCrashInALoopIsKeptAndSent() {
        val dir = tmp()
        CrashRecorder.write(dir, record("crash-1", 1))
        CrashRecorder.write(dir, record("crash-2", 2)) // second crash before the first was sent
        val first = CrashOutbox.collect(dir).map { it.record.crashId }.toSet()
        assertEquals("both crashes waiting, none overwritten", setOf("crash-1", "crash-2"), first)
        // The app dies again before the confirm window: the files are still there, plus a new crash.
        CrashRecorder.write(dir, record("crash-3", 3))
        val second = CrashOutbox.collect(dir).map { it.record.crashId }.toSet()
        assertEquals("earlier crashes resent, the new one added", setOf("crash-1", "crash-2", "crash-3"), second)
    }

    @Test
    fun theOutboxIsBoundedAndDropsUnreadableFiles() {
        val dir = tmp()
        repeat(9) { CrashRecorder.write(dir, record("c$it", it.toLong())); File(dir, "sending_c$it.json").setLastModified(1000L + it) }
        File(dir, "sending_bad.json").writeText("not json")
        val entries = CrashOutbox.collect(dir)
        assertEquals("capped per launch", CrashOutbox.MAX_PER_LAUNCH, entries.size)
        assertEquals("newest first", "c8", entries.first().record.crashId)
        assertEquals("overflow and garbage deleted", CrashOutbox.MAX_PER_LAUNCH, dir.listFiles().orEmpty().count { it.name.endsWith(".json") })
    }

    @Test
    fun aLegacyPendingFileIsPromotedWithoutLosingIt() {
        val dir = tmp()
        File(dir, CrashOutbox.PENDING).writeText(record("legacy", 5).toJson())
        assertEquals("legacy", CrashOutbox.collect(dir).single().record.crashId)
        assertFalse(File(dir, CrashOutbox.PENDING).exists())
    }

    @Test
    fun exceptionMessagesAreRemovedFromTheSdksOwnExceptionEvents() {
        val props = mapOf<String, Any?>(
            "\$exception_list" to listOf(
                mapOf("type" to "JsonDecodingException", "value" to "JSON input: {\"password\":\"s3cret\"} at provider.com", "stacktrace" to "frames"),
            ),
        )
        val out = ExceptionScrub.scrub("\$exception", props)
        val first = (out["\$exception_list"] as List<*>).single() as Map<*, *>
        assertEquals("type kept", "JsonDecodingException", first["type"])
        assertEquals("frames kept", "frames", first["stacktrace"])
        assertNull("message removed", first["value"])
        assertFalse("nothing sensitive left anywhere", out.toString().contains("s3cret"))
    }

    @Test
    fun ourOwnProcessExitNoteSurvivesAndIsRecognised() {
        val props = mapOf<String, Any?>("\$exception_list" to listOf(mapOf("type" to "X", "value" to "Android process exited abnormally: crash")))
        val out = ExceptionScrub.scrub("\$exception", props)
        assertTrue(out.toString().contains("exited abnormally: crash"))
        assertEquals(TelemetrySendPolicy.Action.COUNT, TelemetrySendPolicy.decide("\$exception", out).action)
    }

    @Test
    fun indexBuildDetailKeepsOnlyTheExceptionClass() {
        assertEquals("OutOfMemoryError", ExceptionScrub.scrub("iptv_index_build", mapOf("detail" to "OutOfMemoryError: pool 12 at host.example"))["detail"])
        assertFalse("free text dropped", ExceptionScrub.scrub("iptv_index_build", mapOf("detail" to "Failed to load https://p.example/x"))
            .containsKey("detail"))
    }

    @Test
    fun realEpgEventShapesAreCountedUnlessTheyFailed() {
        // The real events carry counts, not an outcome (EpgTelemetry.kt).
        assertEquals(TelemetrySendPolicy.Action.COUNT, TelemetrySendPolicy.decide("epg_resolve", mapOf("manual" to 0, "none" to 4, "unavailable" to 0, "total" to 80)).action)
        assertEquals(TelemetrySendPolicy.Action.SEND, TelemetrySendPolicy.decide("epg_resolve", mapOf("unavailable" to 12, "total" to 80)).action)
        assertEquals(TelemetrySendPolicy.Action.COUNT, TelemetrySendPolicy.decide("epg_mapping", mapOf("matched" to 60, "channels" to 80)).action)
        assertEquals(TelemetrySendPolicy.Action.SEND, TelemetrySendPolicy.decide("epg_mapping", mapOf("matched" to 0, "channels" to 80)).action)
        assertEquals(TelemetrySendPolicy.Action.COUNT, TelemetrySendPolicy.decide("iptv_load", mapOf("outcome" to "empty")).action)
        assertEquals(TelemetrySendPolicy.Action.COUNT, TelemetrySendPolicy.decide("iptv_index_build", mapOf("outcome" to "category_fallback")).action)
    }
}

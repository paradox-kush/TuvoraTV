package com.nuvio.tv.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class CrashRecordTest {

    @Test
    fun recordRoundTripsThroughJson() {
        val record = CrashRecord("id-1", 1234L, "1.18.0", "main", "java.lang.IllegalStateException", "boom", listOf("a.B.c(B.kt:1)"))
        assertEquals("round trip", record, CrashRecord.fromJson(record.toJson()))
    }

    @Test
    fun unknownSchemaVersionIsIgnored() {
        assertNull("future schema", CrashRecord.fromJson("""{"schema_version":99}"""))
        assertNull("garbage", CrashRecord.fromJson("not json"))
    }

    @Test
    fun framesIncludeCauseChainAndAreBounded() {
        val inner = IllegalArgumentException("inner")
        val outer = RuntimeException("outer", inner)
        val frames = CrashRecord.framesOf(outer)
        assertTrue("cause marker present", frames.any { it == "Caused by: java.lang.IllegalArgumentException" })
        assertTrue("bounded", frames.size <= CrashRecord.MAX_FRAMES)
    }

    /** Regression: before this, a Java crash left nothing with a stack for the next launch. */
    @Test
    fun crashWritesARecordWithAStack() {
        val dir = Files.createTempDirectory("telemetry").toFile()
        val record = CrashRecorder.record(Thread.currentThread(), IllegalStateException("live crash"), "1.18.0")
        CrashRecorder.write(dir, record)
        val entries = CrashOutbox.collect(dir)
        assertEquals("one waiting record", 1, entries.size)
        val written = entries.single().record
        assertEquals("exception type kept", "java.lang.IllegalStateException", written.exceptionType)
        assertTrue("stack kept", written.frames.isNotEmpty())
        assertTrue("no temp file left", dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    /** Privacy regression: a parse error that embeds provider credentials must not reach the record. */
    @Test
    fun crashRecordNeverCarriesTheExceptionMessage() {
        val error = IllegalStateException("JSON input: {\"user_info\":{\"username\":\"john\",\"password\":\"s3cret\"} at https://MyIptv.Provider.com/x")
        val record = CrashRecorder.record(Thread.currentThread(), error, "1.18.1")
        val json = record.toJson()
        assertNull("message dropped", record.message)
        assertTrue("no password in the stored record", !json.contains("s3cret"))
        assertTrue("no host in the stored record", !json.contains("MyIptv"))
        assertTrue("type kept", json.contains("java.lang.IllegalStateException"))
    }

    @Test
    fun supportCodeUsesOnlyUnambiguousCharacters() {
        val code = SupportCodeGenerator.generate(Random(42))
        assertTrue("valid", SupportCodeGenerator.isValid(code))
        assertTrue("no look-alikes", code.none { it in "01OIL" })
        assertEquals("display grouping", 9, SupportCodeGenerator.display(code).length)
    }
}

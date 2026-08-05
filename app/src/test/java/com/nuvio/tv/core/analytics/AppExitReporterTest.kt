package com.nuvio.tv.core.analytics

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppExitReporterTest {

    @Test
    fun `selects the run that was active when the process exited`() {
        val oldRun = run(startedAt = 100, version = "1.4.16")
        val failedRun = run(startedAt = 200, version = "1.4.17")
        val uploadingRun = run(startedAt = 400, version = "1.4.18")

        assertSame(failedRun, findRunContext(350, listOf(uploadingRun, oldRun, failedRun)))
        assertNull(findRunContext(50, listOf(oldRun, failedRun)))
    }

    @Test
    fun `route context keeps only the stable destination name`() {
        assertEquals("player", safeRouteName("player/secret-stream/title?token=secret"))
        assertEquals("sports_hub", safeRouteName("Sports Hub?account=user"))
        assertNull(safeRouteName(null))
    }

    @Test
    fun `diagnostic text removes urls credentials emails and opaque tokens`() {
        val token = "a".repeat(48)
        val sanitized = sanitizeDiagnosticText(
            "GET https://provider.example/live/user/pass/1\n" +
                "token=abc123\nauthorization: Bearer secret-value\nuser@example.com $token",
            maxChars = 1_000,
        ).orEmpty()

        assertFalse(sanitized.contains("provider.example"))
        assertFalse(sanitized.contains("abc123"))
        assertFalse(sanitized.contains("Bearer"))
        assertFalse(sanitized.contains("secret-value"))
        assertFalse(sanitized.contains("user@example.com"))
        assertFalse(sanitized.contains(token))
        assertTrue(sanitized.contains("[redacted_url]"))
        assertTrue(sanitized.contains("token=[redacted]"))
    }

    @Test
    fun `trace reading and sanitizing are strictly bounded`() {
        val raw = "x".repeat(20_000)
        val excerpt = readBoundedText(ByteArrayInputStream(raw.toByteArray()), maxChars = 6_000)

        assertEquals(6_000, excerpt.length)
        val sanitized = sanitizeDiagnosticText(raw, maxChars = 100).orEmpty()
        assertTrue(sanitized.length <= 100)
        assertFalse(sanitized.contains(raw.take(100)))
    }

    @Test
    fun `run starts tolerate corrupt preference entries`() {
        assertEquals(listOf(10L, 20L), parseRunStarts("20,bad,-1,10,20"))
    }

    @Test
    fun `exit fingerprint distinguishes process and status`() {
        val base = exitFingerprint(100, reason = 6, status = 0, processName = "com.tuvora.tv")

        assertFalse(base == exitFingerprint(100, 6, 9, "com.tuvora.tv"))
        assertFalse(base == exitFingerprint(100, 6, 0, "com.tuvora.tv:player"))
    }

    @Test
    fun `process exits use stable issue grouping and useful severity`() {
        assertEquals("android_process_exit:anr", processExitIssueFingerprint("anr"))
        assertEquals("fatal", processExitIssueLevel("anr"))
        assertEquals("fatal", processExitIssueLevel("native_crash"))
        assertEquals("warning", processExitIssueLevel("low_memory_kill"))
        assertEquals("warning", processExitIssueLevel("excessive_resource_usage"))
    }

    private fun run(startedAt: Long, version: String) = AppRunContext(
        startedAtMs = startedAt,
        versionName = version,
        versionCode = 1,
        lastRoute = null,
        lastAction = null,
        contextUpdatedAtMs = null,
    )
}

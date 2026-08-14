package com.nuvio.tv.core.analytics

import android.app.ActivityManager
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

    @Test
    fun `cached low memory exits remain analytics but do not become issues`() {
        assertFalse(
            shouldPromoteProcessExitToIssue(
                "low_memory_kill",
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
            ),
        )
        assertTrue(
            shouldPromoteProcessExitToIssue(
                "low_memory_kill",
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
            ),
        )
        assertTrue(
            shouldPromoteProcessExitToIssue(
                "native_crash",
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
            ),
        )
    }

    @Test
    fun `binary tombstones retain printable native diagnostics`() {
        val tombstone = byteArrayOf(0, 1) + "SIGSEGV".toByteArray() +
            byteArrayOf(0) + "libmpv.so".toByteArray()

        val excerpt = readDiagnosticExcerpt(ByteArrayInputStream(tombstone), maxChars = 1_000)

        assertTrue(excerpt.contains("SIGSEGV"))
        assertTrue(excerpt.contains("libmpv.so"))
    }

    @Test
    fun `anr excerpt keeps the main thread stack behind a long GC preamble`() {
        val trace = buildString {
            appendLine("----- pid 493 at 2026-08-08 16:22:54 -----")
            appendLine("Cmd line: com.tuvora.tv")
            appendLine("Heap: 19% free, 33MB/41MB; 757361 objects")
            appendLine("Libraries: libmpv.so libplayer.so")
            appendLine("Dumping cumulative Gc timings")
            repeat(400) { appendLine("ProcessMarkStack:\tSum: 868.280ms Avg: 96.475ms") }
            appendLine("\"main\" prio=5 tid=1 Native")
            appendLine("  #00 pc 000000000009d1f4  libmpv.so (mpv_terminate_destroy)")
            appendLine("  at com.nuvio.tv.MainActivity.onStop(MainActivity.kt:42)")
        }

        val excerpt = focusTraceOnMainThread(trace, headChars = 1_500, mainChars = 6_000)

        // The header still carries the evidence that separates a native from a Java-heap death.
        assertTrue(excerpt.contains("Heap: 19% free, 33MB/41MB"))
        assertTrue(excerpt.contains("libmpv.so libplayer.so"))
        // ...and the blocked frame is no longer cut off by the GC histograms.
        assertTrue(excerpt.contains("\"main\" prio=5"))
        assertTrue(excerpt.contains("mpv_terminate_destroy"))
        assertTrue(excerpt.contains(ELISION))
    }

    @Test
    fun `traces without a main thread marker keep the head`() {
        val tombstone = "SIGSEGV\nbacktrace:\n  #00 libGLES_mali.so\n" + "x".repeat(20_000)

        val excerpt = focusTraceOnMainThread(tombstone, headChars = 1_500, mainChars = 6_000)

        assertTrue(excerpt.startsWith("SIGSEGV"))
        assertTrue(excerpt.contains("libGLES_mali.so"))
        assertFalse(excerpt.contains(ELISION))
        assertEquals(7_500, excerpt.length)
    }

    @Test
    fun `a main thread already inside the head slice is not stitched`() {
        val trace = "----- pid 1 -----\n\"main\" prio=5 tid=1 Blocked\n  at Foo.bar(Foo.kt:1)\n"

        val excerpt = focusTraceOnMainThread(trace, headChars = 1_500, mainChars = 6_000)

        assertEquals(trace, excerpt)
        assertFalse(excerpt.contains(ELISION))
    }

    @Test
    fun `memory stat parser survives whatever a vendor ROM returns`() {
        // Java heap plus native heap accounted for barely half of PSS at death, so the graphics
        // bucket is the one that matters — but it must never take the sampler down with it.
        assertEquals(184_320L, parseMemoryStatKb("184320"))
        assertEquals(0L, parseMemoryStatKb("0"))
        assertEquals(184_320L, parseMemoryStatKb("  184320 "))
        assertNull(parseMemoryStatKb(null))
        assertNull(parseMemoryStatKb(""))
        assertNull(parseMemoryStatKb("n/a"))
        assertNull(parseMemoryStatKb("12.5"))
        assertNull(parseMemoryStatKb("-1"))
    }

    @Test
    fun `process stat parser handles names containing spaces and parentheses`() {
        val stat = "123 (Tuvora worker (1)) S 1 2 3 4 5 6 7 8 9 10 120 30 0 0"

        assertEquals(150L, parseProcessCpuTicks(stat))
        assertNull(parseProcessCpuTicks("malformed"))
    }

    @Test
    fun `process inventory is privacy safe and records importance`() {
        val processes = listOf(
            ProcessCpuSample(
                pid = 12,
                name = safeProcessName("com.tuvora.mobile", "com.tuvora.mobile"),
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                cpuTicks = 100,
            ),
            ProcessCpuSample(
                pid = 13,
                name = safeProcessName("com.tuvora.mobile", "com.tuvora.mobile:player"),
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                cpuTicks = 200,
            ),
        )

        assertEquals("main:cached:12,:player:service:13", formatProcessInventory(processes))
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

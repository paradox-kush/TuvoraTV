package com.nuvio.tv.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Our own deliberate process kill must be counted but never filed as a fatal `signaled` issue. */
class SelfExitPolicyTest {
    private val foreground = 100
    private val cached = 400

    @Test
    fun `a self exit tagged user_exit at foreground is counted but not promoted`() {
        assertEquals(
            "our own SIGKILL at foreground is a deliberate exit, not a crash",
            AppExitReportPolicy.Outcome.CountOnly("user_exit"),
            AppExitReportPolicy.classify("signaled", foreground, "user_exit"),
        )
    }

    @Test
    fun `restart tag from AppRestarter is not promoted`() {
        assertEquals(
            "AppRestarter kills the process on purpose",
            AppExitReportPolicy.Outcome.CountOnly("restart"),
            AppExitReportPolicy.classify("signaled", foreground, "restart"),
        )
    }

    @Test
    fun `an untagged foreground SIGKILL still promotes`() {
        assertEquals(
            "a real kill must never be hidden",
            AppExitReportPolicy.Outcome.Report(promote = true),
            AppExitReportPolicy.classify("signaled", foreground, null),
        )
    }

    @Test
    fun `an untagged cached SIGKILL is still dropped`() {
        assertEquals(
            "cached reclaim stays normal lifecycle",
            AppExitReportPolicy.Outcome.Drop,
            AppExitReportPolicy.classify("signaled", cached, null),
        )
    }

    @Test
    fun `a tag never excuses a crash or anr that happens to share the record`() {
        assertEquals(
            "native crash with a tag still reports",
            AppExitReportPolicy.Outcome.Report(promote = true),
            AppExitReportPolicy.classify("native_crash", foreground, "user_exit"),
        )
        assertEquals(
            "anr with a tag still reports",
            AppExitReportPolicy.Outcome.Report(promote = true),
            AppExitReportPolicy.classify("anr", foreground, "restart"),
        )
    }

    @Test
    fun `foreground low memory kill still reports and promotes`() {
        assertEquals(
            "OS reclaim is not ours",
            AppExitReportPolicy.Outcome.Report(promote = true),
            AppExitReportPolicy.classify("low_memory_kill", foreground, null),
        )
    }

    @Test
    fun `marker round trips and rejects unknown tags`() {
        assertEquals("encode", "restart|123|456", SelfExitMarker.encode("restart", 123, 456))
        assertEquals("decode", SelfExitMarker("icon_change", 7, 99L), SelfExitMarker.decode("icon_change|7|99"))
        assertNull("tags are an allow-list", SelfExitMarker.encode("rm -rf", 1, 1))
        assertNull("unknown tag", SelfExitMarker.decode("bogus|1|2"))
        assertNull("bad pid", SelfExitMarker.decode("user_exit|x|2"))
        assertNull("null raw", SelfExitMarker.decode(null))
    }

    @Test
    fun `marker matches only the same pid within the window`() {
        val m = SelfExitMarker("user_exit", pid = 2973, atMs = 1_000_000L)
        assertEquals("same pid, just after", "user_exit", SelfExitMarker.tagFor(m, 2973, 1_000_050L))
        assertNull("different pid is a different process", SelfExitMarker.tagFor(m, 2974, 1_000_050L))
        assertNull(
            "a stale marker must not mask a later kill",
            SelfExitMarker.tagFor(m, 2973, 1_000_000L + SelfExitMarker.MATCH_WINDOW_MS + 1),
        )
        assertNull("an exit before the marker is not ours", SelfExitMarker.tagFor(m, 2973, 900_000L))
        assertNull("no marker", SelfExitMarker.tagFor(null, 2973, 1_000_050L))
    }
}

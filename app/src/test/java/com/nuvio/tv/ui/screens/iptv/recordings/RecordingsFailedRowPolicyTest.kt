package com.nuvio.tv.ui.screens.iptv.recordings

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.RecordingAction
import com.nuvio.tv.core.recordings.api.RecordingActionsPolicy
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingRow
import com.nuvio.tv.core.recordings.api.RecordingSection
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.RecordingsPageModel
import com.nuvio.tv.ui.screens.iptv.recordings.RecordingRowTextPolicy.ProblemTextKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Failed row on the Onn: cut-off reason, no highlight, dead OK, DOWN left the app. */
class RecordingsFailedRowPolicyTest {

    private fun row(id: String, section: RecordingSection, status: RecordingStatus, problem: RecordingProblem? = null) =
        RecordingRow(
            id = id, section = section, title = "Show", episode = null, channelName = "XC1",
            programmeStartMs = 0, programmeEndMs = 1, sizeBytes = 0, status = status,
            mode = CaptureMode.CATCH_UP, problem = problem, description = null,
            scheduleId = null, recordingId = id, canRetry = true, retryPending = false,
        )

    private fun page(vararg rows: RecordingRow) = RecordingsPageModel(rows.groupBy { it.section })

    private val failed = row("f", RecordingSection.FAILED, RecordingStatus.FAILED, RecordingProblem.NO_SPACE)
    private val done = row("d", RecordingSection.DONE, RecordingStatus.DONE)
    private val scheduled = row("s", RecordingSection.SCHEDULED, RecordingStatus.SCHEDULED)

    // --- initial focus by sections
    @Test
    fun `entry is the first row of the first non-empty section`() {
        assertEquals("s", RecordingsFocusPolicy.entryFor(page(failed, done, scheduled)))
        assertEquals("d", RecordingsFocusPolicy.entryFor(page(failed, done)))
    }

    @Test
    fun `a page with only a failed row lands on the failed row`() {
        assertEquals("f", RecordingsFocusPolicy.entryFor(page(failed)))
    }

    @Test
    fun `an empty page has no entry`() {
        assertNull(RecordingsFocusPolicy.entryFor(page()))
    }

    // --- focus after removal / retry (moves to a neighbouring row, never a banner)
    @Test
    fun `deleting the only failed row focuses the last row before it`() {
        val before = listOf("s", "d", "f")
        assertEquals("d", RecordingsFocusPolicy.afterRemoval(before, "f", listOf("s", "d")))
    }

    @Test
    fun `retrying a failed row that becomes scheduled never targets a banner`() {
        val target = RecordingsFocusPolicy.afterRemoval(listOf("d", "f"), "f", listOf("f2", "d"))
        assertEquals("f2".takeIf { false } ?: "d", target)
    }

    // --- fence at the edges
    private val ids = listOf("s", "d", "f")

    @Test
    fun `down on the last row is swallowed`() {
        assertEquals(RecordingsFocusPolicy.Vertical.STAY, RecordingsFocusPolicy.vertical(ids, "f", down = true, bannerShown = true))
        assertEquals(RecordingsFocusPolicy.Vertical.STAY, RecordingsFocusPolicy.vertical(ids, "f", down = true, bannerShown = false))
    }

    @Test
    fun `up on the first row goes to the banner when there is one and is swallowed otherwise`() {
        assertEquals(RecordingsFocusPolicy.Vertical.TO_BANNER, RecordingsFocusPolicy.vertical(ids, "s", down = false, bannerShown = true))
        assertEquals(RecordingsFocusPolicy.Vertical.STAY, RecordingsFocusPolicy.vertical(ids, "s", down = false, bannerShown = false))
    }

    @Test
    fun `inside the list focus moves normally`() {
        assertEquals(RecordingsFocusPolicy.Vertical.MOVE, RecordingsFocusPolicy.vertical(ids, "d", down = true, bannerShown = true))
        assertEquals(RecordingsFocusPolicy.Vertical.MOVE, RecordingsFocusPolicy.vertical(ids, "d", down = false, bannerShown = true))
        assertEquals(RecordingsFocusPolicy.Vertical.MOVE, RecordingsFocusPolicy.vertical(ids, "f", down = false, bannerShown = true))
    }

    @Test
    fun `a single row is fenced both ways`() {
        assertEquals(RecordingsFocusPolicy.Vertical.STAY, RecordingsFocusPolicy.vertical(listOf("f"), "f", down = true, bannerShown = false))
        assertEquals(RecordingsFocusPolicy.Vertical.STAY, RecordingsFocusPolicy.vertical(listOf("f"), "f", down = false, bannerShown = false))
    }

    // --- focus lost when a row moves section (Scheduled -> Recording -> Failed while the viewer sits there)
    @Test
    fun `focus lost with rows on the page is put back on the remembered row`() {
        assertEquals("f", RecordingsFocusPolicy.lostFocusTarget(false, false, "f", ids))
        assertEquals("s", RecordingsFocusPolicy.lostFocusTarget(false, false, "gone", ids))
    }

    @Test
    fun `nothing is done while the list has focus or an overlay is open or there are no rows`() {
        assertNull(RecordingsFocusPolicy.lostFocusTarget(true, false, "f", ids))
        assertNull(RecordingsFocusPolicy.lostFocusTarget(false, true, "f", ids))
        assertNull(RecordingsFocusPolicy.lostFocusTarget(false, false, "f", emptyList()))
    }

    // --- a Failed row is a first-class row
    @Test
    fun `every status is focusable`() {
        RecordingStatus.entries.forEach { assertTrue("$it", RecordingRowTextPolicy.focusable(it)) }
    }

    @Test
    fun `a failed row opens details with try again and delete and defaults to close`() {
        val actions = RecordingActionsPolicy.actionsFor(failed, fileAvailable = false)
        assertEquals(listOf(RecordingAction.RETRY, RecordingAction.DELETE), actions)
        assertEquals(RecordingDetailsFocusPolicy.Target.CLOSE, RecordingDetailsFocusPolicy.defaultFocus(failed.status, actions))
    }

    // --- text rule
    @Test
    fun `a failed row shows its reason on its own wrapping line`() {
        assertTrue(RecordingRowTextPolicy.reasonOnOwnLine(failed))
        assertFalse(RecordingRowTextPolicy.reasonOnOwnLine(done))
        assertFalse(RecordingRowTextPolicy.reasonOnOwnLine(failed.copy(problem = null)))
    }

    // --- the space reasons (space policy change)

    @Test
    fun `a done row cut short by space says it kept what was saved and puts that on its own line`() {
        val cut = done.copy(problem = RecordingProblem.NO_SPACE, sizeBytes = 48_000_000L)
        assertEquals(ProblemTextKind.CUT_SHORT, RecordingRowTextPolicy.problemTextKind(cut))
        assertTrue(RecordingRowTextPolicy.reasonOnOwnLine(cut))
    }

    @Test
    fun `a failed no-space row with both numbers names them`() {
        val r = failed.copy(problemNeedBytes = 1_100_000_000L, problemFreeBytes = 900_000_000L)
        assertEquals(ProblemTextKind.NO_SPACE_KNOWN, RecordingRowTextPolicy.problemTextKind(r))
    }

    @Test
    fun `without both numbers or for other problems the generic sentence is used`() {
        assertEquals(ProblemTextKind.GENERIC, RecordingRowTextPolicy.problemTextKind(failed.copy(problemNeedBytes = 5L)))
        assertEquals(ProblemTextKind.GENERIC, RecordingRowTextPolicy.problemTextKind(failed.copy(problem = RecordingProblem.NETWORK, problemNeedBytes = 5L, problemFreeBytes = 4L)))
    }

    @Test
    fun `an amount inside a sentence is never empty`() {
        assertEquals("0 MB", RecordingRowFormat.amount(0L))
        assertEquals("1.0 GB", RecordingRowFormat.amount(1_100_000_000L))
        assertEquals("858 MB", RecordingRowFormat.amount(900_000_000L))
    }

    @Test
    fun `the reason may wrap over several lines`() {
        assertTrue(RecordingRowTextPolicy.REASON_MAX_LINES >= 4)
    }

    private val screenSource: String by lazy {
        listOf("src/main/java", "app/src/main/java")
            .map { File(it, "com/nuvio/tv/ui/screens/iptv/recordings/RecordingsScreen.kt") }
            .first { it.exists() }.readText()
    }

    private fun block(name: String): String {
        val b = screenSource.indexOf("// $name-BEGIN")
        val e = screenSource.indexOf("// $name-END")
        assertTrue("marker $name missing", b >= 0 && e > b)
        return screenSource.substring(b, e)
    }

    @Test
    fun `the row reason text never ellipsizes and uses the policy line count`() {
        val b = block("ROW-REASON")
        assertFalse(b, b.contains("Ellipsis"))
        assertTrue(b.contains("RecordingRowTextPolicy.REASON_MAX_LINES"))
    }

    @Test
    fun `the details sheet shows the reason in full`() {
        val b = block("DETAIL-REASON")
        assertFalse(b, b.contains("Ellipsis"))
        assertFalse(b, b.contains("maxLines"))
    }
}

package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingSection
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.RecordingsPagePolicy
import com.nuvio.tv.core.recordings.api.RecordingsState
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class RecordingsPagePolicyTest {

    private fun state(
        schedules: List<com.nuvio.tv.core.recordings.api.RecordingSchedule> = emptyList(),
        recordings: List<com.nuvio.tv.core.recordings.api.Recording> = emptyList(),
    ) = RecordingsState(schedules = schedules, recordings = recordings, loaded = true)

    @Test
    fun `an empty state is an empty page`() {
        val page = RecordingsPagePolicy.build(state(), emptyMap())
        assertTrue(page.isEmpty)
        RecordingSection.entries.forEach { assertTrue("$it", page.rows(it).isEmpty()) }
    }

    @Test
    fun `sections are drawn scheduled then recording then done then failed`() {
        assertEquals(listOf(RecordingSection.SCHEDULED, RecordingSection.RECORDING, RecordingSection.DONE, RecordingSection.FAILED), RecordingsPagePolicy.ORDER)
    }

    @Test
    fun `a schedule becomes a scheduled row that carries what the line needs`() {
        val s = schedule(id = "a", mode = CaptureMode.CATCH_UP, title = "News", episode = "S01E02", description = "Top stories")
        val row = RecordingsPagePolicy.build(state(schedules = listOf(s)), emptyMap()).rows(RecordingSection.SCHEDULED).single()
        assertEquals("News", row.title)
        assertEquals("S01E02", row.episode)
        assertEquals("Channel ch1", row.channelName)
        assertEquals(s.programmeStartMs, row.programmeStartMs)
        assertEquals(s.programmeEndMs, row.programmeEndMs)
        assertEquals(RecordingStatus.SCHEDULED, row.status)
        assertEquals("a", row.scheduleId)
        assertEquals(null, row.recordingId)
        assertEquals("Top stories", row.description)
        assertFalse(row.retryPending)
    }

    @Test
    fun `a capturing schedule is shown once as recording and not also as scheduled`() {
        val s = schedule(id = "a", mode = CaptureMode.CATCH_UP)
        val r = recording("rec:a", status = RecordingStatus.RECORDING, size = 10).copy(scheduleId = "a")
        val page = RecordingsPagePolicy.build(state(listOf(s), listOf(r)), emptyMap())
        assertTrue(page.rows(RecordingSection.SCHEDULED).isEmpty())
        val row = page.rows(RecordingSection.RECORDING).single()
        assertEquals("rec:a", row.recordingId)
        assertEquals("Cancel needs the schedule id of a running capture", "a", row.scheduleId)
    }

    @Test
    fun `live progress replaces the stored size of a recording row`() {
        val r = recording("rec:a", status = RecordingStatus.RECORDING, size = 100).copy(scheduleId = "a")
        val row = RecordingsPagePolicy.build(state(recordings = listOf(r)), mapOf("rec:a" to 5_000L)).rows(RecordingSection.RECORDING).single()
        assertEquals(5_000L, row.sizeBytes)
    }

    @Test
    fun `progress never makes a recording look smaller than what is stored`() {
        val r = recording("rec:a", status = RecordingStatus.RECORDING, size = 9_000).copy(scheduleId = "a")
        val row = RecordingsPagePolicy.build(state(recordings = listOf(r)), mapOf("rec:a" to 5_000L)).rows(RecordingSection.RECORDING).single()
        assertEquals(9_000L, row.sizeBytes)
    }

    @Test
    fun `done rows list the newest programme first and keep their size`() {
        val old = recording("old", start = NOW - 3 * DAY, size = 111)
        val new = recording("new", start = NOW - DAY, size = 222)
        val rows = RecordingsPagePolicy.build(state(recordings = listOf(old, new)), emptyMap()).rows(RecordingSection.DONE)
        assertEquals(listOf("new", "old"), rows.map { it.recordingId })
        assertEquals(222L, rows.first().sizeBytes)
    }

    @Test
    fun `scheduled rows list the soonest programme first`() {
        val later = schedule(id = "later", start = NOW + 5 * HOUR)
        val sooner = schedule(id = "sooner", start = NOW + HOUR)
        val rows = RecordingsPagePolicy.build(state(schedules = listOf(later, sooner)), emptyMap()).rows(RecordingSection.SCHEDULED)
        assertEquals(listOf("sooner", "later"), rows.map { it.scheduleId })
    }

    @Test
    fun `failed and cancelled both land in failed with their problem`() {
        val failed = recording("f", status = RecordingStatus.FAILED).copy(problem = RecordingProblem.NO_SPACE)
        val cancelled = recording("c", status = RecordingStatus.CANCELLED, start = NOW - 2 * DAY).copy(problem = RecordingProblem.CANCELLED_BY_USER)
        val rows = RecordingsPagePolicy.build(state(recordings = listOf(cancelled, failed)), emptyMap()).rows(RecordingSection.FAILED)
        assertEquals(setOf("f", "c"), rows.map { it.recordingId }.toSet())
        assertEquals(RecordingProblem.NO_SPACE, rows.first { it.recordingId == "f" }.problem)
        assertEquals(RecordingStatus.CANCELLED, rows.first { it.recordingId == "c" }.status)
    }

    @Test
    fun `retry is possible only when the failed row kept its schedule`() {
        val kept = recording("k", status = RecordingStatus.FAILED).copy(retrySource = schedule(id = "k"))
        val lost = recording("l", status = RecordingStatus.FAILED)
        val rows = RecordingsPagePolicy.build(state(recordings = listOf(kept, lost)), emptyMap()).rows(RecordingSection.FAILED)
        assertTrue(rows.first { it.recordingId == "k" }.canRetry)
        assertFalse(rows.first { it.recordingId == "l" }.canRetry)
    }

    @Test
    fun `a schedule that failed once and waits for its next try says so`() {
        val s = schedule(id = "a", mode = CaptureMode.CATCH_UP, attempts = 1, nextTry = NOW + HOUR)
        val row = RecordingsPagePolicy.build(state(schedules = listOf(s)), emptyMap()).rows(RecordingSection.SCHEDULED).single()
        assertTrue(row.retryPending)
    }

    @Test
    fun `a schedule whose recording already finished is not shown as scheduled`() {
        val s = schedule(id = "a")
        val done = recording("rec:a", status = RecordingStatus.DONE).copy(scheduleId = "a")
        val page = RecordingsPagePolicy.build(state(listOf(s), listOf(done)), emptyMap())
        assertTrue(page.rows(RecordingSection.SCHEDULED).isEmpty())
        assertEquals(1, page.rows(RecordingSection.DONE).size)
    }

    @Test
    fun `row ids are unique across the page`() {
        val s = schedule(id = "a")
        val r = recording("a", status = RecordingStatus.DONE)
        val ids = RecordingSection.entries.flatMap { RecordingsPagePolicy.build(state(listOf(s), listOf(r)), emptyMap()).rows(it) }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}

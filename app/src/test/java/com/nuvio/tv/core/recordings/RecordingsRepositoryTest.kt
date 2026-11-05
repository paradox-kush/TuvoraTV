package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.ArmKind
import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.FireResult
import com.nuvio.tv.core.recordings.api.LowSpacePolicy
import com.nuvio.tv.core.recordings.api.RecordingAlarms
import com.nuvio.tv.core.recordings.api.RecordingCapabilities
import com.nuvio.tv.core.recordings.api.RecordingFiles
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingSaver
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.SaveOutcome
import com.nuvio.tv.core.recordings.api.SaveRequest
import com.nuvio.tv.core.recordings.api.ScheduleResult
import com.nuvio.tv.core.recordings.internal.FireEntry
import com.nuvio.tv.core.recordings.internal.FireLedgerState
import com.nuvio.tv.core.recordings.internal.FireState
import com.nuvio.tv.core.recordings.internal.RecordingSchedulePolicy
import com.nuvio.tv.core.recordings.internal.RecordingsCodec
import com.nuvio.tv.core.recordings.internal.RecordingsDocument
import com.nuvio.tv.core.recordings.internal.RecordingsRepository
import com.nuvio.tv.core.recordings.internal.StoredRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private class FakeAlarms : RecordingAlarms {
    val armed = LinkedHashMap<String, Long>()
    var armCalls = 0
    var exact = true
    override fun arm(scheduleId: String, atMs: Long): ArmKind {
        armCalls++
        armed[scheduleId] = atMs
        return if (exact) ArmKind.EXACT else ArmKind.INEXACT
    }
    override fun disarm(scheduleId: String) { armed.remove(scheduleId) }
    override fun exactAllowed() = exact
}

private class FakeFiles : RecordingFiles {
    val texts = HashMap<String, String>()
    val aside = ArrayList<Pair<String, String>>()
    val sizes = HashMap<String, Long>()
    val deleted = ArrayList<String>()
    val writes = ArrayList<String>()
    var free = 100L * 1024 * 1024 * 1024
    override suspend fun read(name: String) = texts[name]
    override suspend fun write(name: String, text: String) { writes += name; texts[name] = text }
    override suspend fun keepAside(name: String) { texts.remove(name)?.let { aside += name to it } }
    override fun pathFor(fileName: String) = "/rec/$fileName"
    override fun freeBytes() = free
    override fun sizeOf(path: String) = sizes[path] ?: 0L
    override suspend fun delete(path: String) { deleted += path; sizes.remove(path) }
}

private class FakeSaver : RecordingSaver {
    val calls = ArrayList<SaveRequest>()
    var next: suspend (SaveRequest, (Long) -> Unit) -> SaveOutcome = { _, progress -> progress(10); SaveOutcome.Done(2_000_000_000L) }
    override suspend fun save(req: SaveRequest, progress: (Long) -> Unit): SaveOutcome {
        calls += req
        return next(req, progress)
    }
}

private val catchUpChannel = channel(catchUp = xtream(3))

private class Rig(
    capabilities: RecordingCapabilities = RecordingCapabilities.ANDROID,
    policy: LowSpacePolicy = LowSpacePolicy.STOP,
    val files: FakeFiles = FakeFiles(),
) {
    val alarms = FakeAlarms()
    val saver = FakeSaver()
    val clock = TestTimeSource()
    var low = policy
    var wall = NOW
    val repo = RecordingsRepository(files, alarms, saver, capabilities, lowSpacePolicy = { low }, timeSource = clock, clock = { wall })
}

/** A catch-up programme that ended 1 h ago: its save fire time (end + 5 min + 10 min) is past. */
private fun dueCatchUp(id: String = "s1") = schedule(id = id, mode = CaptureMode.CATCH_UP, start = NOW - 2 * HOUR, end = NOW - HOUR, ch = catchUpChannel)

private fun stateText(vararg s: com.nuvio.tv.core.recordings.api.RecordingSchedule, recs: List<com.nuvio.tv.core.recordings.api.Recording> = emptyList()) =
    RecordingsCodec.encode(RecordingsDocument(schedules = s.map { StoredRow.of(it) }, recordings = recs.map { StoredRow.of(it) }))

class RecordingsRepositoryTest {

    @Test
    fun `scheduling a catch-up programme arms its save time and persists it`() = runTest {
        val rig = Rig()
        val res = rig.repo.scheduleProgramme(catchUpChannel, "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        val scheduled = assertNotNull(res as? ScheduleResult.Scheduled)
        assertEquals(CaptureMode.CATCH_UP, scheduled.schedule.mode)
        assertEquals(ArmKind.EXACT, scheduled.arm)
        assertEquals(RecordingSchedulePolicy.fireTimeMs(scheduled.schedule), rig.alarms.armed[scheduled.schedule.id])
        val saved = RecordingsCodec.decode(rig.files.texts["recordings.json"])!!
        assertEquals(1, saved.scheduleValues.size)
        assertEquals(1, rig.repo.state.value.schedules.size)
    }

    @Test
    fun `scheduling the same programme twice is a duplicate`() = runTest {
        val rig = Rig()
        rig.repo.scheduleProgramme(catchUpChannel, "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        val again = rig.repo.scheduleProgramme(catchUpChannel, "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        assertTrue(again is ScheduleResult.Duplicate)
        assertEquals(1, rig.repo.state.value.schedules.size)
    }

    @Test
    fun `a live-only programme cannot be recorded where live capture is off`() = runTest {
        val rig = Rig(capabilities = RecordingCapabilities(canRecordLive = false, canSaveCatchUp = true))
        val res = rig.repo.scheduleProgramme(channel(), "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        assertEquals(ScheduleResult.CannotRecord, res)
        assertTrue(rig.repo.state.value.schedules.isEmpty())
    }

    @Test
    fun `an inexact alarm is reported so the UI can say it may start late`() = runTest {
        val rig = Rig().also { it.alarms.exact = false }
        val res = rig.repo.scheduleProgramme(catchUpChannel, "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW) as ScheduleResult.Scheduled
        assertEquals(ArmKind.INEXACT, res.arm)
        assertEquals(false, rig.repo.state.value.exactAllowed)
    }

    @Test
    fun `fire saves once and a second concurrent fire is ignored`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        val gate = CompletableDeferred<Unit>()
        rig.saver.next = { _, _ -> gate.await(); SaveOutcome.Done(5_000_000_000L) }
        val first = async { rig.repo.fire("s1", NOW) }
        advanceUntilIdle()
        val second = rig.repo.fire("s1", NOW)
        assertEquals(FireResult.AlreadyHandled, second)
        gate.complete(Unit)
        assertEquals(FireResult.Saved, first.await())
        assertEquals("a re-delivered alarm after the save finds nothing to do", FireResult.NotFound, rig.repo.fire("s1", NOW))
        assertEquals(1, rig.saver.calls.size)
        val rec = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, rec.status)
        assertEquals(5_000_000_000L, rec.sizeBytes)
        assertTrue(rig.repo.state.value.schedules.isEmpty())
    }

    private fun v1(text: String) = text.replace("\"schemaVersion\":2", "\"schemaVersion\":1")

    @Test
    fun `a v1 state with the old ledger file is migrated once and recovers its RUNNING claim`() = runTest {
        val files = FakeFiles()
        files.texts["recordings.json"] = v1(stateText(dueCatchUp()))
        val ledger = FireLedgerState(listOf(FireEntry("s1", FireState.RUNNING, "s1#1", 5L)), nextSeq = 2)
        files.texts["recordings-ledger.json"] = kotlinx.serialization.json.Json.encodeToString(FireLedgerState.serializer(), ledger)
        val rig = Rig(files = files)
        rig.repo.start(NOW)
        val s = rig.repo.state.value.schedules.single()
        assertEquals("the lost run used an attempt", 1, s.attempts)
        assertEquals(NOW + RecordingSchedulePolicy.RETRY_MS, s.nextTryMs)
        assertEquals(s.nextTryMs, rig.alarms.armed["s1"])
        assertTrue("recovery re-arms, it does not start a save", rig.saver.calls.isEmpty())
        val doc = RecordingsCodec.decode(files.texts["recordings.json"])!!
        assertEquals("rewritten as v2", 2, doc.schemaVersion)
        assertEquals("the ledger now lives in the document", FireState.FAILED, doc.ledgerState.entries.single().state)
        assertNull("the old ledger file is gone after the one-time migration", files.texts["recordings-ledger.json"])
        assertEquals("recordings-ledger.json", files.aside.single().first)
    }

    @Test
    fun `a fire commits the ledger and the state in ONE write so a crash cannot split them`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        assertEquals(FireResult.Saved, rig.repo.fire("s1", NOW))
        assertTrue(rig.files.writes.isNotEmpty())
        assertTrue("no second file: ${rig.files.writes.toSet()}", rig.files.writes.all { it == "recordings.json" })
        assertNull(rig.files.texts["recordings-ledger.json"])
        val doc = RecordingsCodec.decode(rig.files.texts["recordings.json"])!!
        assertEquals(FireState.DONE, doc.ledgerState.entries.single { it.scheduleId == "s1" }.state)
        assertEquals(RecordingStatus.DONE, doc.recordingValues.single().status)
    }

    @Test
    fun `a RECORDING row whose ledger entry says DONE is finished from the file on load`() = runTest {
        val files = FakeFiles()
        val row = recording("rec:s1", status = RecordingStatus.RECORDING, size = 0).copy(scheduleId = "s1", fileName = "Show.ts")
        val doc = RecordingsDocument(
            schedules = listOf(StoredRow.of(dueCatchUp())), recordings = listOf(StoredRow.of(row)),
        ).withLedger(FireLedgerState(listOf(FireEntry("s1", FireState.DONE, "s1#1", 1L)), 2))
        files.texts["recordings.json"] = RecordingsCodec.encode(doc)
        files.sizes["/rec/Show.ts"] = 777L
        val rig = Rig(files = files)
        rig.repo.start(NOW)
        val rec = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.DONE, rec.status)
        assertEquals(777L, rec.sizeBytes)
        assertTrue("a finished capture has no schedule to re-arm", rig.repo.state.value.schedules.isEmpty())
        assertNull("and is never re-armed", rig.alarms.armed["s1"])
    }

    @Test
    fun `a RECORDING row whose ledger entry is not RUNNING is retried not left recording forever`() = runTest {
        val files = FakeFiles()
        val row = recording("rec:s1", status = RecordingStatus.RECORDING, size = 0).copy(scheduleId = "s1", fileName = "Show.ts")
        files.texts["recordings.json"] = RecordingsCodec.encode(
            RecordingsDocument(schedules = listOf(StoredRow.of(dueCatchUp())), recordings = listOf(StoredRow.of(row))),
        )
        val rig = Rig(files = files)
        rig.repo.start(NOW)
        assertTrue(rig.repo.state.value.recordings.none { it.status == RecordingStatus.RECORDING })
        val s = rig.repo.state.value.schedules.single()
        assertEquals(1, s.attempts)
        assertEquals(NOW + RecordingSchedulePolicy.RETRY_MS, rig.alarms.armed["s1"])
    }

    @Test
    fun `delete after a cold start recovers lost runs with the real clock not zero`() = runTest {
        val files = FakeFiles()
        files.texts["recordings.json"] = v1(RecordingsCodec.encode(
            RecordingsDocument(schedules = listOf(StoredRow.of(dueCatchUp())), recordings = listOf(StoredRow.of(recording("old")))),
        ))
        files.texts["recordings-ledger.json"] = kotlinx.serialization.json.Json.encodeToString(
            FireLedgerState.serializer(), FireLedgerState(listOf(FireEntry("s1", FireState.RUNNING, "s1#1", 5L)), 2),
        )
        val rig = Rig(files = files)
        rig.wall = NOW + 3 * HOUR
        rig.repo.deleteRecording("old")
        assertEquals(NOW + 3 * HOUR + RecordingSchedulePolicy.RETRY_MS, rig.repo.state.value.schedules.single().nextTryMs)
    }

    @Test
    fun `cancel after a cold start stamps endedAtMs from the real clock`() = runTest {
        val files = FakeFiles()
        val exhausted = dueCatchUp().copy(attempts = RecordingSchedulePolicy.MAX_ATTEMPTS - 1)
        val row = recording("rec:s1", status = RecordingStatus.RECORDING, size = 0).copy(scheduleId = "s1", fileName = "Show.ts")
        files.texts["recordings.json"] = RecordingsCodec.encode(
            RecordingsDocument(schedules = listOf(StoredRow.of(exhausted)), recordings = listOf(StoredRow.of(row)))
                .withLedger(FireLedgerState(listOf(FireEntry("s1", FireState.RUNNING, "s1#1", 5L)), 2)),
        )
        val rig = Rig(files = files)
        rig.wall = NOW + 3 * HOUR
        rig.repo.cancel("s1")
        val rec = rig.repo.state.value.recordings.single()
        assertEquals("not 0 (1970)", NOW + 3 * HOUR, rec.endedAtMs)
    }

    @Test
    fun `not enough space refuses the save with NO_SPACE and no retry`() = runTest {
        val rig = Rig()
        rig.files.free = 400L * 1024 * 1024
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        val res = rig.repo.fire("s1", NOW)
        assertEquals(FireResult.Failed(RecordingProblem.NO_SPACE), res)
        assertTrue(rig.saver.calls.isEmpty())
        val rec = rig.repo.state.value.recordings.single()
        assertEquals(RecordingStatus.FAILED, rec.status)
        assertEquals(RecordingProblem.NO_SPACE, rec.problem)
        assertTrue(rig.repo.state.value.schedules.isEmpty())
    }

    @Test
    fun `delete oldest frees room by removing old files and rows then saves`() = runTest {
        val rig = Rig(policy = LowSpacePolicy.DELETE_OLDEST)
        val old = recording("old1", start = NOW - 10 * DAY, size = 50L * 1024 * 1024 * 1024)
        val old2 = recording("old2", start = NOW - 9 * DAY, size = 50L * 1024 * 1024 * 1024)
        rig.files.texts["recordings.json"] = stateText(dueCatchUp(), recs = listOf(old, old2))
        rig.files.free = 1L * 1024 * 1024 * 1024 // 1 GiB free, an hour at 8 Mbit/s needs ~3.6 GB + floor
        rig.files.sizes["/rec/old1.ts"] = old.sizeBytes
        val res = rig.repo.fire("s1", NOW)
        assertEquals(FireResult.Saved, res)
        assertEquals("only the oldest file", listOf("/rec/old1.ts"), rig.files.deleted.filter { it.startsWith("/rec/old") })
        val ids = rig.repo.state.value.recordings.map { it.id }
        assertTrue("oldest removed only: $ids", "old1" !in ids && "old2" in ids)
    }

    @Test
    fun `an HLS-only provider is UNSUPPORTED_FORMAT and is not retried`() = runTest {
        val rig = Rig()
        val hlsOnly = schedule(
            id = "s1", mode = CaptureMode.CATCH_UP, start = NOW - 2 * HOUR, end = NOW - HOUR,
            ch = channel(catchUp = com.nuvio.tv.core.recordings.api.RecordingCatchUp.M3u("flussonic", null, 3))
                .copy(liveUrl = "http://f.example/stream1/index.m3u8"),
        )
        rig.files.texts["recordings.json"] = stateText(hlsOnly)
        val res = rig.repo.fire("s1", NOW)
        assertEquals(FireResult.Failed(RecordingProblem.UNSUPPORTED_FORMAT), res)
        assertTrue(rig.saver.calls.isEmpty())
        assertTrue("no retry is armed", rig.repo.state.value.schedules.isEmpty())
        assertNull(rig.alarms.armed["s1"])
    }

    @Test
    fun `a channel without catch-up is NO_CATCH_UP`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp().copy(channel = channel(catchUp = com.nuvio.tv.core.recordings.api.RecordingCatchUp.Xtream("http://p.example:8080", "", "", 42, 3, null))))
        assertEquals(FireResult.Failed(RecordingProblem.NO_CATCH_UP), rig.repo.fire("s1", NOW))
    }

    @Test
    fun `an offline failure does not consume an attempt and is retried`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        rig.saver.next = { _, _ -> SaveOutcome.Failed(RecordingProblem.NETWORK, offline = true, writtenBytes = 0, rangeSupported = false) }
        val res = rig.repo.fire("s1", NOW)
        assertTrue(res is FireResult.Retrying)
        val s = rig.repo.state.value.schedules.single()
        assertEquals(0, s.attempts)
        assertEquals(NOW + RecordingSchedulePolicy.RETRY_MS, rig.alarms.armed["s1"])
        assertTrue("a retry leaves no failed row", rig.repo.state.value.recordings.isEmpty())
    }

    @Test
    fun `an online failure uses an attempt and the third gives up as FAILED`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp().copy(attempts = 2))
        rig.saver.next = { _, _ -> SaveOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, offline = false, writtenBytes = 0, rangeSupported = false) }
        assertEquals(FireResult.Failed(RecordingProblem.PROVIDER_REFUSED), rig.repo.fire("s1", NOW))
        val rec = rig.repo.state.value.recordings.single()
        assertEquals(RecordingProblem.PROVIDER_REFUSED, rec.problem)
        assertNotNull(rec.retrySource, "Retry needs the schedule back")
    }

    @Test
    fun `a partial file is resumed on retry only when the server supports ranges`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        rig.saver.next = { req, _ ->
            rig.files.sizes[req.targetPath + ".part"] = 3_000_000L
            SaveOutcome.Failed(RecordingProblem.STALLED, offline = false, writtenBytes = 3_000_000L, rangeSupported = true)
        }
        assertTrue(rig.repo.fire("s1", NOW) is FireResult.Retrying)
        rig.saver.next = { _, _ -> SaveOutcome.Done(9L) }
        rig.repo.fire("s1", NOW + RecordingSchedulePolicy.RETRY_MS)
        assertEquals(3_000_000L, rig.saver.calls.last().resumeFromBytes)
    }

    @Test
    fun `rearmAll arms every waiting schedule from its recomputed fire time`() = runTest {
        val rig = Rig()
        val future = schedule(id = "f", mode = CaptureMode.CATCH_UP, start = NOW + HOUR, end = NOW + 2 * HOUR, ch = catchUpChannel)
        rig.files.texts["recordings.json"] = stateText(future, dueCatchUp("d"))
        rig.repo.start(NOW)
        rig.alarms.armed.clear() // the OS lost them (boot)
        rig.repo.rearmAll(NOW)
        assertEquals(RecordingSchedulePolicy.fireTimeMs(future), rig.alarms.armed["f"])
        assertEquals("an overdue schedule is armed a few seconds out", NOW + 5_000, rig.alarms.armed["d"])
        // The clock was set forward 3 hours (TIME_SET): nothing is cached, "f" is now overdue as well.
        val later = NOW + 3 * HOUR
        rig.repo.rearmAll(later)
        assertEquals(later + 5_000, rig.alarms.armed["f"])
        assertEquals(later + 5_000, rig.alarms.armed["d"])
    }

    @Test
    fun `rearmAll never starts a save`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        rig.repo.rearmAll(NOW)
        assertTrue(rig.saver.calls.isEmpty())
        assertEquals(NOW + 5_000, rig.alarms.armed["s1"])
    }

    @Test
    fun `sweepOverdue runs overdue schedules once`() = runTest {
        val rig = Rig()
        val future = schedule(id = "f", mode = CaptureMode.CATCH_UP, start = NOW + HOUR, end = NOW + 2 * HOUR, ch = catchUpChannel)
        rig.files.texts["recordings.json"] = stateText(dueCatchUp("d"), future)
        assertEquals(listOf("d"), rig.repo.overdueIds(NOW))
        rig.repo.sweepOverdue(NOW)
        rig.repo.sweepOverdue(NOW)
        assertEquals(1, rig.saver.calls.size)
        assertEquals(listOf("f"), rig.repo.state.value.schedules.map { it.id })
    }

    @Test
    fun `an alarm that comes early is armed again`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(schedule(id = "s1", mode = CaptureMode.CATCH_UP, start = NOW + HOUR, end = NOW + 2 * HOUR, ch = catchUpChannel))
        val res = rig.repo.fire("s1", NOW)
        assertTrue(res is FireResult.Rearmed)
        assertTrue(rig.saver.calls.isEmpty())
        assertEquals((res as FireResult.Rearmed).atMs, rig.alarms.armed["s1"])
        // and a later, on-time fire still works: the early one did not burn the claim
        assertEquals(FireResult.Saved, rig.repo.fire("s1", res.atMs))
    }

    @Test
    fun `a live schedule fails with the pending-capture problem and no retry`() = runTest {
        val rig = Rig()
        val live = schedule(id = "L", mode = CaptureMode.LIVE, start = NOW - 10 * MIN, end = NOW + 50 * MIN)
        rig.files.texts["recordings.json"] = stateText(live)
        assertEquals(FireResult.Failed(RecordingProblem.LIVE_CAPTURE_PENDING), rig.repo.fire("L", NOW))
        assertTrue(rig.saver.calls.isEmpty())
        assertTrue(rig.repo.state.value.schedules.isEmpty())
    }

    @Test
    fun `system teardown during a save rethrows and is retryable not a user cancel`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        val started = CompletableDeferred<Unit>()
        rig.saver.next = { _, _ -> started.complete(Unit); CompletableDeferred<Unit>().await(); SaveOutcome.Done(1) }
        var thrown: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { rig.repo.fire("s1", NOW) } catch (e: CancellationException) { thrown = e; throw e }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue("cancellation must propagate", thrown is CancellationException)
        assertTrue("the user did not cancel", rig.repo.state.value.recordings.none { it.status == RecordingStatus.CANCELLED })
        val s = rig.repo.state.value.schedules.single()
        assertEquals("the interrupted run used an attempt", 1, s.attempts)
        assertEquals("retry is armed", NOW + RecordingSchedulePolicy.RETRY_MS, rig.alarms.armed["s1"])
        val doc = RecordingsCodec.decode(rig.files.texts["recordings.json"])!!
        assertEquals(RecordingProblem.STALLED, doc.ledgerState.entries.single().problem)
        assertEquals("the claim was released", FireState.FAILED, doc.ledgerState.entries.single().state)
    }

    @Test
    fun `a user cancel of the running capture records CANCELLED_BY_USER and the claim is released`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        val started = CompletableDeferred<Unit>()
        rig.saver.next = { _, _ -> started.complete(Unit); CompletableDeferred<Unit>().await(); SaveOutcome.Done(1) }
        val result = async { rig.repo.fire("s1", NOW) }
        started.await()
        rig.repo.cancel("s1")
        assertEquals(FireResult.Cancelled, result.await())
        val rec = rig.repo.state.value.recordings.single()
        assertEquals(RecordingProblem.CANCELLED_BY_USER, rec.problem)
        assertEquals(NOW, rec.endedAtMs)
        val doc = RecordingsCodec.decode(rig.files.texts["recordings.json"])!!
        assertEquals(RecordingProblem.CANCELLED_BY_USER, doc.ledgerState.entries.single().problem)
    }

    @Test
    fun `cancel stops a running capture without cancelling the caller`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        val started = CompletableDeferred<Unit>()
        rig.saver.next = { _, _ -> started.complete(Unit); CompletableDeferred<Unit>().await(); SaveOutcome.Done(1) }
        val result = async { rig.repo.fire("s1", NOW) }
        started.await()
        rig.repo.cancel("s1")
        assertEquals(FireResult.Cancelled, result.await())
        assertEquals(RecordingStatus.CANCELLED, rig.repo.state.value.recordings.single().status)
    }

    @Test
    fun `cancel before it runs removes the schedule and its alarm`() = runTest {
        val rig = Rig()
        rig.repo.scheduleProgramme(catchUpChannel, "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        val id = rig.repo.state.value.schedules.single().id
        rig.repo.cancel(id)
        assertTrue(rig.repo.state.value.schedules.isEmpty())
        assertNull(rig.alarms.armed[id])
    }

    @Test
    fun `a corrupt state file is kept aside and not overwritten`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = "{ this is not json"
        rig.repo.start(NOW)
        assertEquals(listOf("recordings.json" to "{ this is not json"), rig.files.aside)
        assertTrue(rig.repo.state.value.loaded)
        rig.repo.scheduleProgramme(catchUpChannel, "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        assertEquals("{ this is not json", rig.files.aside.single().second)
        assertEquals(1, RecordingsCodec.decode(rig.files.texts["recordings.json"])!!.scheduleValues.size)
    }

    @Test
    fun `unknown JSON fields survive a round trip through the repository`() = runTest {
        val rig = Rig()
        val base = RecordingsCodec.decode(stateText(dueCatchUp()))!!
        val rawRow = (base.schedules.single().raw as JsonObject).let { JsonObject(it + ("futureField" to JsonPrimitive("keep me"))) }
        val doc = base.copy(
            schedules = listOf(StoredRow(base.schedules.single().value, rawRow)),
            extra = JsonObject(mapOf("topLevelFuture" to JsonPrimitive(7))),
            schemaVersion = 3,
        )
        rig.files.texts["recordings.json"] = RecordingsCodec.encode(doc)
        rig.repo.start(NOW)
        rig.repo.scheduleProgramme(catchUpChannel, "Other", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        val out = rig.files.texts["recordings.json"]!!
        assertTrue("row field kept", out.contains("keep me"))
        assertTrue("top-level field kept", out.contains("topLevelFuture"))
        assertTrue("higher schemaVersion kept", out.contains("\"schemaVersion\": 3") || out.contains("\"schemaVersion\":3"))
    }

    @Test
    fun `keep last deletes the extra recordings after a save`() = runTest {
        val rig = Rig()
        val ruleRow = rule(id = "r1", keepLast = 1, ch = catchUpChannel)
        val r1 = recording("r1a", start = NOW - 3 * DAY, seriesRuleId = "r1")
        val r2 = recording("r1b", start = NOW - 2 * DAY, seriesRuleId = "r1")
        val sch = dueCatchUp().copy(seriesRuleId = "r1")
        rig.files.texts["recordings.json"] = RecordingsCodec.encode(
            RecordingsDocument(schedules = listOf(StoredRow.of(sch)), series = listOf(StoredRow.of(ruleRow)), recordings = listOf(r1, r2).map { StoredRow.of(it) }),
        )
        assertEquals(FireResult.Saved, rig.repo.fire("s1", NOW))
        val left = rig.repo.state.value.recordings.map { it.id }
        assertEquals("only the newest is kept: $left", 1, left.size)
        assertTrue(rig.files.deleted.contains("/rec/r1a.ts") && rig.files.deleted.contains("/rec/r1b.ts"))
    }

    @Test
    fun `deleting a recording removes its row and file`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(recs = listOf(recording("x1")))
        rig.repo.deleteRecording("x1")
        assertTrue(rig.repo.state.value.recordings.isEmpty())
        assertEquals(listOf("/rec/x1.ts"), rig.files.deleted)
    }

    @Test
    fun `retry puts a failed recording back as a schedule that runs now`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp().copy(attempts = 2))
        rig.saver.next = { _, _ -> SaveOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, false, 0, false) }
        rig.repo.fire("s1", NOW)
        val failedId = rig.repo.state.value.recordings.single().id
        rig.repo.retry(failedId, NOW + MIN)
        assertTrue(rig.repo.state.value.recordings.isEmpty())
        val s = rig.repo.state.value.schedules.single()
        assertEquals(0, s.attempts)
        assertNotNull(rig.alarms.armed[s.id])
    }

    @Test
    fun `adding a series rule schedules matching airings once`() = runTest {
        val rig = Rig()
        val listings = listOf(
            com.nuvio.tv.core.recordings.api.ListingInfo("Show", null, NOW + 3 * HOUR, NOW + 4 * HOUR, "S01E01"),
            com.nuvio.tv.core.recordings.api.ListingInfo("Show", null, NOW + 1 * DAY, NOW + 1 * DAY + HOUR, "S01E02"),
            com.nuvio.tv.core.recordings.api.ListingInfo("Other", null, NOW + 5 * HOUR, NOW + 6 * HOUR, null),
        )
        val created = rig.repo.addSeriesRule(catchUpChannel, "Show", 0, listings, NOW)
        assertEquals(2, created.size)
        assertEquals(1, rig.repo.state.value.series.size)
        val ruleId = rig.repo.state.value.series.single().id
        assertTrue("re-expansion adds nothing", rig.repo.expandSeries(ruleId, listings, NOW).isEmpty())
        assertEquals(2, rig.repo.state.value.schedules.size)
        assertEquals(2, rig.alarms.armed.size)
    }

    private val showListings = listOf(
        com.nuvio.tv.core.recordings.api.ListingInfo("Show", null, NOW + 3 * HOUR, NOW + 4 * HOUR, "S01E01"),
        com.nuvio.tv.core.recordings.api.ListingInfo("Show", null, NOW + 1 * DAY, NOW + 1 * DAY + HOUR, "S01E02"),
    )

    @Test
    fun `removing a series rule drops the rule and its upcoming schedules but keeps finished recordings`() = runTest {
        val rig = Rig()
        rig.repo.addSeriesRule(catchUpChannel, "Show", 0, showListings, NOW)
        val ruleId = rig.repo.state.value.series.single().id
        rig.repo.scheduleProgramme(catchUpChannel, "One-off", null, null, NOW + 5 * HOUR, NOW + 6 * HOUR, NOW)
        assertEquals(3, rig.repo.state.value.schedules.size)
        rig.repo.removeSeriesRule(ruleId)
        val state = rig.repo.state.value
        assertTrue("the rule is gone", state.series.isEmpty())
        assertEquals("only the one-off is left", listOf("One-off"), state.schedules.map { it.title })
        assertEquals("the one-off keeps its alarm, the series alarms are disarmed", 1, rig.alarms.armed.size)
        val doc = RecordingsCodec.decode(rig.files.texts["recordings.json"])!!
        assertTrue("and it is persisted", doc.seriesValues.isEmpty())
        assertEquals(1, doc.scheduleValues.size)
        assertTrue("a removed rule expands to nothing", rig.repo.expandSeries(ruleId, showListings, NOW).isEmpty())
    }

    @Test
    fun `removing a series rule keeps finished recordings of that rule`() = runTest {
        val files = FakeFiles()
        val rule = rule(id = "r1", ch = catchUpChannel)
        files.texts["recordings.json"] = RecordingsCodec.encode(
            RecordingsDocument(
                series = listOf(StoredRow.of(rule)),
                recordings = listOf(StoredRow.of(recording("rec:old", seriesRuleId = "r1"))),
            ),
        )
        val rig = Rig(files = files)
        rig.repo.removeSeriesRule("r1")
        assertTrue(rig.repo.state.value.series.isEmpty())
        assertEquals("rec:old", rig.repo.state.value.recordings.single().id)
        assertTrue("no file was deleted", files.deleted.isEmpty())
    }

    @Test
    fun `removing a series rule with cancelUpcoming false keeps the schedules`() = runTest {
        val rig = Rig()
        rig.repo.addSeriesRule(catchUpChannel, "Show", 0, showListings, NOW)
        val ruleId = rig.repo.state.value.series.single().id
        rig.repo.removeSeriesRule(ruleId, cancelUpcoming = false)
        assertTrue(rig.repo.state.value.series.isEmpty())
        assertEquals(2, rig.repo.state.value.schedules.size)
        assertEquals(2, rig.alarms.armed.size)
    }

    @Test
    fun `removing a rule that does not exist changes nothing`() = runTest {
        val rig = Rig()
        rig.repo.addSeriesRule(catchUpChannel, "Show", 0, showListings, NOW)
        rig.repo.removeSeriesRule("series:nope")
        assertEquals(1, rig.repo.state.value.series.size)
        assertEquals(2, rig.repo.state.value.schedules.size)
    }

    @Test
    fun `the space check during a save asks the policy and can stop the capture`() = runTest {
        val rig = Rig()
        rig.files.texts["recordings.json"] = stateText(dueCatchUp())
        var verdict: RecordingProblem? = null
        rig.saver.next = { req, _ ->
            rig.files.free = 100L * 1024 * 1024 // below the floor
            verdict = req.spaceCheck!!.invoke(16L * 1024 * 1024)
            SaveOutcome.Failed(verdict ?: RecordingProblem.UNKNOWN, false, 1, true)
        }
        assertEquals(FireResult.Failed(RecordingProblem.NO_SPACE), rig.repo.fire("s1", NOW))
        assertEquals(RecordingProblem.NO_SPACE, verdict)
    }

    @Test
    fun `fire after rejecting a duplicate arm keeps one alarm per schedule`() = runTest {
        val rig = Rig()
        rig.repo.scheduleProgramme(catchUpChannel, "Show", null, null, NOW + HOUR, NOW + 2 * HOUR, NOW)
        rig.repo.rearmAll(NOW)
        rig.repo.rearmAll(NOW)
        assertEquals(1, rig.alarms.armed.size)
    }

    // ---- wave 3: what the screens ask the port ----------------------------------------------

    @Test
    fun `the port reports the capabilities it was built with`() {
        val caps = RecordingCapabilities(canRecordLive = false, canSaveCatchUp = true)
        assertEquals(caps, Rig(capabilities = caps).repo.capabilities)
    }

    @Test
    fun `a done recording with its file on disk has a playable file uri`() = runTest {
        val rig = Rig()
        rig.files.sizes["/rec/news-1.ts"] = 5_000L
        val row = recording("rec:a", status = RecordingStatus.DONE).copy(fileName = "news-1.ts")
        assertEquals("file:///rec/news-1.ts", rig.repo.playableUri(row))
    }

    @Test
    fun `a missing or empty file is not playable`() = runTest {
        val rig = Rig()
        val row = recording("rec:a", status = RecordingStatus.DONE).copy(fileName = "gone.ts")
        assertNull(rig.repo.playableUri(row))
        rig.files.sizes["/rec/gone.ts"] = 0L
        assertNull(rig.repo.playableUri(row))
    }

    @Test
    fun `a recording that is not done is never playable even with a part file`() = runTest {
        val rig = Rig()
        rig.files.sizes["/rec/x.ts"] = 9_000L
        for (status in listOf(RecordingStatus.RECORDING, RecordingStatus.FAILED, RecordingStatus.CANCELLED)) {
            assertNull("$status", rig.repo.playableUri(recording("rec:x", status = status).copy(fileName = "x.ts")))
        }
    }
}

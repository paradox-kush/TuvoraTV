package com.nuvio.tv.core.recordings.internal

import android.util.Log
import com.nuvio.tv.core.recordings.api.ArmKind
import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.FireResult
import com.nuvio.tv.core.recordings.api.ListingInfo
import com.nuvio.tv.core.recordings.api.LowSpacePolicy
import com.nuvio.tv.core.recordings.api.Recording
import com.nuvio.tv.core.recordings.api.RecordingAlarms
import com.nuvio.tv.core.recordings.api.RecordingCapabilities
import com.nuvio.tv.core.recordings.api.RecordingChannel
import com.nuvio.tv.core.recordings.api.RecordingFiles
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingSaver
import com.nuvio.tv.core.recordings.api.RecordingSchedule
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.RecordingFileUri
import com.nuvio.tv.core.recordings.api.RecordingsPort
import com.nuvio.tv.core.recordings.api.RecordingsState
import com.nuvio.tv.core.recordings.api.SaveOutcome
import com.nuvio.tv.core.recordings.api.SaveRequest
import com.nuvio.tv.core.recordings.api.ScheduleResult
import com.nuvio.tv.core.recordings.api.SeriesRule
import io.ktor.util.date.GMTDate
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Orchestrates recordings: what is scheduled, what is running, what is saved. One instance per
 * process, created by the platform composition root. All state (the document, the fire ledger, the
 * running jobs) is owned by [mutex]; nothing awaits a save while holding it.
 *
 * Durations use the monotonic [timeSource], never the wall clock (a timezone or NTP jump must not
 * change how long a save "took"). Cancellation is never swallowed: a cancelled caller sees it
 * rethrown after the claim is released as FAILED(STALLED) (system teardown, retryable); only the
 * explicit [cancel] records CANCELLED_BY_USER.
 *
 * LIVE capture is wave 4. TODO(live capture pending live-pause branch): a due LIVE schedule becomes a
 * FAILED row with [RecordingProblem.LIVE_CAPTURE_PENDING] until that lands.
 */
class RecordingsRepository(
    private val files: RecordingFiles,
    private val alarms: RecordingAlarms,
    private val saver: RecordingSaver,
    override val capabilities: RecordingCapabilities,
    private val lowSpacePolicy: () -> LowSpacePolicy = { LowSpacePolicy.STOP },
    private val preMs: Long = RecordingSchedulePolicy.DEFAULT_PRE_MS,
    private val postMs: Long = RecordingSchedulePolicy.DEFAULT_POST_MS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Wall clock for paths that have no caller-supplied time (cancel, delete after a cold start). */
    private val clock: () -> Long = { GMTDate().timestamp },
) : RecordingsPort {

    private val mutex = Mutex()
    private val origin = timeSource.markNow()

    // Everything below is guarded by [mutex].
    private var loaded = false
    private var doc = RecordingsDocument()
    private var ledger = RecordingFireLedger()
    private val running = HashMap<String, Job>()

    private val _state = MutableStateFlow(RecordingsState())
    override val state: StateFlow<RecordingsState> = _state.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val progress: StateFlow<Map<String, Long>> = _progress.asStateFlow()

    private val ledgerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun playableUri(recording: Recording): String? {
        if (recording.status != RecordingStatus.DONE) return null
        val path = files.pathFor(recording.fileName)
        return if (files.sizeOf(path) > 0L) RecordingFileUri.fromPath(path) else null
    }

    private fun elapsedMs(): Long = origin.elapsedNow().inWholeMilliseconds

    // ---- loading, persistence -------------------------------------------------------------

    private suspend fun <T> locked(nowMs: Long, block: suspend () -> T): T = mutex.withLock {
        if (!loaded) loadLocked(nowMs)
        block()
    }

    override suspend fun start(nowMs: Long) {
        locked(nowMs) { rearmAllLocked(nowMs); publish() }
    }

    private suspend fun loadLocked(nowMs: Long) {
        val text = files.read(STATE_FILE)
        doc = RecordingsCodec.decode(text) ?: run {
            // Not a JSON object: never overwrite it, move it out of the way and start clean.
            Log.w(TAG, "recordings state unreadable, kept aside")
            files.keepAside(STATE_FILE)
            RecordingsDocument()
        }
        // v1 kept the ledger in a second file: fold it in once, then retire the file (below, after the
        // v2 document is on disk, so a crash in between leaves the old file to be read again).
        val migrating = doc.loadedVersion < 2
        ledger = RecordingFireLedger(if (migrating) readLegacyLedger() else doc.ledgerState)
        loaded = true
        // A RUNNING claim now belongs to a process that is gone.
        for (id in ledger.recoverAfterRestart()) {
            val schedule = doc.scheduleValues.firstOrNull { it.id == id }
            val row = doc.recordingValues.firstOrNull { it.id == recordingIdFor(id) }
            if (schedule != null) {
                // Whatever the dead process wrote to the .part file is kept: the saver resumes it if the server allows.
                val partial = row?.let { files.sizeOf(partPath(files.pathFor(it.fileName))) } ?: 0L
                afterFailureLocked(schedule, RecordingProblem.STALLED, offline = false, nowMs = nowMs, written = partial, rangeSupported = true)
            } else if (row != null && row.status == RecordingStatus.RECORDING) {
                edit(recordings = { list -> list.map { if (it.id == row.id) it.copy(status = RecordingStatus.FAILED, problem = RecordingProblem.STALLED) else it } })
            }
        }
        reconcileRecordingRowsLocked(nowMs)
        if (persistState() && migrating) files.keepAside(LEDGER_FILE)
    }

    /**
     * A row still RECORDING after the RUNNING claims were recovered has a ledger entry that is not
     * RUNNING, so nothing is writing to it. DONE in the ledger means the save finished: finish the row
     * from the file. Anything else is a lost run: the retry policy decides, or the row is failed.
     */
    private suspend fun reconcileRecordingRowsLocked(nowMs: Long) {
        for (row in doc.recordingValues.filter { it.status == RecordingStatus.RECORDING }) {
            val sid = row.scheduleId
            val schedule = sid?.let { id -> doc.scheduleValues.firstOrNull { it.id == id } }
            val target = files.pathFor(row.fileName)
            if (sid != null && ledger.stateOf(sid) == FireState.DONE && files.sizeOf(target) > 0L) {
                edit(
                    schedules = { list -> list.filterNot { it.id == sid } },
                    recordings = { list ->
                        list.map { if (it.id == row.id) it.copy(status = RecordingStatus.DONE, endedAtMs = row.endedAtMs ?: nowMs, sizeBytes = files.sizeOf(target), problem = null, retrySource = null) else it }
                    },
                )
                alarms.disarm(sid)
            } else if (schedule != null) {
                val partial = files.sizeOf(partPath(target))
                afterFailureLocked(schedule, RecordingProblem.STALLED, offline = false, nowMs = nowMs, written = partial, rangeSupported = true)
            } else {
                edit(recordings = { list -> list.map { if (it.id == row.id) it.copy(status = RecordingStatus.FAILED, problem = RecordingProblem.STALLED, endedAtMs = row.endedAtMs ?: nowMs) else it } })
            }
        }
    }

    private suspend fun readLegacyLedger(): FireLedgerState {
        val text = files.read(LEDGER_FILE) ?: return FireLedgerState()
        return try {
            ledgerJson.decodeFromString(FireLedgerState.serializer(), text)
        } catch (e: IllegalArgumentException) {
            // Losing the ledger can only repeat a save, never lose one: keep it aside and carry on.
            Log.w(TAG, "fire ledger unreadable, kept aside")
            files.keepAside(LEDGER_FILE)
            FireLedgerState()
        }
    }

    /** One atomic write: the ledger rides in the same document, so the two can never disagree on disk. */
    private suspend fun persistState(): Boolean {
        doc = doc.withLedger(ledger.snapshot())
        return try {
            files.write(STATE_FILE, RecordingsCodec.encode(doc))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "could not write recordings state", e)
            false
        }
    }


    private fun publish() {
        _state.value = RecordingsState(
            schedules = doc.scheduleValues,
            series = doc.seriesValues,
            recordings = doc.recordingValues,
            exactAllowed = alarms.exactAllowed(),
            loaded = loaded,
        )
    }

    private fun edit(
        schedules: (List<RecordingSchedule>) -> List<RecordingSchedule> = { it },
        series: (List<SeriesRule>) -> List<SeriesRule> = { it },
        recordings: (List<Recording>) -> List<Recording> = { it },
    ) {
        doc = doc.copy(
            schedules = doc.schedules.reconcile(schedules(doc.scheduleValues)) { it.id },
            series = doc.series.reconcile(series(doc.seriesValues)) { it.id },
            recordings = doc.recordings.reconcile(recordings(doc.recordingValues)) { it.id },
        )
    }

    private suspend fun commit() {
        persistState()
        publish()
    }

    private fun recordingIdFor(scheduleId: String) = "rec:$scheduleId"

    private fun removeRecordingRow(id: String) = edit(recordings = { list -> list.filterNot { it.id == id } })

    private fun partPath(path: String) = "$path.part"

    // ---- alarms ---------------------------------------------------------------------------

    private fun armAt(s: RecordingSchedule, nowMs: Long): ArmKind {
        val at = RecordingSchedulePolicy.fireTimeMs(s)
        // A time that has passed gets an alarm a few seconds out, never "in the past".
        return alarms.arm(s.id, if (at <= nowMs) nowMs + OVERDUE_REARM_DELAY_MS else at)
    }

    private fun rearmAllLocked(nowMs: Long) {
        for (s in doc.scheduleValues) {
            if (ledger.stateOf(s.id) == FireState.RUNNING) continue
            armAt(s, nowMs)
        }
    }

    override suspend fun rearmAll(nowMs: Long) {
        locked(nowMs) { rearmAllLocked(nowMs); publish() }
    }

    // ---- scheduling -----------------------------------------------------------------------

    override suspend fun scheduleProgramme(
        channel: RecordingChannel,
        title: String,
        episode: String?,
        description: String?,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
    ): ScheduleResult = locked(nowMs) {
        val mode = RecordingSchedulePolicy.modeFor(channel, startMs, endMs, nowMs, capabilities)
            ?: return@locked ScheduleResult.CannotRecord
        val candidate = RecordingSchedule(
            id = "${channel.key}:$startMs", channel = channel, title = title, episode = episode, description = description,
            programmeStartMs = startMs, programmeEndMs = endMs, mode = mode, preMs = preMs, postMs = postMs,
        )
        val existing = doc.scheduleValues
        existing.firstOrNull { it.channel.key == channel.key && it.programmeStartMs == startMs }
            ?.let { return@locked ScheduleResult.Duplicate(it) }
        val clashes = RecordingSchedulePolicy.clashes(candidate, existing)
        edit(schedules = { it + candidate })
        val arm = armAt(candidate, nowMs)
        commit()
        ScheduleResult.Scheduled(candidate, arm, clashes)
    }

    override suspend fun addSeriesRule(
        channel: RecordingChannel,
        title: String,
        keepLast: Int,
        listings: List<ListingInfo>,
        nowMs: Long,
    ): List<RecordingSchedule> = locked(nowMs) {
        val id = SeriesRuleIds.of(channel.key, title)
        val rule = SeriesRule(id = id, channel = channel, title = title, keepLast = keepLast)
        edit(series = { list -> list.filterNot { it.id == id } + rule })
        val created = expandLocked(rule, listings, nowMs)
        commit()
        created
    }

    override suspend fun expandSeries(ruleId: String, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule> =
        locked(nowMs) {
            val rule = doc.seriesValues.firstOrNull { it.id == ruleId } ?: return@locked emptyList()
            val created = expandLocked(rule, listings, nowMs)
            if (created.isNotEmpty()) commit()
            created
        }

    private fun expandLocked(rule: SeriesRule, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule> {
        val created = SeriesExpansion.expand(
            rule = rule, listings = listings, existingSchedules = doc.scheduleValues, existingRecordings = doc.recordingValues,
            nowMs = nowMs, preMs = preMs, postMs = postMs,
            modeFor = { s, e -> RecordingSchedulePolicy.modeFor(rule.channel, s, e, nowMs, capabilities) },
        )
        if (created.isEmpty()) return created
        edit(schedules = { it + created })
        created.forEach { armAt(it, nowMs) }
        return created
    }

    // ---- cancel, delete, retry ------------------------------------------------------------

    override suspend fun cancel(scheduleId: String) {
        val job = mutex.withLock {
            running[scheduleId]?.let { return@withLock it }
            if (!loaded) loadLocked(clock())
            alarms.disarm(scheduleId)
            if (doc.scheduleValues.any { it.id == scheduleId }) {
                edit(schedules = { list -> list.filterNot { it.id == scheduleId } })
                ledger.forget(scheduleId)
                commit()
            }
            null
        }
        // A running capture finishes itself: the fire coroutine records CANCELLED_BY_USER.
        job?.cancel(CancellationException("cancelled by user"))
    }

    override suspend fun deleteRecording(id: String) {
        val job = mutex.withLock { running[id.removePrefix("rec:")] }
        job?.cancel(CancellationException("cancelled by user"))
        locked(clock()) {
            val row = doc.recordingValues.firstOrNull { it.id == id } ?: return@locked
            deleteFilesOf(row)
            removeRecordingRow(id)
            row.scheduleId?.let { ledger.forget(it) }
            commit()
        }
    }

    private suspend fun deleteFilesOf(row: Recording) {
        val path = files.pathFor(row.fileName)
        files.delete(path)
        if (row.status != RecordingStatus.DONE) files.delete(partPath(path))
    }

    override suspend fun retry(recordingId: String, nowMs: Long) {
        locked(nowMs) {
            val row = doc.recordingValues.firstOrNull { it.id == recordingId } ?: return@locked
            val source = row.retrySource ?: return@locked
            if (row.status != RecordingStatus.FAILED && row.status != RecordingStatus.CANCELLED) return@locked
            if (doc.scheduleValues.any { it.id == source.id }) return@locked
            files.delete(partPath(files.pathFor(row.fileName)))
            removeRecordingRow(recordingId)
            ledger.forget(source.id)
            val again = source.copy(attempts = 0, nextTryMs = nowMs)
            edit(schedules = { it + again })
            armAt(again, nowMs)
            commit()
        }
    }

    // ---- overdue sweep --------------------------------------------------------------------

    override suspend fun overdueIds(nowMs: Long): List<String> = locked(nowMs) {
        doc.scheduleValues.filter {
            RecordingSchedulePolicy.fireTimeMs(it) <= nowMs &&
                ledger.stateOf(it.id).let { s -> s != FireState.RUNNING && s != FireState.DONE }
        }.map { it.id }
    }

    override suspend fun sweepOverdue(nowMs: Long) {
        val ids = overdueIds(nowMs)
        coroutineScope { ids.map { id -> async { fire(id, nowMs) } }.awaitAll() }
    }

    // ---- fire -----------------------------------------------------------------------------

    private class Run(
        val schedule: RecordingSchedule,
        val token: String,
        val request: SaveRequest,
        val targetPath: String,
        val fromMs: Long,
        val toMs: Long,
        val cancelJob: CompletableJob,
        val recordingId: String,
    )

    private sealed interface Begin {
        data class Finished(val result: FireResult) : Begin
        data class Go(val run: Run) : Begin
    }

    override suspend fun fire(scheduleId: String, nowMs: Long): FireResult {
        val begin = locked(nowMs) { beginLocked(scheduleId, nowMs) }
        val run = when (begin) {
            is Begin.Finished -> return begin.result
            is Begin.Go -> begin.run
        }
        return runSave(run, nowMs)
    }

    private suspend fun beginLocked(scheduleId: String, nowMs: Long): Begin {
        val schedule = doc.scheduleValues.firstOrNull { it.id == scheduleId } ?: return Begin.Finished(FireResult.NotFound)
        val token = when (val claim = ledger.claim(scheduleId, elapsedMs())) {
            is RecordingFireLedger.Claim.Claimed -> claim.token
            RecordingFireLedger.Claim.AlreadyRunning, RecordingFireLedger.Claim.AlreadyDone ->
                return Begin.Finished(FireResult.AlreadyHandled)
        }
        when (val decision = RecordingSchedulePolicy.onFire(schedule, nowMs)) {
            is FireDecision.Rearm -> {
                ledger.release(token, FireOutcome.FAILED, RecordingProblem.UNKNOWN)
                alarms.arm(schedule.id, decision.atMs)
                commit()
                return Begin.Finished(FireResult.Rearmed(decision.atMs))
            }
            FireDecision.Missed, FireDecision.Expired -> return Begin.Finished(failTerminal(schedule, token, RecordingProblem.MISSED, nowMs))
            is FireDecision.StartLive ->
                // TODO(live capture pending live-pause branch): wave 4 replaces this with a real live capture.
                return Begin.Finished(failTerminal(schedule, token, RecordingProblem.LIVE_CAPTURE_PENDING, nowMs))
            is FireDecision.StartCatchUpSave -> return beginCatchUp(schedule, token, decision, nowMs)
        }
    }

    private suspend fun beginCatchUp(schedule: RecordingSchedule, token: String, d: FireDecision.StartCatchUpSave, nowMs: Long): Begin {
        val candidates = CatchUpSaveLinks.candidates(schedule.channel, d.fromMs, d.toMs, nowMs)
        CatchUpSaveLinks.problemFor(candidates)?.let { return Begin.Finished(failTerminal(schedule, token, it, nowMs)) }

        val fileName = fileNameFor(schedule, candidates.first())
        val target = files.pathFor(fileName)
        val resumeFrom = files.sizeOf(partPath(target))
        val estimate = RecordingSpacePolicy.estimateBytes(d.toMs - d.fromMs)

        when (val space = RecordingSpacePolicy.check(files.freeBytes(), (estimate - resumeFrom).coerceAtLeast(0), lowSpacePolicy(), doc.recordingValues)) {
            SpaceDecision.Proceed -> Unit
            is SpaceDecision.DeleteThenProceed -> deleteRecordings(space.ids)
            is SpaceDecision.Refuse -> return Begin.Finished(failTerminal(schedule, token, space.problem, nowMs))
        }

        val recordingId = recordingIdFor(schedule.id)
        val row = Recording(
            id = recordingId, scheduleId = schedule.id, channelKey = schedule.channel.key, channelName = schedule.channel.name,
            title = schedule.title, episode = schedule.episode, description = schedule.description,
            programmeStartMs = schedule.programmeStartMs, programmeEndMs = schedule.programmeEndMs,
            startedAtMs = nowMs, endedAtMs = null, status = RecordingStatus.RECORDING, mode = CaptureMode.CATCH_UP,
            sizeBytes = resumeFrom, fileName = fileName, seriesRuleId = schedule.seriesRuleId,
        )
        edit(recordings = { list -> list.filterNot { it.id == recordingId } + row })
        alarms.disarm(schedule.id)

        val parent = currentCoroutineContext()[Job]
        val cancelJob = Job(parent)
        running[schedule.id] = cancelJob
        commit() // the RUNNING claim reaches disk before the first byte
        val request = SaveRequest(
            candidates = candidates, headers = schedule.channel.headers, targetPath = target,
            resumeFromBytes = resumeFrom, estimatedBytes = estimate,
            spaceCheck = { expected -> checkSpaceDuringCapture(expected) },
        )
        return Begin.Go(Run(schedule, token, request, target, d.fromMs, d.toMs, cancelJob, recordingId))
    }

    /** Asked by the saver every ~16 MB. Deletes old recordings if the user chose DELETE_OLDEST. */
    private suspend fun checkSpaceDuringCapture(expectedBytes: Long): RecordingProblem? = mutex.withLock {
        when (val d = RecordingSpacePolicy.duringCapture(files.freeBytes(), expectedBytes, lowSpacePolicy(), doc.recordingValues)) {
            CaptureSpaceDecision.Continue -> null
            is CaptureSpaceDecision.Stop -> d.problem
            is CaptureSpaceDecision.DeleteThenContinue -> {
                deleteRecordings(d.ids)
                commit()
                null
            }
        }
    }

    private suspend fun deleteRecordings(ids: List<String>) {
        for (id in ids) {
            val row = doc.recordingValues.firstOrNull { it.id == id } ?: continue
            deleteFilesOf(row)
            removeRecordingRow(id)
            row.scheduleId?.let { ledger.forget(it) }
        }
    }

    private suspend fun runSave(run: Run, nowMs: Long): FireResult {
        val mark = timeSource.markNow()
        var lastReported = -1L
        val progress: (Long) -> Unit = { bytes ->
            if (lastReported < 0 || bytes - lastReported >= PROGRESS_STEP_BYTES || bytes < lastReported) {
                lastReported = bytes
                _progress.update { it + (run.recordingId to bytes) }
            }
        }
        val outcome: SaveOutcome = try {
            withContext(run.cancelJob) { saver.save(run.request, progress) }
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // Only our own job was cancelled: that is the user's cancel().
                withContext(NonCancellable) { mutex.withLock { finishCancelled(run) } }
                return FireResult.Cancelled
            }
            // The caller was cancelled by the system (service destroyed, process scope torn down), not by
            // the user: release the claim as STALLED so the retry policy re-arms it. Then rethrow.
            withContext(NonCancellable) {
                mutex.withLock {
                    running.remove(run.schedule.id)
                    val partial = files.sizeOf(partPath(run.targetPath))
                    afterFailureLocked(
                        run.schedule, RecordingProblem.STALLED, offline = false,
                        nowMs = nowMs + mark.elapsedNow().inWholeMilliseconds, written = partial, rangeSupported = true, token = run.token,
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "save threw", e)
            SaveOutcome.Failed(RecordingProblem.UNKNOWN, offline = false, writtenBytes = 0, rangeSupported = false)
        } finally {
            withContext(NonCancellable) {
                run.cancelJob.complete()
                _progress.update { it - run.recordingId }
            }
        }
        val now = nowMs + mark.elapsedNow().inWholeMilliseconds
        val durationMs = mark.elapsedNow().inWholeMilliseconds
        return withContext(NonCancellable) {
            mutex.withLock {
                running.remove(run.schedule.id)
                when (outcome) {
                    is SaveOutcome.Done -> finishDone(run, outcome, now, durationMs)
                    is SaveOutcome.Failed -> afterFailureLocked(
                        run.schedule, outcome.problem, outcome.offline, now, outcome.writtenBytes, outcome.rangeSupported, run.token,
                    )
                }
            }
        }
    }

    private suspend fun finishDone(run: Run, done: SaveOutcome.Done, nowMs: Long, @Suppress("UNUSED_PARAMETER") elapsedMs: Long): FireResult {
        val s = run.schedule
        ledger.release(run.token, FireOutcome.DONE)
        edit(
            schedules = { list -> list.filterNot { it.id == s.id } },
            recordings = { list ->
                list.map {
                    if (it.id == run.recordingId) it.copy(
                        status = RecordingStatus.DONE, endedAtMs = nowMs, sizeBytes = done.bytes,
                        durationMs = run.toMs - run.fromMs, problem = null, retrySource = null,
                    ) else it
                }
            },
        )
        // "Keep last N": the extras go after the new one is safely on disk.
        doc.seriesValues.firstOrNull { it.id == s.seriesRuleId }?.let { rule ->
            deleteRecordings(SeriesExpansion.keepLastToDelete(rule, doc.recordingValues))
        }
        commit()
        return FireResult.Saved
    }

    private suspend fun finishCancelled(run: Run) {
        val s = run.schedule
        running.remove(s.id)
        ledger.release(run.token, FireOutcome.FAILED, RecordingProblem.CANCELLED_BY_USER)
        val endedAt = clock()
        files.delete(partPath(run.targetPath))
        alarms.disarm(s.id)
        edit(
            schedules = { list -> list.filterNot { it.id == s.id } },
            recordings = { list ->
                list.map {
                    if (it.id == run.recordingId) it.copy(
                        status = RecordingStatus.CANCELLED, problem = RecordingProblem.CANCELLED_BY_USER, retrySource = s, endedAtMs = endedAt,
                    ) else it
                }
            },
        )
        commit()
    }

    /** A failure the retry policy should judge: try again later, or give up. */
    private suspend fun afterFailureLocked(
        schedule: RecordingSchedule,
        problem: RecordingProblem,
        offline: Boolean,
        nowMs: Long,
        written: Long,
        rangeSupported: Boolean,
        token: String? = null,
    ): FireResult {
        token?.let { ledger.release(it, FireOutcome.FAILED, problem) }
        val recordingId = recordingIdFor(schedule.id)
        val target = rowTarget(recordingId)
        return when (val d = RecordingSchedulePolicy.afterFailure(schedule, problem, nowMs, offline)) {
            is RetryDecision.RetryAt -> {
                removeRecordingRow(recordingId)
                edit(schedules = { list -> list.map { if (it.id == schedule.id) d.schedule else it } })
                // Resume only if the server will honour it; otherwise a stale partial would just be rewritten.
                if (target != null && !(written > 0 && rangeSupported)) files.delete(partPath(target))
                armAt(d.schedule, nowMs)
                commit()
                FireResult.Retrying(d.schedule.nextTryMs)
            }
            is RetryDecision.GiveUp -> {
                terminalRow(schedule, d.problem, nowMs, target)
                commit()
                FireResult.Failed(d.problem)
            }
        }
    }

    private fun rowTarget(recordingId: String): String? =
        doc.recordingValues.firstOrNull { it.id == recordingId }?.let { files.pathFor(it.fileName) }

    private suspend fun failTerminal(schedule: RecordingSchedule, token: String, problem: RecordingProblem, nowMs: Long): FireResult {
        ledger.release(token, FireOutcome.FAILED, problem)
        terminalRow(schedule, problem, nowMs, rowTarget(recordingIdFor(schedule.id)))
        commit()
        return FireResult.Failed(problem)
    }

    /** Removes the schedule and leaves a FAILED row the UI can show and Retry can restart. */
    private suspend fun terminalRow(schedule: RecordingSchedule, problem: RecordingProblem, nowMs: Long, target: String?) {
        val id = recordingIdFor(schedule.id)
        alarms.disarm(schedule.id)
        if (target != null) files.delete(partPath(target))
        val previous = doc.recordingValues.firstOrNull { it.id == id }
        val row = (previous ?: Recording(
            id = id, scheduleId = schedule.id, channelKey = schedule.channel.key, channelName = schedule.channel.name,
            title = schedule.title, episode = schedule.episode, description = schedule.description,
            programmeStartMs = schedule.programmeStartMs, programmeEndMs = schedule.programmeEndMs,
            startedAtMs = nowMs, endedAtMs = nowMs, status = RecordingStatus.FAILED, mode = schedule.mode,
            fileName = fileNameFor(schedule, null), seriesRuleId = schedule.seriesRuleId,
        )).copy(status = RecordingStatus.FAILED, problem = problem, endedAtMs = nowMs, retrySource = schedule)
        edit(
            schedules = { list -> list.filterNot { it.id == schedule.id } },
            recordings = { list -> list.filterNot { it.id == id } + row },
        )
    }

    private fun fileNameFor(schedule: RecordingSchedule, firstCandidate: String?): String {
        val ext = if (firstCandidate?.substringBefore('?')?.lowercase()?.endsWith(".mp4") == true) "mp4" else "ts"
        val base = buildString {
            for (c in schedule.title) append(if (c.isLetterOrDigit() || c == ' ' || c == '-') c else '_')
        }.trim().take(60).ifBlank { "recording" }
        return "$base-${schedule.programmeStartMs}.$ext"
    }

    private companion object {
        const val TAG = "Recordings"
        const val STATE_FILE = "recordings.json"
        /** v1 only: read once to migrate into the document, then retired. */
        const val LEDGER_FILE = "recordings-ledger.json"
        const val OVERDUE_REARM_DELAY_MS = 5_000L
        const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}

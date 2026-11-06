package com.nuvio.tv.core.recordings.api

/** How firmly a platform could promise the wake-up: EXACT (on time), INEXACT (may start late), NONE (cannot wake the app at all). */
enum class ArmKind { EXACT, INEXACT, NONE }

/**
 * Wakes the app at a wall-clock time. Android: AlarmManager. iOS: cannot, so [arm] returns NONE and
 * the schedule waits for the next foreground sweep. Every [arm] replaces an earlier one for the same id.
 */
interface RecordingAlarms {
    fun arm(scheduleId: String, atMs: Long): ArmKind
    fun disarm(scheduleId: String)

    /** Whether the platform will honour exact times right now (Android 12+ makes this a user permission). */
    fun exactAllowed(): Boolean
}

/**
 * One save of a programme to disk. [candidates] are tried in order; the file is written to
 * `targetPath + ".part"` and renamed to [targetPath] when complete.
 */
data class SaveRequest(
    val candidates: List<String>,
    val headers: Map<String, String>,
    val targetPath: String,
    val resumeFromBytes: Long,
    val estimatedBytes: Long,
    /**
     * Called by the saver about every 16 MB with the bytes it expects to write before the next call.
     * Returns a problem (NO_SPACE) when the save must stop, null to continue. The repository owns the
     * policy (and any "delete oldest" it implies); the saver only asks.
     */
    val spaceCheck: (suspend (expectedBytes: Long) -> RecordingProblem?)? = null,
    /**
     * Called ONCE, as soon as the saver KNOWS the size (a declared Content-Length, after the response
     * headers and before any body byte is written). [totalBytes] is the whole file; [onDiskBytes] is what
     * a resumed save already holds, so the repository compares only the remainder with the free space.
     * On [KnownSizeDecision.Refuse] the saver must abort at once and report
     * `Failed(NO_SPACE, needBytes, freeBytes)`. Never called when the size is not declared: an estimate
     * is never a reason to refuse.
     */
    val onKnownTotal: (suspend (totalBytes: Long, onDiskBytes: Long) -> KnownSizeDecision)? = null,
)

/** The repository's answer to [SaveRequest.onKnownTotal]. */
sealed interface KnownSizeDecision {
    data object Continue : KnownSizeDecision

    /** The declared size will not fit: [needBytes] still to write, [freeBytes] free right now. */
    data class Refuse(val needBytes: Long, val freeBytes: Long) : KnownSizeDecision
}

sealed interface SaveOutcome {
    /**
     * [stoppedFor] non-null means the file is a playable TRUNCATED save the saver kept (renamed to the
     * target) because it had to stop part-way, today only [RecordingProblem.NO_SPACE]; the row is DONE
     * with that note and is never retried.
     */
    data class Done(val bytes: Long, val stoppedFor: RecordingProblem? = null) : SaveOutcome

    /**
     * [offline]: the device had no network (does not use up an attempt). [writtenBytes]: what is in the
     * `.part` file. [rangeSupported]: the server honoured Range, so the next try can resume from there.
     * [needBytes]/[freeBytes]: set only on a NO_SPACE refusal of a KNOWN size (see [SaveRequest.onKnownTotal]).
     */
    data class Failed(
        val problem: RecordingProblem,
        val offline: Boolean,
        val writtenBytes: Long,
        val rangeSupported: Boolean,
        val needBytes: Long? = null,
        val freeBytes: Long? = null,
    ) : SaveOutcome
}

/** Downloads a catch-up programme. Must rethrow cancellation, and report progress as total bytes in the file. */
interface RecordingSaver {
    suspend fun save(req: SaveRequest, progress: (writtenBytes: Long) -> Unit): SaveOutcome
}

/** Storage of the recordings state and files. Names are plain file names inside the app's own directory. */
interface RecordingFiles {
    /** Text of [name], or null when it does not exist. */
    suspend fun read(name: String): String?

    /** Atomic: written to a temporary file and renamed over [name]. */
    suspend fun write(name: String, text: String)

    /** Moves an unreadable [name] out of the way (never deletes it) so a fresh one can be written. */
    suspend fun keepAside(name: String)

    /** Absolute path of a recording file called [fileName]. */
    fun pathFor(fileName: String): String

    fun freeBytes(): Long

    /** Size of the file at [path], 0 when it does not exist. */
    fun sizeOf(path: String): Long

    /** Deletes the file at [path] (no error when it is gone). */
    suspend fun delete(path: String)
}

data class RecordingsState(
    val schedules: List<RecordingSchedule> = emptyList(),
    val series: List<SeriesRule> = emptyList(),
    val recordings: List<Recording> = emptyList(),
    val exactAllowed: Boolean = true,
    /** False until the persisted state has been read: a screen shows nothing rather than "no recordings". */
    val loaded: Boolean = false,
)

sealed interface ScheduleResult {
    /** [arm] tells the UI whether to say "may start late" (INEXACT) or "will not start by itself" (NONE). [clashes] are overlapping live captures on the same source: warn, never block. */
    data class Scheduled(
        val schedule: RecordingSchedule,
        val arm: ArmKind,
        val clashes: List<RecordingSchedule>,
        /** The estimated size plus the free-space floor exceeds the free space now: say "may stop early", never block. */
        val spaceTight: Boolean = false,
    ) : ScheduleResult
    data class Duplicate(val existing: RecordingSchedule) : ScheduleResult

    /** Not catch-up, and this platform cannot record live (or the programme is over). */
    data object CannotRecord : ScheduleResult
}

sealed interface FireResult {
    data object Saved : FireResult
    data class Retrying(val atMs: Long) : FireResult
    data class Failed(val problem: RecordingProblem) : FireResult

    /** The alarm came early: armed again for [atMs]. */
    data class Rearmed(val atMs: Long) : FireResult
    data object Cancelled : FireResult

    /** Another fire for this schedule is running or has finished. */
    data object AlreadyHandled : FireResult
    data object NotFound : FireResult
}

/**
 * What a screen, an alarm receiver and the foreground service call. Implemented by the repository,
 * created once by the platform composition root and published through [RecordingsAccess].
 */
interface RecordingsPort {
    val state: kotlinx.coroutines.flow.StateFlow<RecordingsState>

    /** What this platform can capture and whether it can wake the app: the UI hides what cannot happen here. */
    val capabilities: RecordingCapabilities

    /** `file://` URI of a finished recording's file, or null when it is not DONE or the file is gone. Never a loopback server. */
    fun playableUri(recording: Recording): String?

    /** Bytes written so far by each running capture, keyed by recording id. */
    val progress: kotlinx.coroutines.flow.StateFlow<Map<String, Long>>

    /** Reads the persisted state, recovers captures a dead process left running, re-arms alarms. Idempotent. */
    suspend fun start(nowMs: Long)

    suspend fun scheduleProgramme(
        channel: RecordingChannel,
        title: String,
        episode: String?,
        description: String?,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
    ): ScheduleResult

    /** Saves the rule and schedules every matching [listings] airing. */
    suspend fun addSeriesRule(channel: RecordingChannel, title: String, keepLast: Int, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule>

    /** Schedules matching airings of an existing rule from a fresh guide. Idempotent. */
    suspend fun expandSeries(ruleId: String, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule>

    /** Removes a schedule, or stops the capture if it is running. */
    suspend fun cancel(scheduleId: String)

    /**
     * Stops a series rule: removes it and, when [cancelUpcoming], cancels its scheduled, not-yet-running
     * airings. Finished recordings (and a capture already running) are kept.
     */
    suspend fun removeSeriesRule(ruleId: String, cancelUpcoming: Boolean = true)

    /** Removes a recording row and its file (stops it first if still capturing). */
    suspend fun deleteRecording(id: String)

    /** Runs a FAILED or CANCELLED recording again, now. */
    suspend fun retry(recordingId: String, nowMs: Long)

    /** Arms every waiting schedule again from its wall-clock fire time. For boot, clock and permission changes. */
    suspend fun rearmAll(nowMs: Long)

    /** Ids of schedules whose time has passed and that nothing has run. */
    suspend fun overdueIds(nowMs: Long): List<String>

    /** Fires everything [overdueIds] lists. Safe to call twice. */
    suspend fun sweepOverdue(nowMs: Long)

    /** Runs the schedule: at most one capture per id, however many times and from wherever it is called. */
    suspend fun fire(scheduleId: String, nowMs: Long): FireResult
}

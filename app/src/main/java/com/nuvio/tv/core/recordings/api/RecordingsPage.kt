package com.nuvio.tv.core.recordings.api

/** The page's groups, in the order they are drawn. */
enum class RecordingSection { SCHEDULED, RECORDING, DONE, FAILED }

/**
 * One line of the Recordings page. [id] is unique across the page (a schedule and its recording
 * never share one) so it can key a lazy list. [scheduleId] is what `cancel` takes, [recordingId]
 * what `deleteRecording` / `retry` take; a scheduled row has only the first, a finished row only the second.
 */
data class RecordingRow(
    val id: String,
    val section: RecordingSection,
    val title: String,
    val episode: String?,
    val channelName: String,
    val programmeStartMs: Long,
    val programmeEndMs: Long,
    /** Bytes on disk so far (RECORDING), final size (DONE), 0 otherwise. */
    val sizeBytes: Long,
    val status: RecordingStatus,
    val mode: CaptureMode,
    val problem: RecordingProblem?,
    val description: String?,
    val scheduleId: String?,
    val recordingId: String?,
    /** A FAILED / CANCELLED row can be run again only when it kept the schedule it came from. */
    val canRetry: Boolean,
    /** A scheduled row that already failed once and waits for its next try. */
    val retryPending: Boolean,
    /** NO_SPACE on a known size: what it needed and what was free (both or neither). */
    val problemNeedBytes: Long? = null,
    val problemFreeBytes: Long? = null,
)

data class RecordingsPageModel(val sections: Map<RecordingSection, List<RecordingRow>>) {
    fun rows(section: RecordingSection): List<RecordingRow> = sections[section].orEmpty()
    val isEmpty: Boolean get() = sections.values.all { it.isEmpty() }
}

/** What the Recordings page shows, derived from the port's state. Pure. */
object RecordingsPagePolicy {
    val ORDER: List<RecordingSection> = listOf(
        RecordingSection.SCHEDULED, RecordingSection.RECORDING, RecordingSection.DONE, RecordingSection.FAILED,
    )

    fun build(state: RecordingsState, progress: Map<String, Long>): RecordingsPageModel {
        // A schedule whose capture has started (or finished) is represented by its recording row.
        val handled = state.recordings
            .filter { it.status == RecordingStatus.RECORDING || it.status == RecordingStatus.DONE }
            .mapNotNull { it.scheduleId }
            .toSet()

        val scheduled = state.schedules
            .filterNot { it.id in handled }
            .sortedBy { it.programmeStartMs }
            .map { s ->
                RecordingRow(
                    id = "s:${s.id}", section = RecordingSection.SCHEDULED, title = s.title, episode = s.episode,
                    channelName = s.channel.name, programmeStartMs = s.programmeStartMs, programmeEndMs = s.programmeEndMs,
                    sizeBytes = 0L, status = RecordingStatus.SCHEDULED, mode = s.mode, problem = null,
                    description = s.description, scheduleId = s.id, recordingId = null, canRetry = false,
                    retryPending = s.attempts > 0 && s.nextTryMs > 0L,
                )
            }

        fun row(r: Recording, section: RecordingSection, size: Long) = RecordingRow(
            id = "r:${r.id}", section = section, title = r.title, episode = r.episode, channelName = r.channelName,
            programmeStartMs = r.programmeStartMs, programmeEndMs = r.programmeEndMs, sizeBytes = size, status = r.status,
            mode = r.mode, problem = r.problem, description = r.description, scheduleId = r.scheduleId, recordingId = r.id,
            canRetry = (r.status == RecordingStatus.FAILED || r.status == RecordingStatus.CANCELLED) && r.retrySource != null,
            retryPending = false, problemNeedBytes = r.problemNeedBytes, problemFreeBytes = r.problemFreeBytes,
        )

        val recording = state.recordings.filter { it.status == RecordingStatus.RECORDING }
            .sortedBy { it.programmeStartMs }
            .map { row(it, RecordingSection.RECORDING, maxOf(it.sizeBytes, progress[it.id] ?: 0L)) }
        val done = state.recordings.filter { it.status == RecordingStatus.DONE }
            .sortedByDescending { it.programmeStartMs }
            .map { row(it, RecordingSection.DONE, it.sizeBytes) }
        val failed = state.recordings.filter { it.status == RecordingStatus.FAILED || it.status == RecordingStatus.CANCELLED }
            .sortedByDescending { it.programmeStartMs }
            .map { row(it, RecordingSection.FAILED, 0L) }

        return RecordingsPageModel(
            mapOf(
                RecordingSection.SCHEDULED to scheduled,
                RecordingSection.RECORDING to recording,
                RecordingSection.DONE to done,
                RecordingSection.FAILED to failed,
            ),
        )
    }
}

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

    fun build(state: RecordingsState, progress: Map<String, Long>): RecordingsPageModel = TODO("wave 3")
}

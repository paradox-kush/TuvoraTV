package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.FireResult
import com.nuvio.tv.core.recordings.api.ListingInfo
import com.nuvio.tv.core.recordings.api.LowSpacePolicy
import com.nuvio.tv.core.recordings.api.RecordingAlarms
import com.nuvio.tv.core.recordings.api.RecordingCapabilities
import com.nuvio.tv.core.recordings.api.RecordingChannel
import com.nuvio.tv.core.recordings.api.RecordingFiles
import com.nuvio.tv.core.recordings.api.RecordingSaver
import com.nuvio.tv.core.recordings.api.RecordingSchedule
import com.nuvio.tv.core.recordings.api.RecordingsPort
import com.nuvio.tv.core.recordings.api.RecordingsState
import com.nuvio.tv.core.recordings.api.ScheduleResult
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Red-first stub: every method is TODO() until the repository lands. */
class RecordingsRepository(
    private val files: RecordingFiles,
    private val alarms: RecordingAlarms,
    private val saver: RecordingSaver,
    private val capabilities: RecordingCapabilities,
    private val lowSpacePolicy: () -> LowSpacePolicy = { LowSpacePolicy.STOP },
    private val preMs: Long = RecordingSchedulePolicy.DEFAULT_PRE_MS,
    private val postMs: Long = RecordingSchedulePolicy.DEFAULT_POST_MS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : RecordingsPort {
    override val state: StateFlow<RecordingsState> = MutableStateFlow(RecordingsState())
    override val progress: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    override suspend fun start(nowMs: Long) = TODO()
    override suspend fun scheduleProgramme(
        channel: RecordingChannel, title: String, episode: String?, description: String?,
        startMs: Long, endMs: Long, nowMs: Long,
    ): ScheduleResult = TODO()
    override suspend fun addSeriesRule(channel: RecordingChannel, title: String, keepLast: Int, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule> = TODO()
    override suspend fun expandSeries(ruleId: String, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule> = TODO()
    override suspend fun cancel(scheduleId: String) = TODO()
    override suspend fun deleteRecording(id: String) = TODO()
    override suspend fun retry(recordingId: String, nowMs: Long) = TODO()
    override suspend fun rearmAll(nowMs: Long) = TODO()
    override suspend fun overdueIds(nowMs: Long): List<String> = TODO()
    override suspend fun sweepOverdue(nowMs: Long) = TODO()
    override suspend fun fire(scheduleId: String, nowMs: Long): FireResult = TODO()
}

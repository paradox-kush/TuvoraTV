package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.Recording
import com.nuvio.tv.core.recordings.api.RecordingCatchUp
import com.nuvio.tv.core.recordings.api.RecordingChannel
import com.nuvio.tv.core.recordings.api.RecordingSchedule
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.SeriesRule

internal const val MIN = 60_000L
internal const val HOUR = 60 * MIN
internal const val DAY = 24 * HOUR

/** A fixed "now" far from zero (about 2026-11-05). */
internal const val NOW = 1_794_000_000_000L

internal fun xtream(days: Int = 3) = RecordingCatchUp.Xtream(
    baseUrl = "http://panel.example:8080", username = "user", password = "pass", streamId = 42,
    days = days, containerExtension = null,
)

internal fun channel(
    key: String = "ch1",
    sourceId: String = "src1",
    catchUp: RecordingCatchUp? = null,
) = RecordingChannel(
    key = key, sourceId = sourceId, name = "Channel $key", logo = null,
    liveUrl = "http://panel.example:8080/live/user/pass/42.ts", catchUp = catchUp,
)

internal fun schedule(
    id: String = "s1",
    mode: CaptureMode = CaptureMode.LIVE,
    start: Long = NOW + 2 * HOUR,
    end: Long = start + HOUR,
    pre: Long = 2 * MIN,
    post: Long = 5 * MIN,
    ch: RecordingChannel = channel(catchUp = if (mode == CaptureMode.CATCH_UP) xtream(3) else null),
    attempts: Int = 0,
    nextTry: Long = 0L,
    seriesRuleId: String? = null,
    title: String = "Show",
    episode: String? = null,
    description: String? = null,
) = RecordingSchedule(
    id = id, channel = ch, title = title, episode = episode, description = description,
    programmeStartMs = start, programmeEndMs = end, mode = mode, seriesRuleId = seriesRuleId,
    preMs = pre, postMs = post, attempts = attempts, nextTryMs = nextTry,
)

internal fun recording(
    id: String,
    status: RecordingStatus = RecordingStatus.DONE,
    start: Long = NOW - DAY,
    size: Long = 1_000_000_000L,
    seriesRuleId: String? = null,
    channelKey: String = "ch1",
    title: String = "Show",
    episode: String? = null,
    description: String? = null,
) = Recording(
    id = id, scheduleId = null, channelKey = channelKey, channelName = "Channel", title = title,
    episode = episode, description = description, programmeStartMs = start, programmeEndMs = start + HOUR,
    startedAtMs = start, endedAtMs = null, status = status, mode = CaptureMode.LIVE, sizeBytes = size,
    fileName = "$id.ts", seriesRuleId = seriesRuleId,
)

internal fun rule(id: String = "r1", title: String = "Show", keepLast: Int = 0, ch: RecordingChannel = channel()) =
    SeriesRule(id = id, channel = ch, title = title, keepLast = keepLast)

/** JUnit has no `assertIs` / value-returning `assertNotNull`; these keep the mobile test bodies readable. */
internal inline fun <reified T> assertIs(value: Any?, message: String? = null): T {
    org.junit.Assert.assertTrue(message ?: "expected ${T::class.simpleName} but was $value", value is T)
    return value as T
}

internal fun <T : Any> assertNotNull(value: T?, message: String? = null): T {
    org.junit.Assert.assertNotNull(message ?: "expected a non-null value", value)
    return value!!
}

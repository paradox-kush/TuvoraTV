package com.nuvio.tv.core.recordings.api

import com.nuvio.tv.core.recordings.internal.RecordingSchedulePolicy
import com.nuvio.tv.core.recordings.internal.SeriesEpisodeKey
import com.nuvio.tv.core.recordings.internal.SeriesExpansion

/** Why a programme cannot be recorded here, so the sheet can say it in one line. */
enum class RecordUnavailable {
    /** The guide row has no usable start / end. */
    BAD_TIMES,

    /** Not over yet, but the channel keeps no archive and this build cannot record live. */
    NEEDS_CATCH_UP,

    /** Over, and the provider's archive no longer holds it (or the channel has none). */
    PROGRAMME_OVER,
}

/** What the programme sheet shows for one programme. */
sealed interface RecordOffer {
    data class Record(val mode: CaptureMode) : RecordOffer

    /** Already waiting (or capturing): the sheet offers "Cancel recording" instead. */
    data class Scheduled(val scheduleId: String, val capturing: Boolean) : RecordOffer

    /** A finished recording of this airing is already on the device. */
    data object AlreadySaved : RecordOffer

    data class Unavailable(val reason: RecordUnavailable) : RecordOffer
}

sealed interface SeriesOffer {
    data object Record : SeriesOffer

    /** A rule for this title on this channel exists already. */
    data class Active(val ruleId: String) : SeriesOffer
    data object Unavailable : SeriesOffer
}

/** Decides what the guide's programme sheet offers. Pure: the sheet only draws the answer. */
object ProgrammeRecordPolicy {
    fun offer(
        channel: RecordingChannel,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        capabilities: RecordingCapabilities,
        state: RecordingsState,
    ): RecordOffer {
        if (startMs <= 0L || endMs <= startMs) return RecordOffer.Unavailable(RecordUnavailable.BAD_TIMES)
        state.schedules.firstOrNull { it.channel.key == channel.key && it.programmeStartMs == startMs }?.let { s ->
            val capturing = state.recordings.any { it.scheduleId == s.id && it.status == RecordingStatus.RECORDING }
            return RecordOffer.Scheduled(s.id, capturing)
        }
        if (state.recordings.any { it.channelKey == channel.key && it.programmeStartMs == startMs && it.status == RecordingStatus.DONE }) {
            return RecordOffer.AlreadySaved
        }
        RecordingSchedulePolicy.modeFor(channel, startMs, endMs, nowMs, capabilities)?.let { return RecordOffer.Record(it) }
        return RecordOffer.Unavailable(if (endMs <= nowMs) RecordUnavailable.PROGRAMME_OVER else RecordUnavailable.NEEDS_CATCH_UP)
    }

    fun seriesOffer(
        channel: RecordingChannel,
        title: String,
        capabilities: RecordingCapabilities,
        state: RecordingsState,
    ): SeriesOffer {
        if (title.isBlank()) return SeriesOffer.Unavailable
        val wanted = SeriesExpansion.normalizeTitle(title)
        state.series.firstOrNull { it.channel.key == channel.key && SeriesExpansion.normalizeTitle(it.title) == wanted }
            ?.let { return SeriesOffer.Active(it.id) }
        val possible = (channel.catchUp != null && capabilities.canSaveCatchUp) || capabilities.canRecordLive
        return if (possible) SeriesOffer.Record else SeriesOffer.Unavailable
    }

    /** "S02E05" / "E12" read from the guide text, or null. Shown with the recording and used to skip repeats. */
    fun episodeLabel(title: String, description: String?): String? =
        SeriesEpisodeKey.fromProgramme(title, description)?.normalized
}

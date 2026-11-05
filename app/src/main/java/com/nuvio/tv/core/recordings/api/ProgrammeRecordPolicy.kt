package com.nuvio.tv.core.recordings.api

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
    ): RecordOffer = TODO("wave 3")

    fun seriesOffer(
        channel: RecordingChannel,
        title: String,
        capabilities: RecordingCapabilities,
        state: RecordingsState,
    ): SeriesOffer = TODO("wave 3")

    /** "S02E05" / "E12" read from the guide text, or null. Shown with the recording and used to skip repeats. */
    fun episodeLabel(title: String, description: String?): String? = TODO("wave 3")
}

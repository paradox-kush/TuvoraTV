package com.nuvio.tv.core.recordings.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Neutral value types of the recordings feature (the `api` side of the firewall). Everything here
 * is persisted by `RecordingsCodec`, so a field is added with a default and never renamed.
 *
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), `LiveTvRecording.kt` / `LiveTvRecordingScheduler.kt`
 * (the schedule, series rule and channel snapshot shapes).
 */
@Serializable
enum class RecordingStatus { SCHEDULED, RECORDING, DONE, FAILED, CANCELLED }

/** LIVE = record as it airs; CATCH_UP = save from the provider's archive after it ends. */
@Serializable
enum class CaptureMode { LIVE, CATCH_UP }

@Serializable
enum class LowSpacePolicy { STOP, DELETE_OLDEST }

/**
 * Why a recording did not happen. The UI resolves its text from this enum; a raw exception
 * message or a provider host never reaches the screen.
 */
@Serializable
enum class RecordingProblem {
    NO_SPACE, NO_CATCH_UP, PROVIDER_REFUSED, NETWORK, STALLED, MISSED, CANCELLED_BY_USER, UNSUPPORTED_FORMAT, UNKNOWN,

    /** A live capture was due but this build cannot record live yet (wave 4, pending the live-pause branch). */
    LIVE_CAPTURE_PENDING,
}

/** What a platform can do: iPhone (false, true), Android (true, true), Desktop (true, true), Apple TV (false, false). */
@Serializable
data class RecordingCapabilities(
    val canRecordLive: Boolean,
    val canSaveCatchUp: Boolean,
    /** The platform can wake the app at a time (Android alarms). False where a schedule waits for the next foreground (iOS). */
    val canWakeApp: Boolean = true,
) {
    companion object {
        val IPHONE = RecordingCapabilities(canRecordLive = false, canSaveCatchUp = true, canWakeApp = false)
        val ANDROID = RecordingCapabilities(canRecordLive = true, canSaveCatchUp = true)
        val DESKTOP = RecordingCapabilities(canRecordLive = true, canSaveCatchUp = true)
        val APPLE_TV = RecordingCapabilities(canRecordLive = false, canSaveCatchUp = false, canWakeApp = false)
    }
}

/** What is needed to build a catch-up link for the channel, saved with the schedule. */
@Serializable
sealed interface RecordingCatchUp {
    val days: Int

    @Serializable
    @SerialName("m3u")
    data class M3u(
        val type: String,
        val source: String?,
        override val days: Int,
        val offsetMs: Long = 0,
    ) : RecordingCatchUp

    @Serializable
    @SerialName("xtream")
    data class Xtream(
        val baseUrl: String,
        val username: String,
        val password: String,
        val streamId: Int,
        override val days: Int,
        val containerExtension: String?,
        val serverOffsetMs: Long? = null,
    ) : RecordingCatchUp
}

/** A snapshot of the channel so a capture can start without loading Live TV. */
@Serializable
data class RecordingChannel(
    val key: String,
    val sourceId: String,
    val name: String,
    val logo: String?,
    val liveUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val catchUp: RecordingCatchUp? = null,
)

@Serializable
data class RecordingSchedule(
    val id: String,
    val channel: RecordingChannel,
    val title: String,
    val episode: String?,
    val description: String?,
    val programmeStartMs: Long,
    val programmeEndMs: Long,
    val mode: CaptureMode,
    val seriesRuleId: String? = null,
    val preMs: Long,
    val postMs: Long,
    val attempts: Int = 0,
    val nextTryMs: Long = 0L,
)

/** "Record series": every airing of [title] on [channel]. [keepLast] 0 keeps them all. */
@Serializable
data class SeriesRule(
    val id: String,
    val channel: RecordingChannel,
    val title: String,
    val keepLast: Int = 0,
)

@Serializable
data class Recording(
    val id: String,
    val scheduleId: String?,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val episode: String?,
    val description: String?,
    val programmeStartMs: Long,
    val programmeEndMs: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val status: RecordingStatus,
    val mode: CaptureMode,
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
    val problem: RecordingProblem? = null,
    val fileName: String,
    val seriesRuleId: String? = null,
    /** The schedule that produced a FAILED or CANCELLED row, kept so Retry can run it again. Null otherwise. */
    val retrySource: RecordingSchedule? = null,
    /** With [RecordingProblem.NO_SPACE] on a KNOWN size: bytes the save still needed. Null otherwise. */
    val problemNeedBytes: Long? = null,
    /** With [problemNeedBytes]: bytes that were free at the time. */
    val problemFreeBytes: Long? = null,
)

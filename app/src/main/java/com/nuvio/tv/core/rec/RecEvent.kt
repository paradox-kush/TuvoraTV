package com.nuvio.tv.core.rec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire model for the recommendation event stream (`rec_events`, see the backend's
 * docs/impression-logging-design.md). Field names and enum values are the contract the
 * `rec-events` edge function validates against — it rejects the WHOLE batch on any unknown
 * value, so nothing here may drift from the migration's CHECK constraints.
 *
 * NuvioMobile and NuvioDesktop carry mirrored twins of this file; the three apps share no code.
 */

/** Where the item was shown. Free text server-side (<=64 chars), constrained here on purpose. */
object RecSurface {
    const val HOME = "home"
    const val SEARCH = "search"
    const val DETAILS = "details"
    const val LIVE = "live"
    const val SPORTS = "sports"

    /** Addon catalogues get their own surface so their rows stay separable in training. */
    fun addon(addonId: String): String = "addon:${addonId.take(56)}"
}

object RecEventType {
    const val IMPRESSION = "impression"
    const val CLICK = "click"
    const val PLAY_START = "play_start"
    const val PLAY_PROGRESS = "play_progress"
    const val PLAY_COMPLETE = "play_complete"
}

/**
 * `series` is the SHOW (a poster on a shelf); `episode` is one episode of it, carrying the same
 * `tmdbId` plus season/episode. Logging the episode is what makes the like/dislike signal
 * possible — finishing eight episodes and bailing on the first are opposite outcomes, and the
 * show-level rollup happens at training time, not here.
 */
object RecContentType {
    const val MOVIE = "movie"
    const val SERIES = "series"
    const val EPISODE = "episode"
    const val LIVE = "live"
}

/** The four watched-fraction crossings that stand in for a star rating. */
val REC_PROGRESS_BUCKETS = intArrayOf(25, 50, 75, 90)

/**
 * `clientTs` and `profileId` are stamped by [RecEventLogger] at queue time, not by callers —
 * the profile must be read at the moment the event happens (a batch can straddle a profile
 * switch on a household TV), and a caller-supplied timestamp is one more thing to get wrong.
 */
@Serializable
data class RecEvent(
    @SerialName("event_type") val eventType: String,
    @SerialName("surface") val surface: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("client_ts") val clientTs: String = "",
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("row_id") val rowId: String? = null,
    @SerialName("row_index") val rowIndex: Int? = null,
    @SerialName("item_position") val itemPosition: Int? = null,
    /**
     * The app's own content id (`MetaPreview.id`): "tt0903747", "tmdb:1396", an addon id, an
     * IPTV stream key. This is the real join key — the client rarely knows a TMDB id at the
     * moment something is shown, and IPTV items often have none at all. Resolution happens at
     * training time.
     */
    @SerialName("item_id") val itemId: String? = null,
    /** Only when it was free — i.e. the id was already in "tmdb:<n>" form. */
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("progress_pct") val progressPct: Int? = null,
)

/**
 * A queued event plus the session it belongs to. Persisted one-per-line so a process kill loses
 * at most the partial tail; the session travels WITH the event because a batch flushed after a
 * cold start can hold events from more than one session, and the envelope only carries one.
 */
@Serializable
data class RecEventRecord(
    @SerialName("session_id") val sessionId: String,
    @SerialName("event") val event: RecEvent,
)

@Serializable
data class RecEventBatch(
    @SerialName("device_id") val deviceId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("app") val app: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("events") val events: List<RecEvent>,
)

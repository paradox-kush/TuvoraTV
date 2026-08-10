package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

/**
 * Xtream Codes `player_api.php` response shapes. Only the fields we actually use
 * are modelled.
 *
 * Real panels are wildly inconsistent about numeric types: the SAME field is an int
 * on one panel and a quoted string (or a bool) on another (verified live — e.g.
 * tmdb_id is "936075" on one server, 24831 on another). So every numeric field that
 * isn't free-text uses [@FlexInt], which tolerates number | "string" | true/false.
 * category_id stays String (always quoted in practice).
 *
 * ponytail: minimal field set, add fields when a screen needs them.
 */

/** Marks an Int field that may arrive as a JSON number, a quoted string, or a bool. */
@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
annotation class FlexInt

/** Coerces number | "string" | true/false | null -> Int?. Registered on the app Moshi. */
object FlexIntAdapter {
    @FromJson
    @FlexInt
    fun fromJson(reader: JsonReader): Int? = when (reader.peek()) {
        JsonReader.Token.NUMBER -> reader.nextInt()
        JsonReader.Token.STRING -> reader.nextString().trim().toIntOrNull()
        JsonReader.Token.BOOLEAN -> if (reader.nextBoolean()) 1 else 0
        JsonReader.Token.NULL -> reader.nextNull()
        else -> { reader.skipValue(); null }
    }

    @ToJson
    fun toJson(writer: JsonWriter, @FlexInt value: Int?) {
        writer.value(value?.toLong())
    }
}

// player_api.php?username=U&password=P  (no action)
data class XtreamAccountDto(
    @Json(name = "user_info") val userInfo: XtreamUserInfoDto?
    // ponytail: server_info dropped — fields were never read and panels send `port` as a
    // bare int, which broke Moshi's String decode. Moshi ignores the unknown key.
)

data class XtreamUserInfoDto(
    val username: String?,
    val password: String?,
    @FlexInt val auth: Int?,        // 1 = ok
    val status: String?,            // "Active", "Expired", ...
    @Json(name = "exp_date") val expDate: String?,   // unix seconds (string) or null = unlimited
    @Json(name = "active_cons") val activeConnections: String?,
    @Json(name = "max_connections") val maxConnections: String?
)

data class XtreamCategoryDto(
    @Json(name = "category_id") val categoryId: String?,
    @Json(name = "category_name") val categoryName: String?,
    @FlexInt @Json(name = "parent_id") val parentId: Int?
)

// action=get_live_streams
data class XtreamLiveStreamDto(
    @FlexInt val num: Int?,
    val name: String?,
    @FlexInt @Json(name = "stream_id") val streamId: Int?,
    @Json(name = "stream_icon") val streamIcon: String?,
    @Json(name = "epg_channel_id") val epgChannelId: String?,
    @Json(name = "category_id") val categoryId: String?,
    @FlexInt @Json(name = "tv_archive") val tvArchive: Int?
)

// action=get_vod_streams
data class XtreamVodStreamDto(
    @FlexInt val num: Int?,
    val name: String?,
    @FlexInt @Json(name = "stream_id") val streamId: Int?,
    @Json(name = "stream_icon") val streamIcon: String?,
    @Json(name = "category_id") val categoryId: String?,
    @Json(name = "container_extension") val containerExtension: String?,
    val rating: String?,
    @Json(name = "added") val added: String?,
    // XUI.one panels ship a TMDB id in the bulk list (~90% populated on tested providers) —
    // the free tier of TMDB->stream matching
    @FlexInt val tmdb: Int?
)

// action=get_series
data class XtreamSeriesDto(
    @FlexInt @Json(name = "series_id") val seriesId: Int?,
    val name: String?,
    val cover: String?,
    @Json(name = "category_id") val categoryId: String?,
    val plot: String?,
    val rating: String?,
    @FlexInt val tmdb: Int?,
    // panels send BOTH spellings; releaseDate is the one XUI populates
    @Json(name = "releaseDate") val releaseDate: String?,
    @Json(name = "release_date") val releaseDateAlt: String?
)

// action=get_vod_info&vod_id=X -> { info: {...}, movie_data: {...} }
// If a panel returns "info": [] (empty array) the body fails to parse and the caller
// falls back to non-enriched meta — acceptable (no tmdb_id available there anyway).
data class XtreamVodInfoResponseDto(
    val info: XtreamVodInfoDto?
)

data class XtreamVodInfoDto(
    @FlexInt @Json(name = "tmdb_id") val tmdbId: Int?,
    // artwork for panels whose bulk get_vod_streams ships empty stream_icons (PosterEnricher)
    @Json(name = "movie_image") val movieImage: String?,
    @Json(name = "cover_big") val coverBig: String?,
    // verify signal when a panel has no tmdb_id: year check against the TMDB target
    @Json(name = "releasedate") val releaseDate: String?,
    @Json(name = "release_date") val releaseDateAlt: String?
)

// action=get_series_info&series_id=X -> { info:{...}, episodes: { "1":[...], "2":[...] } }
data class XtreamSeriesInfoResponseDto(
    val info: XtreamSeriesInfoDto?,
    val episodes: Map<String, List<XtreamEpisodeDto>>?
)

data class XtreamSeriesInfoDto(
    val name: String?,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    @Json(name = "backdrop_path") val backdropPath: List<String>?,
    @FlexInt @Json(name = "tmdb_id") val tmdbId: Int?,
    @Json(name = "releaseDate") val releaseDate: String?,
    @Json(name = "release_date") val releaseDateAlt: String?
)

data class XtreamEpisodeDto(
    val id: String?,                 // episode/stream id (string)
    @FlexInt @Json(name = "episode_num") val episodeNum: Int?,
    val title: String?,
    @Json(name = "container_extension") val containerExtension: String?,
    @FlexInt val season: Int?,
    val info: XtreamEpisodeInfoDto?
)

data class XtreamEpisodeInfoDto(
    val plot: String?,
    @Json(name = "movie_image") val movieImage: String?,
    val duration: String?
)

// action=get_short_epg -> { epg_listings: [...] }
data class XtreamShortEpgResponseDto(
    @Json(name = "epg_listings") val listings: List<XtreamEpgEntryDto>?
)

data class XtreamEpgEntryDto(
    val id: String?,
    val title: String?,            // base64
    val description: String?,      // base64
    @Json(name = "start_timestamp") val startTimestamp: String?,  // unix seconds (string)
    @Json(name = "stop_timestamp") val stopTimestamp: String?,
    @FlexInt @Json(name = "now_playing") val nowPlaying: Int?
)

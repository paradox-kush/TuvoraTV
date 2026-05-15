package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TorboxEnvelopeDto<T>(
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "data") val data: T? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "detail") val detail: String? = null
)

@JsonClass(generateAdapter = true)
data class TorboxCreateTorrentDataDto(
    @Json(name = "torrent_id") val torrentId: Int? = null,
    @Json(name = "id") val id: Int? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "auth_id") val authId: String? = null
) {
    fun resolvedTorrentId(): Int? = torrentId ?: id
}

@JsonClass(generateAdapter = true)
data class TorboxTorrentDataDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "hash") val hash: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "files") val files: List<TorboxTorrentFileDto>? = null
)

@JsonClass(generateAdapter = true)
data class TorboxTorrentFileDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "short_name") val shortName: String? = null,
    @Json(name = "absolute_path") val absolutePath: String? = null,
    @Json(name = "mimetype") val mimeType: String? = null,
    @Json(name = "size") val size: Long? = null
) {
    fun displayName(): String = listOfNotNull(name, shortName, absolutePath)
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
}

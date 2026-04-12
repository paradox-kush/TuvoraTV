package com.nuvio.tv.data.remote.dto.kitsu

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTOs for Kitsu's JSON:API — `GET /library-entries`, `PATCH /library-entries/{id}`,
 * `POST /library-entries`, `GET /users?filter[self]=true`.
 *
 * Kitsu wraps everything in the standard JSON:API envelope:
 *
 *   { data: [ { id, type, attributes, relationships } ],
 *     included: [ ... ],
 *     links: { next, … }, meta: { count } }
 *
 * Only the attributes we actually consume are modelled. Fields on the `meta`
 * and `links` blocks are used for pagination.
 *
 * Status wire values: current | planned | completed | on_hold | dropped.
 */

@JsonClass(generateAdapter = true)
data class KitsuLibraryPageDto(
    @Json(name = "data") val data: List<KitsuLibraryEntryDto> = emptyList(),
    @Json(name = "included") val included: List<KitsuIncludedDto> = emptyList(),
    @Json(name = "meta") val meta: KitsuMetaDto? = null,
    @Json(name = "links") val links: KitsuLinksDto? = null
)

@JsonClass(generateAdapter = true)
data class KitsuLibraryEntryDto(
    @Json(name = "id") val id: String,
    @Json(name = "type") val type: String, // always "library-entries"
    @Json(name = "attributes") val attributes: KitsuLibraryEntryAttributesDto? = null,
    @Json(name = "relationships") val relationships: KitsuLibraryEntryRelationshipsDto? = null
)

@JsonClass(generateAdapter = true)
data class KitsuLibraryEntryAttributesDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "progress") val progress: Int = 0,
    @Json(name = "ratingTwenty") val ratingTwenty: Int? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "reconsuming") val reconsuming: Boolean = false,
    @Json(name = "reconsumeCount") val reconsumeCount: Int = 0,
    @Json(name = "progressedAt") val progressedAt: String? = null,
    @Json(name = "startedAt") val startedAt: String? = null,
    @Json(name = "finishedAt") val finishedAt: String? = null,
    @Json(name = "updatedAt") val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuLibraryEntryRelationshipsDto(
    @Json(name = "anime") val anime: KitsuRelationshipDto? = null,
    @Json(name = "user") val user: KitsuRelationshipDto? = null
)

@JsonClass(generateAdapter = true)
data class KitsuRelationshipDto(
    @Json(name = "data") val data: KitsuRelationshipRefDto? = null
)

@JsonClass(generateAdapter = true)
data class KitsuRelationshipRefDto(
    @Json(name = "id") val id: String,
    @Json(name = "type") val type: String
)

/**
 * A member of the `included` array. Anime, user, and mappings all share this
 * envelope and are discriminated by [type].
 */
@JsonClass(generateAdapter = true)
data class KitsuIncludedDto(
    @Json(name = "id") val id: String,
    @Json(name = "type") val type: String,
    @Json(name = "attributes") val attributes: KitsuIncludedAttributesDto? = null,
    @Json(name = "relationships") val relationships: Map<String, KitsuRelationshipListDto>? = null
)

/**
 * Catch-all attributes dto. Every field is optional and only the subset that
 * matches the [KitsuIncludedDto.type] is populated by the server.
 */
@JsonClass(generateAdapter = true)
data class KitsuIncludedAttributesDto(
    // anime
    @Json(name = "canonicalTitle") val canonicalTitle: String? = null,
    @Json(name = "titles") val titles: Map<String, String>? = null,
    @Json(name = "posterImage") val posterImage: KitsuImageSetDto? = null,
    @Json(name = "coverImage") val coverImage: KitsuImageSetDto? = null,
    @Json(name = "episodeCount") val episodeCount: Int? = null,
    @Json(name = "episodeLength") val episodeLength: Int? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "subtype") val subtype: String? = null,
    @Json(name = "showType") val showType: String? = null,
    @Json(name = "startDate") val startDate: String? = null,
    // user
    @Json(name = "name") val name: String? = null,
    // mappings
    @Json(name = "externalSite") val externalSite: String? = null,
    @Json(name = "externalId") val externalId: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuImageSetDto(
    @Json(name = "tiny") val tiny: String? = null,
    @Json(name = "small") val small: String? = null,
    @Json(name = "medium") val medium: String? = null,
    @Json(name = "large") val large: String? = null,
    @Json(name = "original") val original: String? = null
)

/** Relationships with multiple entries (e.g. anime.mappings — List of refs). */
@JsonClass(generateAdapter = true)
data class KitsuRelationshipListDto(
    @Json(name = "data") val data: List<KitsuRelationshipRefDto>? = null
)

@JsonClass(generateAdapter = true)
data class KitsuMetaDto(
    @Json(name = "count") val count: Int? = null,
    @Json(name = "statusCounts") val statusCounts: Map<String, Int>? = null
)

@JsonClass(generateAdapter = true)
data class KitsuLinksDto(
    @Json(name = "first") val first: String? = null,
    @Json(name = "next") val next: String? = null,
    @Json(name = "last") val last: String? = null,
    @Json(name = "prev") val prev: String? = null
)

// --- Write bodies --- //

/**
 * JSON:API patch body. Only `attributes` fields actually mutated should be
 * included; the server ignores unset fields (hence all nullable).
 */
@JsonClass(generateAdapter = true)
data class KitsuLibraryPatchDto(
    @Json(name = "data") val data: KitsuLibraryPatchDataDto
)

@JsonClass(generateAdapter = true)
data class KitsuLibraryPatchDataDto(
    @Json(name = "id") val id: String,
    @Json(name = "type") val type: String = "library-entries",
    @Json(name = "attributes") val attributes: KitsuLibraryPatchAttributesDto
)

@JsonClass(generateAdapter = true)
data class KitsuLibraryPatchAttributesDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "progress") val progress: Int? = null,
    @Json(name = "ratingTwenty") val ratingTwenty: Int? = null,
    @Json(name = "reconsuming") val reconsuming: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class KitsuLibraryCreateDto(
    @Json(name = "data") val data: KitsuLibraryCreateDataDto
)

@JsonClass(generateAdapter = true)
data class KitsuLibraryCreateDataDto(
    @Json(name = "type") val type: String = "library-entries",
    @Json(name = "attributes") val attributes: KitsuLibraryPatchAttributesDto,
    @Json(name = "relationships") val relationships: KitsuLibraryCreateRelsDto
)

@JsonClass(generateAdapter = true)
data class KitsuLibraryCreateRelsDto(
    @Json(name = "user") val user: KitsuRelationshipDto,
    @Json(name = "anime") val anime: KitsuRelationshipDto
)

/**
 * Single-resource response (PATCH /library-entries/{id}, POST /library-entries).
 */
@JsonClass(generateAdapter = true)
data class KitsuLibraryEntryResponseDto(
    @Json(name = "data") val data: KitsuLibraryEntryDto? = null
)

/** `GET /users?filter[self]=true` response — small subset only. */
@JsonClass(generateAdapter = true)
data class KitsuUserPageDto(
    @Json(name = "data") val data: List<KitsuUserEntryDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class KitsuUserEntryDto(
    @Json(name = "id") val id: String,
    @Json(name = "type") val type: String,
    @Json(name = "attributes") val attributes: KitsuUserAttributesDto? = null
)

@JsonClass(generateAdapter = true)
data class KitsuUserAttributesDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "avatar") val avatar: KitsuImageSetDto? = null,
    @Json(name = "slug") val slug: String? = null
)

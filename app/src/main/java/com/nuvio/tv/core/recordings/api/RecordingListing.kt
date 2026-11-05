package com.nuvio.tv.core.recordings.api

/** One programme from the guide, as a series rule sees it. [episodeText] is the guide's own episode field (xmltv_ns or on-screen). */
data class ListingInfo(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val episodeText: String? = null,
)

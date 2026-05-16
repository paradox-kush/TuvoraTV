package com.nuvio.tv.domain.model.locallibrary

import androidx.compose.runtime.Immutable

/**
 * What a [com.nuvio.tv.data.locallibrary.source.LocalLibrarySource] returns when
 * asked to make an item playable. The gateway converts this into a [com.nuvio.tv.domain.model.Stream].
 */
@Immutable
data class ResolvedStream(
    val url: String,
    /** Extra headers required for playback (e.g. Jellyfin auth bearer). */
    val headers: Map<String, String> = emptyMap(),
    val mimeHint: String? = null,
    /** "http", "https", "smb", "content", "file" — used to route the right ExoPlayer DataSource. */
    val scheme: String,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    /** Sidecar / server-side subtitle files discovered alongside the media. */
    val subtitles: List<ExternalSubtitleFile> = emptyList()
)

/**
 * An external subtitle track discovered by a local library source. Carried on
 * [ResolvedStream] and ultimately passed to ExoPlayer as a
 * [androidx.media3.common.MediaItem.SubtitleConfiguration].
 */
@Immutable
data class ExternalSubtitleFile(
    val url: String,
    /** Human-friendly label for the track menu (e.g. "English", "Spanish (Forced)"). */
    val displayName: String,
    /** BCP-47 normalized language code (e.g. "en", "pt-br"), or null if unknown. */
    val language: String?,
    /** ExoPlayer MIME type (APPLICATION_SUBRIP / TEXT_VTT / TEXT_SSA). */
    val mimeType: String,
    val isForced: Boolean = false,
    /** Auth/etc. headers required to fetch the subtitle file. */
    val headers: Map<String, String> = emptyMap(),
    val source: Source = Source.LOCAL_SIDECAR
) {
    enum class Source { LOCAL_SIDECAR, JELLYFIN }
}

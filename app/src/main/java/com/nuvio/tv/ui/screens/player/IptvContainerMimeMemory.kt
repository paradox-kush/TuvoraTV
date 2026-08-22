package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork-only: per-host learned container MIME memory + the ".ts promised, HLS delivered" mismatch
 * detector for IPTV live. Extracted from the shared PlayerMediaSourceFactory companion so that file
 * stays takeable from upstream (research/tv-player-mpv-engine-ownership.md, Part B). Consumers: the
 * factory's own mime inference + the Xtream live guide (fork).
 */
internal object IptvContainerMimeMemory {
    // --- Container mismatch: what the URL claims vs what the server actually sends ----------
    //
    // Panels routinely 302 an Xtream `.ts` live URL to an HLS playlist. The extension is all
    // [inferMimeType] has to go on before the first byte arrives, so ExoPlayer commits to the
    // progressive TS path, is handed `#EXTM3U` text instead of TS packets, and fails with
    // UnrecognizedInputFormatException. Once a probe has learned what a host really serves for
    // a given extension we remember it, so only the FIRST tune on that provider pays a failed
    // attempt — every later zap builds the right source straight away.
    //
    // Keyed on host + requested extension, never host alone: the same panel serves real `.mp4`
    // VOD alongside redirect-to-HLS `.ts` live, and those must not learn from each other.
    private val learnedContainerMimeTypes = ConcurrentHashMap<String, String>()

    // Extension-less URLs get their own bucket rather than being skipped: a Stalker create_link
    // is typically `http://host/ch/12345_` with no extension, and its token rotates per play —
    // the host is the only stable part, so that is what the memory has to hang on. An empty
    // extension can't collide with "$host|mp4", so the buckets still can't contaminate.
    internal fun learnedContainerKey(url: String): String? {
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        val host = withoutQuery
            .substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringAfterLast('@')
            .lowercase(Locale.US)
            .takeIf { it.isNotBlank() }
            ?: return null
        val extension = withoutQuery.substringAfterLast('/', missingDelimiterValue = "")
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.US)
        return "$host|$extension"
    }

    internal fun learnedContainerMimeType(url: String): String? =
        learnedContainerKey(url)?.let(learnedContainerMimeTypes::get)

    internal fun rememberContainerMimeType(url: String, mimeType: String) {
        learnedContainerKey(url)?.let { learnedContainerMimeTypes[it] = mimeType }
    }

    /** The "these bytes aren't the container the URL promised" family of failures. */
    internal fun isContainerMismatch(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.cause?.toString()?.contains("UnrecognizedInputFormatException") == true

    /**
     * What to retry a container mismatch as. HLS is the only realistic answer in practice —
     * panels that don't hand back real MPEG-TS on a `.ts` path are redirecting to an m3u8.
     *
     * We deliberately do NOT probe the server first. A probe would cost an extra request per
     * failure, which is two things we can't afford: another connection on lines capped at
     * `max_connections=1`, and — on Stalker — consuming the single-use `create_link` token,
     * so the retry that followed would 401. Retrying blind costs nothing, and the memory
     * above is written only once the retry has actually played.
     */
    internal val CONTAINER_MISMATCH_RETRY_MIME_TYPE = MimeTypes.APPLICATION_M3U8
}

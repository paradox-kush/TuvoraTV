package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.RecordingProblem

/** Tells an HLS playlist (text) from a media file by what the server said and the first bytes. */
internal object DownloadSniff {
    /** Bytes to peek at; enough for `#EXTM3U` (UTF-8 BOM and leading whitespace tolerated). */
    const val PEEK_BYTES = 16

    private val MAGIC = "#EXTM3U".toByteArray(Charsets.US_ASCII)

    fun isHlsPlaylist(contentType: String?, firstBytes: ByteArray): Boolean {
        if (contentType != null && contentType.contains("mpegurl", ignoreCase = true)) return true
        return startsWithExtM3u(firstBytes)
    }

    private fun startsWithExtM3u(bytes: ByteArray): Boolean {
        var i = 0
        // A UTF-8 byte order mark, then any blank space, may come before the tag.
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && (bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() || bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
        if (bytes.size - i < MAGIC.size) return false
        for (k in MAGIC.indices) if (bytes[i + k] != MAGIC[k]) return false
        return true
    }
}

/** Belt and braces for a "finished" save: a programme of a minute or more that came to a few KiB is not a media file. */
internal object RecordingSanityGuard {
    const val MIN_PROGRAMME_MS = 60_000L
    const val MIN_PLAUSIBLE_BYTES = 64L * 1024

    /** True when [sizeBytes] is implausibly small for a programme [programmeMs] long. */
    fun implausiblyTiny(programmeMs: Long, sizeBytes: Long): Boolean =
        programmeMs >= MIN_PROGRAMME_MS && sizeBytes < MIN_PLAUSIBLE_BYTES

    /** Why a finished save must not be Done: UNSUPPORTED_FORMAT for a playlist, NETWORK (retryable) otherwise; null when plausible. */
    fun verdict(programmeMs: Long, sizeBytes: Long, firstBytes: ByteArray): RecordingProblem? = when {
        !implausiblyTiny(programmeMs, sizeBytes) -> null
        DownloadSniff.isHlsPlaylist(null, firstBytes) -> RecordingProblem.UNSUPPORTED_FORMAT
        else -> RecordingProblem.NETWORK
    }
}

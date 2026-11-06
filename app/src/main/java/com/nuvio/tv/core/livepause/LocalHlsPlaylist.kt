package com.nuvio.tv.core.livepause

/**
 * The local `index.m3u8` the player reloads like a live playlist (research/catchup-recordings-2026-11.md §5).
 * Pure text; the owner writes it atomically through [LiveSegmentStore.writePlaylistAtomically].
 *
 * A rewind buffer drops pieces from the front, which an `EVENT` playlist is not allowed to do, so
 * [eventType] is for recordings (keep-all) only.
 */
object LocalHlsPlaylist {
    fun render(
        pieces: List<LivePiece>,
        targetDurationSec: Int,
        mediaSequence: Long,
        ended: Boolean,
        eventType: Boolean,
        initSegment: String?,
    ): String {
        val sb = StringBuilder()
        // EXT-X-MAP needs protocol version 5+; 6 is what the reference used for fMP4.
        sb.append("#EXTM3U\n#EXT-X-VERSION:").append(if (initSegment != null) 6 else 3).append('\n')
        sb.append("#EXT-X-TARGETDURATION:").append(targetDurationSec).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append('\n')
        if (eventType) sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        if (initSegment != null) sb.append("#EXT-X-MAP:URI=\"").append(initSegment).append("\"\n")
        for (piece in pieces) {
            if (piece.discontinuity) sb.append("#EXT-X-DISCONTINUITY\n")
            sb.append("#EXTINF:").append(formatSeconds(piece.durationMs)).append(",\n")
            sb.append(piece.name).append('\n')
        }
        if (ended) sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    /** `%.3f` without String.format (not available in Kotlin/Native common code). */
    internal fun formatSeconds(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        val frac = (v % 1000).toString().padStart(3, '0')
        return "${v / 1000}.$frac"
    }
}

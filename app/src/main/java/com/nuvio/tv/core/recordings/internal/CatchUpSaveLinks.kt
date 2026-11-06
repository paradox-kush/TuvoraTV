package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.iptv.M3uCatchUp
import com.nuvio.tv.core.iptv.M3uCatchUpInfo
import com.nuvio.tv.core.iptv.XtreamCatchUp
import com.nuvio.tv.core.recordings.api.RecordingCatchUp
import com.nuvio.tv.core.recordings.api.RecordingChannel
import com.nuvio.tv.core.recordings.api.RecordingProblem

/**
 * The links to try when saving a programme from a provider's catch-up archive. Reuses the replay
 * builders ([M3uCatchUp], [XtreamCatchUp]) so a dialect is fixed in one place. A save needs a
 * progressive file (TS / MP4): an HLS playlist is only a fallback, listed after every progressive
 * link, and a channel that offers nothing else is UNSUPPORTED_FORMAT until HLS segment saving lands.
 *
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), the catch-up save path of `LiveTvRecordingScheduler`.
 */
object CatchUpSaveLinks {
    /** A cut-off save resumes this far before where it got to (links go by the minute, so a few seconds may repeat). */
    const val RESUME_OVERLAP_MS = 30_000L

    /** Links for [fromMs]..[toMs], progressive first. Empty when the channel has no catch-up or no link can be built. */
    fun candidates(channel: RecordingChannel, fromMs: Long, toMs: Long, nowMs: Long): List<String> {
        val raw = when (val cu = channel.catchUp) {
            null -> emptyList()
            is RecordingCatchUp.M3u -> m3u(cu, channel.liveUrl, fromMs, toMs, nowMs)
            is RecordingCatchUp.Xtream -> XtreamCatchUp.candidateUrls(
                baseUrl = cu.baseUrl, username = cu.username, password = cu.password, streamId = cu.streamId,
                startMs = fromMs, endMs = toMs, containerExtension = cu.containerExtension, serverOffsetMs = cu.serverOffsetMs,
            )
        }
        // Every HLS link also gets its TS twin (panels that do not offer HLS), then progressive goes first.
        val expanded = raw.flatMap { url ->
            if (isProgressive(url) || isUnknownFormat(url)) listOf(url) else listOfNotNull(url, M3uCatchUp.tsFallback(url))
        }.distinct()
        // Known-progressive first, then links whose format the panel picks (may turn out to be a playlist),
        // then the explicit playlists.
        val progressive = expanded.filter(::isProgressive)
        val unknown = expanded.filter(::isUnknownFormat)
        val hls = expanded.filterNot { isProgressive(it) || isUnknownFormat(it) }
        return progressive + unknown + hls
    }

    /** The rest of a save that was cut off after [doneMs] of footage: from a little before where it got to. */
    fun resumeCandidates(channel: RecordingChannel, fromMs: Long, doneMs: Long, toMs: Long, nowMs: Long): List<String> {
        val resumeFrom = (fromMs + doneMs - RESUME_OVERLAP_MS).coerceAtLeast(fromMs)
        return candidates(channel, resumeFrom, toMs, nowMs)
    }

    /** False for an HLS playlist (`.m3u8`, or an `extension=m3u8` query) and for an [isUnknownFormat] link; true for a file that can be saved as it comes. */
    fun isProgressive(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        val query = lower.substringAfter('?', "")
        return !path.endsWith(".m3u8") && "extension=m3u8" !in query && !isUnknownFormat(url)
    }

    /**
     * An Xtream `timeshift.php` link with no `extension=` parameter: the panel picks the format and many answer
     * with an HLS VOD playlist, so it is neither known-progressive nor a known playlist. Tried after every
     * known-progressive link; the saver sniffs what comes back.
     */
    fun isUnknownFormat(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        val query = lower.substringAfter('?', "")
        if (!path.endsWith("/timeshift.php")) return false
        return query.split('&').none { it.startsWith("extension=") }
    }

    /** Why [candidates] cannot be saved, or null when at least one link can: no catch-up at all, or HLS only. */
    fun problemFor(candidates: List<String>): RecordingProblem? = when {
        candidates.isEmpty() -> RecordingProblem.NO_CATCH_UP
        candidates.none { isProgressive(it) || isUnknownFormat(it) } -> RecordingProblem.UNSUPPORTED_FORMAT
        else -> null
    }

    private fun m3u(cu: RecordingCatchUp.M3u, liveUrl: String, fromMs: Long, toMs: Long, nowMs: Long): List<String> {
        val type = M3uCatchUp.catchUpType(cu.type, cu.source) ?: return emptyList()
        val info = M3uCatchUpInfo(type, cu.source, cu.days.coerceAtLeast(1))
        val primary = M3uCatchUp.build(info, liveUrl, fromMs, toMs, nowMs, cu.offsetMs) ?: return emptyList()
        return listOf(primary) + M3uCatchUp.alternatives(primary, offsetMs = cu.offsetMs)
    }
}

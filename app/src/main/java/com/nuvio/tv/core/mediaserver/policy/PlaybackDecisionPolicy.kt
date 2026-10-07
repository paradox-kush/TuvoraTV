package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.contracts.PlaybackPlayMethod

/**
 * How a media-server item is played, decided at MINT time (design 5.5) from the `MediaSource` the server
 * negotiated in PlaybackInfo - listing never calls PlaybackInfo. Direct play is the default; a transcode
 * only on a user quality cap or after direct play failed. Pure: no I/O, no DTOs (the caller maps the
 * source into [SourceFacts]).
 */
internal object PlaybackDecisionPolicy {
    /** What the server said about one `MediaSource` (PlaybackInfo's negotiation). */
    data class SourceFacts(
        val id: String?,
        /** `File` for a local file; `Http`/`Rtmp`/... for a `.strm` or remote source ([isFile] false). */
        val protocol: String?,
        val container: String?,
        val supportsDirectPlay: Boolean,
        val supportsDirectStream: Boolean,
        val supportsTranscoding: Boolean,
        val directStreamUrl: String?,
        val transcodingUrl: String?,
    ) {
        val isFile: Boolean get() = protocol.isNullOrBlank() || protocol.equals("File", ignoreCase = true)
    }

    sealed interface Plan {
        /** `/Videos/{id}/stream?Static=true&MediaSourceId=..` - the original file, tokenless on Jellyfin. */
        data class StaticStream(val mediaSourceId: String?) : Plan

        /** A URL the SERVER built (`DirectStreamUrl` / `TranscodingUrl`; relative or absolute, its own token included). */
        data class ServerUrl(val pathOrUrl: String) : Plan

        data class NotPlayable(val reason: Reason) : Plan
    }

    enum class Reason { NO_PLAYABLE_PATH }

    data class Decision(val plan: Plan, val method: PlaybackPlayMethod?)

    fun decide(source: SourceFacts, userBitrateCap: Long?, directPlayFailed: Boolean): Decision {
        val transcodeUrl = source.transcodingUrl?.takeIf { it.isNotBlank() }
        val directUrl = source.directStreamUrl?.takeIf { it.isNotBlank() }
        val wantsTranscode = userBitrateCap != null || directPlayFailed
        if (wantsTranscode && source.supportsTranscoding && transcodeUrl != null) {
            return Decision(Plan.ServerUrl(transcodeUrl), PlaybackPlayMethod.TRANSCODE)
        }
        if (!source.isFile) {
            // Static=true is rejected for non-file protocols (.strm, remote): the server's own URL is the way in.
            return when {
                directUrl != null -> Decision(Plan.ServerUrl(directUrl), PlaybackPlayMethod.DIRECT_STREAM)
                transcodeUrl != null -> Decision(Plan.ServerUrl(transcodeUrl), PlaybackPlayMethod.TRANSCODE)
                else -> Decision(Plan.NotPlayable(Reason.NO_PLAYABLE_PATH), null)
            }
        }
        if (source.supportsDirectPlay) return Decision(Plan.StaticStream(source.id), PlaybackPlayMethod.DIRECT_PLAY)
        if (source.supportsDirectStream) {
            return if (transcodeUrl != null) Decision(Plan.ServerUrl(transcodeUrl), PlaybackPlayMethod.DIRECT_STREAM)
            else Decision(Plan.StaticStream(source.id), PlaybackPlayMethod.DIRECT_STREAM)
        }
        if (source.supportsTranscoding && transcodeUrl != null) {
            return Decision(Plan.ServerUrl(transcodeUrl), PlaybackPlayMethod.TRANSCODE)
        }
        return Decision(Plan.NotPlayable(Reason.NO_PLAYABLE_PATH), null)
    }

    /** The wire value of the `PlayMethod` member of a playback report. */
    fun wireMethod(method: PlaybackPlayMethod?): String = when (method) {
        PlaybackPlayMethod.DIRECT_STREAM -> "DirectStream"
        PlaybackPlayMethod.TRANSCODE -> "Transcode"
        PlaybackPlayMethod.DIRECT_PLAY, null -> "DirectPlay"
    }

    /** Server ticks (100 ns) -> milliseconds, and back. */
    fun ticksToMs(ticks: Long): Long = ticks / 10_000L
    fun msToTicks(ms: Long): Long = ms * 10_000L

    /** [startAutomatically]: Tuvora has no progress of its own, so the server's position is simply where playback starts. */
    data class ResumeOffer(val serverPositionMs: Long, val startAutomatically: Boolean = false)

    private const val MIN_OFFER_POSITION_MS = 10_000L
    private const val MIN_DIFFERENCE_MS = 30_000L
    private const val NEARLY_FINISHED = 0.95

    /**
     * Where a media-server item resumes (D3, regression pass 5.12; owner decisions 2026-10-06): the item fetch at play
     * time returns the server's position (`UserData.PlaybackPositionTicks`); one fetch, no extra request.
     *  - Tuvora has NO progress of its own: start from the server's position, no question (the reference clients -
     *    Plezy, jellyfin-androidtv, jellyfin-web - all pass the server's position up front as the start time).
     *  - Tuvora has its own record and the item was watched elsewhere (the server has a later play) with a really
     *    different position: offer the jump ("Continue at hh:mm?").
     * Null = just resume from Tuvora's record.
     */
    fun resumeOffer(
        serverPositionMs: Long?,
        serverLastPlayedAtMs: Long?,
        tuvoraPositionMs: Long?,
        tuvoraUpdatedAtMs: Long?,
        durationMs: Long?,
    ): ResumeOffer? {
        val server = serverPositionMs ?: return null
        if (server < MIN_OFFER_POSITION_MS) return null
        if (durationMs != null && durationMs > 0 && server >= durationMs * NEARLY_FINISHED) return null
        val tuvora = tuvoraPositionMs ?: 0L
        if (tuvora < MIN_OFFER_POSITION_MS) return ResumeOffer(server, startAutomatically = true)
        if (server - tuvora <= MIN_DIFFERENCE_MS && tuvora - server <= MIN_DIFFERENCE_MS) return null
        val serverIsNewer = if (serverLastPlayedAtMs != null && tuvoraUpdatedAtMs != null) serverLastPlayedAtMs > tuvoraUpdatedAtMs else server > tuvora
        return if (serverIsNewer) ResumeOffer(server, startAutomatically = false) else null
    }
}

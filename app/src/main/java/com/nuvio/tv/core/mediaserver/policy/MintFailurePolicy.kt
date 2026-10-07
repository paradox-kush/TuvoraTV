package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.client.MediaServerException

/**
 * Why a media-server stream could not be started, as the viewer should hear it. An on-demand server (its
 * titles are created on demand and the file is fetched when you press play) fails in a new way: PlaybackInfo
 * answers 200 with a source that has nothing playable - the file behind the title was not found or its link expired.
 * Pure: no I/O.
 */
internal object MintFailurePolicy {
    enum class Reason { SOURCE_UNAVAILABLE, TIMED_OUT, UNREACHABLE, SIGN_IN_AGAIN, SERVER_ERROR }

    fun forException(e: MediaServerException): Reason = when (e) {
        is MediaServerException.Unreachable -> if (e.timedOut) Reason.TIMED_OUT else Reason.UNREACHABLE
        is MediaServerException.Http -> if (e.isUnauthorized) Reason.SIGN_IN_AGAIN else Reason.SERVER_ERROR
        is MediaServerException.Malformed, is MediaServerException.CertificateUntrusted -> Reason.SERVER_ERROR
    }

    /** The server answered but offered nothing to play (no source, or a source it could not open). */
    val noPlayableSource: Reason = Reason.SOURCE_UNAVAILABLE

    /**
     * Some servers answer a title with no resolvable file with a playable
     * 10-hour CARD video at this path ("No streams found") rather than an error: it is a
     * message, not a stream, so the client reports it as unavailable instead of playing it.
     */
    fun isServerPlaceholder(path: String?): Boolean = path?.trim()?.trimEnd('/')?.equals("/videos/no-streams", ignoreCase = true) == true
}

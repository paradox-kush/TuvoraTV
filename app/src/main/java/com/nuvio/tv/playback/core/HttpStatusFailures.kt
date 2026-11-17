package com.nuvio.tv.playback.core

/**
 * One meaning for a provider's HTTP status, whichever engine saw it. Media3 reads it from the
 * response; libmpv only reports it in an FFmpeg log line (B189). Both must agree, or the same 403
 * is fatal on one engine and handed off to the other on the next.
 */
object HttpStatusFailures {
    fun forStatus(
        status: Int,
        phase: FailurePhase,
        provenance: HttpStatusProvenance = HttpStatusProvenance.CONFIRMED,
    ): PlaybackFailure = when (status) {
        // T10 (W2 device pass): a 404 read "could not be reached over the network" and
        // ran the live reconnect loop. The stream is not there: say so, at once.
        404 -> PlaybackFailure(
            FailureCode.SOURCE_NOT_FOUND,
            FailureDomain.NETWORK,
            phase,
            Retryability.FATAL,
            httpStatus = 404,
            statusProvenance = provenance,
        )
        // 410 Gone: an expired provider link; the session re-mints once where it can.
        410 -> PlaybackFailure(
            FailureCode.SOURCE_NOT_FOUND,
            FailureDomain.NETWORK,
            phase,
            Retryability.RETRYABLE_WITH_FRESH_REQUEST,
            httpStatus = 410,
            statusProvenance = provenance,
        )
        401, 403 -> PlaybackFailure(
            FailureCode.AUTHORIZATION_REJECTED,
            FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
            phase,
            Retryability.FATAL,
            httpStatus = status,
            statusProvenance = provenance,
        )
        // 429, and the IPTV panels' 458 / 509 ("max connections reached"): the account's slots are
        // full. Reconnecting only hammers the provider, so stop and say why.
        429, 458, 509 -> PlaybackFailure(
            FailureCode.PROVIDER_CONNECTION_LIMIT,
            FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
            phase,
            Retryability.FATAL,
            httpStatus = status,
            statusProvenance = provenance,
        )
        in 500..599 -> PlaybackFailure(
            FailureCode.NETWORK_UNREACHABLE,
            FailureDomain.NETWORK,
            phase,
            Retryability.RETRYABLE_IN_PLACE,
            httpStatus = status,
            statusProvenance = provenance,
        )
        else -> PlaybackFailure(
            FailureCode.NETWORK_UNREACHABLE,
            FailureDomain.NETWORK,
            phase,
            Retryability.RETRYABLE_WITH_FRESH_REQUEST,
            httpStatus = status,
            statusProvenance = provenance,
        )
    }
}

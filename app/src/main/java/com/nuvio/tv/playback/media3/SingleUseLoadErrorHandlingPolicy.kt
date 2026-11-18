package com.nuvio.tv.playback.media3

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy

/**
 * The Media3 load-error policy for a request's retry intent. Never null today: a re-openable link
 * keeps Media3's default recovery except for provider refusals ([ProviderRefusalLoadErrorHandlingPolicy]).
 */
internal fun media3LoadErrorPolicyFor(policy: TransientLoadRetryPolicy): LoadErrorHandlingPolicy? =
    if (policy == TransientLoadRetryPolicy.SESSION_ONLY) SingleUseLoadErrorHandlingPolicy else ProviderRefusalLoadErrorHandlingPolicy

/**
 * HTTP answers the loader must not re-request: a connection limit (429, and the IPTV panels' 458 / 509)
 * or a firewall refusal (456). The provider answered deliberately; asking again only spends another of
 * the account's connection attempts. Pure so the decision tests without Media3.
 */
internal object ProviderRefusalRetryPolicy {
    private val NO_LOADER_RETRY = setOf(429, 456, 458, 509)

    fun loaderMayRetry(httpStatus: Int): Boolean = httpStatus !in NO_LOADER_RETRY
}

/**
 * Media3's default recovery (5xx / network backoff and retry counts unchanged), except that a provider
 * refusal ([ProviderRefusalRetryPolicy]) fails the load at once with [C.TIME_UNSET] — the minimum retry
 * count alone would not stop the backoff (see [SingleUseLoadErrorHandlingPolicy]). The session then
 * classifies it (HttpStatusFailures: 429/458/509 terminal, 456 reconnect / hand-off).
 */
internal object ProviderRefusalLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }
            .take(8)
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.responseCode
        if (status != null && !ProviderRefusalRetryPolicy.loaderMayRetry(status)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

/**
 * A single-use link (every Stalker live link) must not be re-requested after a rejection: each
 * request can spend the token or a connection slot. `DefaultLoadErrorHandlingPolicy(0)` alone is not
 * enough — the minimum retry count only sets when an error becomes fatal, while
 * [getRetryDelayMsFor] still schedules retries on a 0/1/2/3 s backoff (media3 1.11, incl. the HLS
 * playlist tracker). Returning [C.TIME_UNSET] fails the load at once, so [PlaybackSession]'s bounded
 * re-mint takes over. Same rule as Mobile's `SingleUseLoadErrorHandlingPolicy`.
 */
internal object SingleUseLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
}

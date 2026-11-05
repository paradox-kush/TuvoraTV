package com.nuvio.tv.playback.media3

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy

/** The Media3 load-error policy for a request's retry intent; null keeps Media3's default. */
internal fun media3LoadErrorPolicyFor(policy: TransientLoadRetryPolicy): LoadErrorHandlingPolicy? =
    if (policy == TransientLoadRetryPolicy.SESSION_ONLY) SingleUseLoadErrorHandlingPolicy else null

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

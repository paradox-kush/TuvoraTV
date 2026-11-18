package com.nuvio.tv.playback.ui

import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PlaybackFailure

/** Why a live channel is reconnecting, in the viewer's terms. A closed set: never a host, URL or message. */
enum class LiveReconnectReason {
    /** 456: an anti-bot edge turned the request away. */
    PROVIDER_FIREWALL,
    /** 429 / 458 / 509: the account's connection slots are full. */
    CONNECTION_LIMIT,
    /** 404 / 410: the stream is not at that address. */
    STREAM_NOT_FOUND,
    /** 5xx: the provider's server answered with an error. */
    PROVIDER_SERVER_ERROR,
    /** 401 / 403 and other 4xx: the provider answered and refused. */
    PROVIDER_REFUSED,
    /** No answer at all: unreachable or timed out. */
    PROVIDER_UNREACHABLE,
    /** An end-file, a stall, or anything without a sharper cause. */
    STREAM_STOPPED,
}

/** What the live status line shows while reconnecting; attempt/budget are null until the loop's first attempt. */
data class LiveReconnectStatus(
    val attempt: Int?,
    val maxAttempts: Int?,
    val reason: LiveReconnectReason,
)

/** Pure: the failure that started (or last failed) a reconnect → the reason the viewer reads. */
object LiveReconnectReasonPolicy {
    fun reason(cause: PlaybackFailure?): LiveReconnectReason {
        if (cause == null) return LiveReconnectReason.STREAM_STOPPED
        cause.httpStatus?.let { status ->
            return when (status) {
                456 -> LiveReconnectReason.PROVIDER_FIREWALL
                429, 458, 509 -> LiveReconnectReason.CONNECTION_LIMIT
                404, 410 -> LiveReconnectReason.STREAM_NOT_FOUND
                in 500..599 -> LiveReconnectReason.PROVIDER_SERVER_ERROR
                in 400..499 -> LiveReconnectReason.PROVIDER_REFUSED
                else -> LiveReconnectReason.STREAM_STOPPED
            }
        }
        return when (cause.code) {
            FailureCode.PROVIDER_CONNECTION_LIMIT -> LiveReconnectReason.CONNECTION_LIMIT
            FailureCode.SOURCE_NOT_FOUND -> LiveReconnectReason.STREAM_NOT_FOUND
            FailureCode.AUTHORIZATION_REJECTED -> LiveReconnectReason.PROVIDER_REFUSED
            FailureCode.NETWORK_UNREACHABLE,
            FailureCode.NETWORK_TIMEOUT,
            -> LiveReconnectReason.PROVIDER_UNREACHABLE
            else -> LiveReconnectReason.STREAM_STOPPED
        }
    }
}

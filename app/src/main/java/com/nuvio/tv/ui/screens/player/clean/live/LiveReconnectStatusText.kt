package com.nuvio.tv.ui.screens.player.clean.live

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.playback.ui.LiveReconnectReason
import com.nuvio.tv.playback.ui.LiveReconnectStatus

/** The words for a live reconnect: "Reconnecting… (attempt 2 of 3) · Provider server error". Fixed copy only. */
internal object LiveReconnectStatusTextPolicy {
    @StringRes
    fun reasonRes(reason: LiveReconnectReason): Int = when (reason) {
        LiveReconnectReason.PROVIDER_FIREWALL -> R.string.live_reconnect_reason_firewall
        LiveReconnectReason.CONNECTION_LIMIT -> R.string.live_reconnect_reason_connection_limit
        LiveReconnectReason.STREAM_NOT_FOUND -> R.string.live_reconnect_reason_not_found
        LiveReconnectReason.PROVIDER_SERVER_ERROR -> R.string.live_reconnect_reason_server_error
        LiveReconnectReason.PROVIDER_REFUSED -> R.string.live_reconnect_reason_refused
        LiveReconnectReason.PROVIDER_UNREACHABLE -> R.string.live_reconnect_reason_unreachable
        LiveReconnectReason.STREAM_STOPPED -> R.string.live_reconnect_reason_stopped
    }

    /** The attempt counter is shown only once the loop has started one and the budget is known. */
    fun showsAttempt(status: LiveReconnectStatus): Boolean =
        status.attempt != null && status.maxAttempts != null && status.attempt in 1..status.maxAttempts
}

@Composable
internal fun liveReconnectStatusText(status: LiveReconnectStatus?): String? {
    status ?: return null
    val reason = stringResource(LiveReconnectStatusTextPolicy.reasonRes(status.reason))
    return if (LiveReconnectStatusTextPolicy.showsAttempt(status)) {
        stringResource(R.string.live_reconnect_status_attempt, status.attempt!!, status.maxAttempts!!, reason)
    } else {
        stringResource(R.string.live_reconnect_status, reason)
    }
}

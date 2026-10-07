package com.nuvio.tv.core.mediaserver.source

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.core.mediaserver.policy.MintFailurePolicy

/**
 * What the viewer hears when a media-server stream could not be started. Wording is deliberately about "the server" and
 * "this title" - never about where the server gets its files. Every pick site used to fall silently back to the list.
 */
internal object MediaServerMintNotices {
    fun messageRes(reason: MintFailurePolicy.Reason): Int = when (reason) {
        MintFailurePolicy.Reason.SOURCE_UNAVAILABLE -> R.string.ms_play_unavailable
        MintFailurePolicy.Reason.TIMED_OUT -> R.string.ms_play_timed_out
        MintFailurePolicy.Reason.UNREACHABLE -> R.string.ms_play_unreachable
        MintFailurePolicy.Reason.SIGN_IN_AGAIN -> R.string.ms_play_sign_in_again
        MintFailurePolicy.Reason.SERVER_ERROR -> R.string.ms_play_server_error
    }

    fun show(context: Context, reason: MintFailurePolicy.Reason) {
        // minted off the main thread: a Toast must be shown from a Looper thread
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, messageRes(reason), Toast.LENGTH_LONG).show() }
    }
}

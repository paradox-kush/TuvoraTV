package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.RecordingProblem
import java.net.SocketTimeoutException

/** What went wrong and whether the device was simply offline. Pure, so every branch tests without a network. */
internal data class SaveFailureKind(val problem: RecordingProblem, val offline: Boolean)

internal object RecordingSaveClassifier {
    /** HTTP status of a refused request: 4xx the provider said no (PROVIDER_REFUSED); 5xx is its trouble, tried again like a network fault. */
    fun forHttp(code: Int, online: Boolean): SaveFailureKind = when {
        !online -> SaveFailureKind(RecordingProblem.NETWORK, offline = true)
        code in 400..499 -> SaveFailureKind(RecordingProblem.PROVIDER_REFUSED, offline = false)
        else -> SaveFailureKind(RecordingProblem.NETWORK, offline = false)
    }

    /**
     * An I/O failure mid-request. [stalled]: our own watchdog saw no bytes for the stall limit.
     * No network wins over everything (it is not the programme's fault and does not use an attempt).
     */
    fun forIo(error: Throwable?, stalled: Boolean, online: Boolean): SaveFailureKind = when {
        !online -> SaveFailureKind(RecordingProblem.NETWORK, offline = true)
        stalled || error is SocketTimeoutException -> SaveFailureKind(RecordingProblem.STALLED, offline = false)
        else -> SaveFailureKind(RecordingProblem.NETWORK, offline = false)
    }
}

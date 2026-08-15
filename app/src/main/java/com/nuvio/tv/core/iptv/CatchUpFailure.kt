package com.nuvio.tv.core.iptv

import androidx.media3.common.PlaybackException

/**
 * Which playback failures may advance the catch-up dialect ladder, and the Stalker answers that
 * deserve their own words.
 */
object CatchUpFailure {

    /**
     * The walk exists because panels disagree about the URL SHAPE, so only a failure that could BE
     * a wrong shape may advance it. A decode failure means the URL reached a stream — another shape
     * fetches the same broken recording and spends another connection on a line that is often
     * capped at one. StreamVault walks on network/source/buffer-timeout only; this is that list,
     * kept as a whitelist so a code we have never classified stops the walk instead of churning it.
     */
    fun kindFor(errorCode: Int): CatchUpDialectWalk.FailureKind = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> CatchUpDialectWalk.FailureKind.TRANSPORT

        else -> CatchUpDialectWalk.FailureKind.DECODE
    }

    /** The Stalker `create_link` answers worth naming to the viewer. */
    enum class StalkerLinkError { NONE, SESSION_LIMIT, LINK_FAULT }

    /**
     * Reads a raw Stalker response for the two documented failure shapes.
     *
     * `{"js":{"error":"limit"}}` is the account's session cap, which is a completely different
     * problem from "this recording is gone" — it is fixed by closing the other device, and telling
     * the viewer so is the difference between a support thread and a shrug. `link_fault` is the
     * portal admitting it could not build a URL at all.
     *
     * Matched on the FIELD, not on the word: a channel called "No Limits TV" must not report a
     * session cap. Both quoting styles are accepted because the middleware source writes single
     * quotes while the wire format uses double.
     */
    fun stalkerLinkErrorOf(raw: String?): StalkerLinkError {
        if (raw.isNullOrBlank()) return StalkerLinkError.NONE
        if (ERROR_LIMIT.containsMatchIn(raw)) return StalkerLinkError.SESSION_LIMIT
        if (LINK_FAULT.containsMatchIn(raw)) return StalkerLinkError.LINK_FAULT
        return StalkerLinkError.NONE
    }

    private val ERROR_LIMIT = Regex("""["']?error["']?\s*:\s*["']limit["']""", RegexOption.IGNORE_CASE)
    private val LINK_FAULT = Regex("""["']?(cmd|error)["']?\s*:\s*["']link_fault["']""", RegexOption.IGNORE_CASE)
}

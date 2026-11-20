package com.nuvio.tv.playback.mpv

/**
 * What FFmpeg said while the current file was loading. Never holds the log text itself: provider
 * hosts can appear in it, and only the classification leaves this package.
 */
internal data class MpvLoadEvidence(
    val httpStatus: Int? = null,
    val transport: MpvTransportFault? = null,
) {
    companion object {
        val NONE = MpvLoadEvidence()
    }
}

internal enum class MpvTransportFault { TIMEOUT, TLS, UNREACHABLE }

/**
 * B189: libmpv's end-file `file_error` is `mpv_error_string()` — "loading failed", "unrecognized file
 * format" — and never names an HTTP status (mpv `player/client.c`, `include/mpv/client.h`). FFmpeg
 * logs the real cause first: `http.c` warns "HTTP error %d %s", `tcp.c`/`network.c` error on
 * resolve/connect, the TLS backends on handshake. This keeps the last such fact of the load so the
 * end-file failure can be classified like Media3 classifies the same response.
 */
internal object MpvFailureCause {
    /**
     * The clean player's `msg-level`. libmpv-android prints every delivered line to logcat before
     * any observer runs, so only modules that never format a URL are enabled (B116): plain `ffmpeg`
     * is where URLContext protocols (http, tcp, tls) log, and their warn/error lines carry at most a
     * status, an error string or a hostname — never a path or query. `ffmpeg/demuxer` (HLS segment
     * URLs), `stream` and `cplayer` (full URLs) stay off.
     *
     * Decode health: `ad` (audio decoder), `vd` (video decoder) and `ao` (audio output) are on at warn so
     * a decoder error can be classified in a release build (MpvDecodeHealth). These modules print codec
     * and device names, never a URL; only the module and level are read, the text is never kept.
     */
    const val MSG_LEVEL = "all=no,ffmpeg=warn,ffmpeg/demuxer=no,ffmpeg/video=no,ffmpeg/audio=no," +
        "ad=warn,vd=warn,ao=warn"

    /** mpv client.h: MPV_LOG_LEVEL_WARN. Lower numbers are more severe. */
    private const val LOG_LEVEL_WARN = 30

    private val HTTP_ERROR = Regex("\\bHTTP error (\\d{3})\\b", RegexOption.IGNORE_CASE)
    private val TIMEOUT = Regex("timed out|timeout", RegexOption.IGNORE_CASE)
    private val TLS = Regex("\\btls\\b|ssl|certificate|handshake", RegexOption.IGNORE_CASE)
    private val UNREACHABLE = Regex(
        "failed to resolve hostname|connection refused|network is unreachable|no route to host|connection reset",
        RegexOption.IGNORE_CASE,
    )

    fun observe(current: MpvLoadEvidence, prefix: String, level: Int, text: String): MpvLoadEvidence {
        if (level > LOG_LEVEL_WARN) return current
        if (prefix != "ffmpeg" && !prefix.startsWith("ffmpeg/")) return current
        HTTP_ERROR.find(text)?.let { match ->
            return current.copy(httpStatus = match.groupValues[1].toInt())
        }
        val transport = when {
            TIMEOUT.containsMatchIn(text) -> MpvTransportFault.TIMEOUT
            TLS.containsMatchIn(text) -> MpvTransportFault.TLS
            UNREACHABLE.containsMatchIn(text) -> MpvTransportFault.UNREACHABLE
            else -> return current
        }
        return current.copy(transport = transport)
    }
}

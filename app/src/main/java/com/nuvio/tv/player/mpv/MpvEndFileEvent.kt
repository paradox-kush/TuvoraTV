package com.nuvio.tv.player.mpv

/** Typed libmpv END_FILE reason. The wire values are part of mpv's public command API. */
enum class MpvEndFileReason {
    EOF,
    ERROR,
    STOP,
    QUIT,
    REDIRECT,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String?): MpvEndFileReason = when (value?.lowercase()) {
            "eof" -> EOF
            "error" -> ERROR
            "stop" -> STOP
            "quit" -> QUIT
            "redirect" -> REDIRECT
            else -> UNKNOWN
        }
    }
}

/**
 * One libmpv END_FILE event. Live callers retry [MpvEndFileReason.EOF] and
 * [MpvEndFileReason.ERROR]; STOP/QUIT are expected during zapping and lifecycle teardown.
 */
data class MpvEndFileEvent(
    val reason: MpvEndFileReason,
    val fileError: String? = null,
)

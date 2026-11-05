package com.nuvio.tv.ui.screens.player

/**
 * B200: libmpv's `http-header-fields` is ONE global option that holds the stream's headers (cookies,
 * tokens) and is sent on every HTTP request mpv makes. A remote `sub-add` would leak them to the
 * subtitle host, so libmpv only ever `sub-add`s a local file; the player downloads addon subtitles
 * itself with host-scoped headers (executeSubtitleRequest) and caches them.
 */
internal object MpvExternalSubtitlePolicy {
    private val networkScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun isAllowedSubAddSource(source: String): Boolean {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("file://", ignoreCase = true)) return true
        return !networkScheme.containsMatchIn(trimmed)
    }
}

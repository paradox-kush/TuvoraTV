package com.nuvio.tv.ui.screens.iptv.recordings

/** Stub (red). */
internal object RecordingsFocusPolicy {
    fun entry(ids: List<String>): String? = null
    fun restore(lastId: String?, ids: List<String>): String? = null
    fun afterRemoval(before: List<String>, removedId: String, after: List<String>): String? = null
}

internal object RecordingRowFormat {
    fun size(bytes: Long): String = ""
}

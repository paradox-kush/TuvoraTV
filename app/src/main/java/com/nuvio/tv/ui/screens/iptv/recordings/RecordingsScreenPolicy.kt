package com.nuvio.tv.ui.screens.iptv.recordings

import java.util.Locale

/** Which row takes the cursor on the Recordings page. Pure, over row ids in display order. */
internal object RecordingsFocusPolicy {

    /** The first row on entry, or null when the page has no rows. */
    fun entry(ids: List<String>): String? = ids.firstOrNull()

    /** The remembered row when it is still on the page, else the first. */
    fun restore(lastId: String?, ids: List<String>): String? = lastId?.takeIf { it in ids } ?: ids.firstOrNull()

    /**
     * Where the cursor goes when [removedId] leaves the page (deleted, cancelled, or moved to another
     * section by Retry): the row that followed it, else the one before, else the first, else null.
     */
    fun afterRemoval(before: List<String>, removedId: String, after: List<String>): String? {
        val i = before.indexOf(removedId)
        if (i < 0) return after.firstOrNull()
        val survivors = after.toHashSet()
        for (n in i + 1 until before.size) if (before[n] in survivors) return before[n]
        for (n in i - 1 downTo 0) if (before[n] in survivors) return before[n]
        return after.firstOrNull()
    }
}

internal object RecordingRowFormat {
    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024

    /** "" for nothing yet, "<1 MB", "480 MB", "1.5 GB". */
    fun size(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < MB -> "<1 MB"
        bytes < GB -> String.format(Locale.US, "%d MB", bytes / MB)
        else -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / GB)
    }
}

/**
 * When the "Allow exact times" offer is shown on the Recordings page. Pure, and a function of live
 * state only: the confirmation sheet's once-only offer being spent or dismissed never hides it, so
 * closing that sheet without choosing loses nothing.
 */
internal object ExactAlarmOfferPolicy {

    fun showOnRecordingsScreen(exactAllowed: Boolean, scheduledCount: Int, settingsAvailable: Boolean): Boolean =
        !exactAllowed && scheduledCount > 0 && settingsAvailable
}

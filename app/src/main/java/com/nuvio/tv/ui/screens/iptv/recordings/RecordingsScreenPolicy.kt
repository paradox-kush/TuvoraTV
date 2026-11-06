package com.nuvio.tv.ui.screens.iptv.recordings

import com.nuvio.tv.core.recordings.api.RecordingRow
import com.nuvio.tv.core.recordings.api.RecordingSection
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.RecordingsPageModel
import com.nuvio.tv.core.recordings.api.RecordingsPagePolicy
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
    /** First row of the first non-empty section in display order, else null. */
    fun entryFor(page: RecordingsPageModel): String? = null

    /** What a vertical key does on [id]: let focus move, swallow it (a fence), or go up to the banner. */
    enum class Vertical { MOVE, STAY, TO_BANNER }

    fun vertical(ids: List<String>, id: String, down: Boolean, bannerShown: Boolean): Vertical = Vertical.MOVE

    /**
     * Where to put the cursor when it is on none of the rows (the focused row was recomposed away when
     * its status moved it to another section), or null when nothing needs doing.
     */
    fun lostFocusTarget(listHasFocus: Boolean, overlayOpen: Boolean, lastId: String?, ids: List<String>): String? = null
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

/** How a row shows its words. Pure. */
internal object RecordingRowTextPolicy {
    const val REASON_MAX_LINES = 1

    /** True when the reason sits on its own wrapping line under the row instead of the narrow status column. */
    fun reasonOnOwnLine(row: RecordingRow): Boolean = false

    /** Every row of every status takes the cursor and opens its details on OK. */
    fun focusable(status: RecordingStatus): Boolean = false
}

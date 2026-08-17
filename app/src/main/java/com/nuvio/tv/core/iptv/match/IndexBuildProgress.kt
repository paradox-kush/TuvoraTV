package com.nuvio.tv.core.iptv.match

/**
 * How far a catalog index build has got, for one account.
 *
 * A first build over a large panel runs for minutes (measured: ~17 on 468,425 items). TV showed a
 * static "Preparing catalog…" while it ran; mobile exposed the same `indexing` StateFlow but had no
 * consumer at all, so the screen just looked broken. This carries enough to say something true.
 *
 * [totalItems] is nullable on purpose, and that is the whole design. The streaming sync feeds rows
 * as the HTTP response parses, so it genuinely does not know how many are coming — a percentage
 * there would be invented. Only the bulk path, handed a complete list, can be determinate. Keeping
 * the two apart in the type means the UI cannot accidentally render a made-up bar.
 */
data class IndexBuildProgress(
    val itemsWritten: Int,
    val totalItems: Int? = null,
) {
    /** True only when a real total is known; a zero/negative total is a broken answer, not a total. */
    val isDeterminate: Boolean get() = (totalItems ?: 0) > 0

    /**
     * 0f..1f, or null when the total is unknown — the caller should show an indeterminate
     * indicator plus [itemsWritten] rather than guess. Clamped, because a panel that reports fewer
     * rows than it streams must not drive the bar past full.
     */
    val fraction: Float?
        get() {
            val total = totalItems ?: return null
            if (total <= 0) return null
            return (itemsWritten.toFloat() / total).coerceIn(0f, 1f)
        }

    /**
     * Folds a newer report in. Counts only move forward — batches complete in order but a report
     * can arrive late, and a number that visibly rewinds reads as a bug. A total, once known,
     * sticks even if a later report omits it.
     */
    fun mergeWith(other: IndexBuildProgress): IndexBuildProgress = IndexBuildProgress(
        itemsWritten = maxOf(itemsWritten, other.itemsWritten),
        totalItems = totalItems ?: other.totalItems,
    )
}

/** "124530" -> "124,530". Hand-rolled: KMP common code has no locale number formatter. */
fun groupThousands(n: Int): String {
    val digits = n.toString()
    if (digits.length <= 3) return digits
    val sb = StringBuilder(digits.length + digits.length / 3)
    val lead = digits.length % 3
    if (lead != 0) sb.append(digits, 0, lead)
    var i = lead
    while (i < digits.length) {
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(digits, i, i + 3)
        i += 3
    }
    return sb.toString()
}

/**
 * The status line for an account whose catalog index is building, or null when it is not.
 *
 * Exists because a first build runs for minutes with nothing on screen — TV showed a static string,
 * mobile showed nothing at all. Showing the running count is what makes the difference between
 * "this app is broken" and "this is working through a very large catalog".
 *
 * Deliberately count-based: no percentage is offered, because the build spans three catalog kinds
 * and the streaming sync does not know its row count up front. A bar that jumps or resets is worse
 * than an honest number.
 */
fun indexingStatusLine(isIndexing: Boolean, progress: IndexBuildProgress?): String? = when {
    !isIndexing -> null
    progress == null || progress.itemsWritten <= 0 -> "Preparing catalog for search & playback…"
    else -> "Preparing catalog… ${groupThousands(progress.itemsWritten)} items"
}

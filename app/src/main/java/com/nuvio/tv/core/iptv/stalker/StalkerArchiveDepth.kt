package com.nuvio.tv.core.iptv.stalker

/**
 * A Ministra channel's archive depth. The row says `tv_archive` (is there an archive) and
 * `tv_archive_duration` in HOURS (0 to 999); the guide and the replay window speak DAYS
 * ([com.nuvio.tv.core.iptv.XtreamChannel.catchUpDays]; Xtream's `tv_archive_duration` is already days).
 *
 * Rounds UP so the guide never stops short of what the portal keeps. A portal that flags an archive
 * but states no duration reads 0 = "did not say", the permissive value the guide already treats as
 * "travel the stored history", rather than inventing a one-day limit.
 */
internal object StalkerArchiveDepth {
    const val MAX_HOURS = 999
    private const val HOURS_PER_DAY = 24

    fun days(tvArchive: Int?, durationHours: Int?): Int {
        if ((tvArchive ?: 0) <= 0) return 0
        val hours = (durationHours ?: 0).coerceIn(0, MAX_HOURS)
        return (hours + HOURS_PER_DAY - 1) / HOURS_PER_DAY
    }
}

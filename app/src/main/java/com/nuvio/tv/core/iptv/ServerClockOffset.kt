package com.nuvio.tv.core.iptv

/**
 * The panel's UTC offset, derived from `server_info`'s clock PAIR rather than its timezone name.
 *
 * `time_now` (server-local wall clock, "YYYY-MM-DD HH:MM:SS") and `timestamp_now` (unix seconds)
 * describe the same instant, so parsing `time_now` AS IF it were UTC and subtracting
 * `timestamp_now` leaves exactly the panel's effective UTC offset — no timezone database, and it
 * keeps working when the panel's `timezone` field is junk, missing, or a lie.
 *
 * Pure arithmetic on purpose (no java.time): the same algorithm ships in the multiplatform twins,
 * where none of the JVM date machinery exists.
 */
object ServerClockOffset {

    /**
     * Offset in milliseconds (positive = the panel's wall clock runs ahead of UTC), or null when
     * [timeNow] is unparsable or [timestampNow] is not a plausible unix time.
     *
     * Rounded to the whole minute: some panels render the two fields a beat apart, replay starts
     * are minute-granular anyway, and every real zone offset is a whole number of minutes — so
     * sub-minute difference is noise, never signal.
     */
    fun offsetMs(timeNow: String?, timestampNow: Long): Long? {
        if (timeNow.isNullOrBlank() || timestampNow <= 0) return null
        val asIfUtcSeconds = parseAsUtcSeconds(timeNow.trim()) ?: return null
        val diffSeconds = asIfUtcSeconds - timestampNow
        return (diffSeconds + 30).floorDiv(60) * 60_000L
    }

    /** "YYYY-MM-DD HH:MM[:SS]" read as a UTC instant. Anything else is null, never a throw. */
    private fun parseAsUtcSeconds(value: String): Long? {
        val match = TIME_NOW.matchEntire(value) ?: return null
        val (y, mo, d, h, mi) = match.destructured
        val year = y.toIntOrNull() ?: return null
        val month = mo.toIntOrNull() ?: return null
        val day = d.toIntOrNull() ?: return null
        val hour = h.toIntOrNull() ?: return null
        val minute = mi.toIntOrNull() ?: return null
        val second = match.groupValues[6].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        if (month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
    }

    /** Civil date -> days since the unix epoch (Howard Hinnant's days_from_civil). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private val TIME_NOW = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?""")
}

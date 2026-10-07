package com.nuvio.tv.core.mediaserver.client.mediabrowser

/** Epoch milliseconds as an ISO-8601 UTC instant (`2026-10-06T12:00:00Z`) - the form `NextUpDateCutoff` takes. Pure. */
internal object IsoTime {
    fun format(epochMs: Long): String {
        val totalSeconds = epochMs.floorDiv(1000L)
        val days = totalSeconds.floorDiv(86_400L)
        val secondsOfDay = totalSeconds.mod(86_400L).toInt()
        // civil_from_days (Howard Hinnant)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = (z - era * 146_097).toInt()
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = (if (m <= 2) y + 1 else y).toInt()
        val hh = secondsOfDay / 3600
        val mm = secondsOfDay % 3600 / 60
        val ss = secondsOfDay % 60
        fun p(n: Int, w: Int = 2) = n.toString().padStart(w, '0')
        return "${p(year, 4)}-${p(m)}-${p(d)}T${p(hh)}:${p(mm)}:${p(ss)}Z"
    }

    private val pattern = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$""")

    /**
     * An ISO-8601 instant as the servers write it (`2026-10-06T20:31:09.2016699Z`, optional fraction, `Z` or a numeric
     * offset; no offset = UTC) to epoch milliseconds, or null when it is not one. Pure; the inverse of [format].
     */
    fun parse(text: String?): Long? {
        val m = pattern.matchEntire(text?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val y = g[1].toInt()
        val mo = g[2].toInt()
        val d = g[3].toInt()
        val h = g[4].toInt()
        val mi = g[5].toInt()
        val s = g[6].toInt()
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..60) return null
        val millis = m.groupValues[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        // days_from_civil (Howard Hinnant)
        val yy = if (mo <= 2) y - 1 else y
        val era = yy.floorDiv(400)
        val yoe = yy - era * 400
        val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097L + doe - 719_468L
        var epoch = ((days * 24 + h) * 60 + mi) * 60L * 1000 + s * 1000L + millis
        val offset = m.groupValues[8]
        if (offset.isNotEmpty() && offset != "Z") {
            val sign = if (offset[0] == '-') -1 else 1
            val digits = offset.drop(1).replace(":", "")
            epoch -= sign * (digits.take(2).toInt() * 60L + digits.drop(2).toInt()) * 60_000L
        }
        return epoch
    }
}

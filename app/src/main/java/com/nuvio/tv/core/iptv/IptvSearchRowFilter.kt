package com.nuvio.tv.core.iptv

/**
 * UX44: providers pad their channel lists with heading rows — "==== Sky Germany ====",
 * "##### UK SPORTS #####" — that are not playable channels, yet matched search words and surfaced
 * as hits. Pure.
 *
 * Conservative on purpose (dropping a real title is worse than showing a heading): a name is a
 * divider only when, trimmed, it is
 *  - framed by decoration on BOTH ends — a leading and a trailing run of at least two of `= - # *`
 *    ("== A ==", "==== Sky Germany ----"), or
 *  - made of nothing but decoration (and spaces), at least two marks ("==========", "#### ####").
 * So "4K | Sky Sports", "#1 Movie", "M*A*S*H", "-- Sky News", "## Sky Cinema", "= Sky =" all stay.
 *
 * TV twin of NuvioMobile's `features/iptv/IptvSearchRowFilter` (same cases in both test suites).
 */
object IptvSearchRowFilter {
    private const val DECORATION = "=-#*"
    private const val MIN_RUN = 2

    private fun Char.isDecoration() = this in DECORATION

    fun isDivider(name: String): Boolean {
        val s = name.trim()
        if (s.isEmpty()) return false
        if (s.all { it.isDecoration() || it.isWhitespace() }) return s.count { it.isDecoration() } >= MIN_RUN
        return s.takeWhile { it.isDecoration() }.length >= MIN_RUN &&
            s.takeLastWhile { it.isDecoration() }.length >= MIN_RUN
    }

    /** [items] without the divider rows, order kept. */
    fun <T> withoutDividers(items: List<T>, nameOf: (T) -> String): List<T> =
        items.filterNot { isDivider(nameOf(it)) }
}

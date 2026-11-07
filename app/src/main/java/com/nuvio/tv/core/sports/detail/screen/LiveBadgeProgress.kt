package com.nuvio.tv.core.sports.detail.screen

/**
 * The text after "LIVE" on a Sports card. The radar feed's progress is a bare minute for period sports ("9" = the 9th
 * minute OF a period), which reads as the game minute and disagrees with the detail's "3rd · 10:12", so it is dropped for
 * those sports; running-time sports (soccer, rugby, ...) keep their minute.
 */
object LiveBadgeProgress {
    private val periodSports = setOf("ice hockey", "basketball", "american football", "baseball", "tennis", "cricket")

    fun of(sport: String?, progress: String?): String? {
        val p = progress?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bareNumber = p.all { it.isDigit() }
        if (bareNumber && sport?.trim()?.lowercase() in periodSports) return null
        return p
    }
}

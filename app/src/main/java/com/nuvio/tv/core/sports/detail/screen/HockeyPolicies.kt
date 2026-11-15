package com.nuvio.tv.core.sports.detail.screen

/** One hockey play as the strength / power-play policies need it, oldest first. */
data class HockeyPlay(
    val period: Int,
    /** Seconds ELAPSED in the period (the play clock counts up); null when unreadable. */
    val elapsedSec: Int?,
    /** The play's own team, "home" | "away" | null. */
    val side: String?,
    val penaltyMinutes: Int? = null,
    val penaltyType: String? = null,
    val goal: Boolean = false,
    /** ESPN `strength.text`: "Even Strength" | "Power Play" | "Shorthanded". */
    val strength: String? = null,
)

private fun other(side: String) = if (side == "home") "away" else "home"

/**
 * Who has the power play, read from the newest play's own `strength` (direct feed value, no clock). Verified on three live NHL games
 * 2026-11-14 (core lane): "Power Play" describes the play's OWN team, "Shorthanded" the team a man down. Penalty plays carry unreliable
 * tags (a fighting major is tagged "Power Play" for one fighter at even strength) and some stoppage plays have no team: both are skipped.
 */
object HockeyStrength {
    fun powerPlaySide(plays: List<HockeyPlay>): String? {
        for (p in plays.asReversed()) {
            val st = p.strength?.lowercase() ?: continue
            if (p.penaltyType != null) continue
            when {
                "even" in st -> return null
                "power" in st -> return p.side ?: continue
                "short" in st -> return (p.side ?: continue).let(::other)
            }
        }
        return null
    }
}

/**
 * DERIVED (behind the `hockeyPowerPlayClock` switch, off by default): seconds left of the power play as of the last update, from penalty
 * plays and the game clock; a number ONLY when unambiguous. Clock direction (verified live 2026-11-14): a play's `clock.displayValue` is
 * ELAPSED time in the period, the event's `status.clock` is seconds REMAINING; overtime gives no number.
 * UNVERIFIED: the spelling of a double minor in `penaltyType`; which team a "served by" bench minor is filed under.
 */
object PowerPlayClock {
    const val REGULATION_PERIOD_SEC = 1200

    fun elapsedInPeriod(period: Int, clockRemainingSec: Int): Int? =
        if (period !in 1..3) null else (REGULATION_PERIOD_SEC - clockRemainingSec).takeIf { it in 0..REGULATION_PERIOD_SEC }

    private class Penalty(val side: String, val start: Int, val minutes: Int, val kind: String) {
        val end get() = start + minutes * 60
    }

    private fun kindOf(type: String): String? {
        val t = type.lowercase()
        return when {
            "double" in t -> "double"
            t == "minor" || t == "bench" -> "minor"
            t == "major" -> "major"
            else -> null // misconducts and the like create no power play
        }
    }

    fun secondsLeft(plays: List<HockeyPlay>, period: Int, clockRemainingSec: Int, powerPlaySide: String? = null): Int? {
        val nowIn = elapsedInPeriod(period, clockRemainingSec) ?: return null
        val now = (period - 1) * REGULATION_PERIOD_SEC + nowIn
        val pens = ArrayList<Penalty>()
        for (p in plays) {
            val type = p.penaltyType ?: continue
            val kind = kindOf(type) ?: continue
            val minutes = p.penaltyMinutes ?: return null
            val side = p.side ?: return null
            val el = p.elapsedSec ?: return null
            if (p.period !in 1..3) return null
            pens += Penalty(side, (p.period - 1) * REGULATION_PERIOD_SEC + el, minutes, kind)
        }
        // Coincidental penalties (both teams, same moment) create no power play; unequal ones are ambiguous.
        val live = ArrayList<Penalty>()
        for ((_, group) in pens.groupBy { it.start }) {
            val homes = group.filter { it.side == "home" }
            val aways = group.filter { it.side == "away" }
            if (homes.isNotEmpty() && aways.isNotEmpty()) {
                if (homes.map { it.minutes }.sorted() != aways.map { it.minutes }.sorted()) return null
            } else live += group
        }
        val active = live.filter { it.start <= now && it.end > now }
        if (active.size != 1) return null // none: nothing to count; several: 5-on-3 or 4-on-4, ambiguous
        val pen = active.single()
        if (powerPlaySide != other(pen.side)) return null // the feed's own strength wins over our arithmetic
        val goalEndsIt = plays.any {
            it.goal && it.side == other(pen.side) && it.elapsedSec != null && it.period in 1..3 &&
                (it.period - 1) * REGULATION_PERIOD_SEC + it.elapsedSec > pen.start
        }
        if (goalEndsIt && pen.kind != "major") return null // a power play goal ends a minor; a major runs on
        return (pen.end - now).coerceAtLeast(0)
    }
}

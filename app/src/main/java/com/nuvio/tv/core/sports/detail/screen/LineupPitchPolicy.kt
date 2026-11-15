package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.LineupPlayer

/** One dot on a team's half-pitch: [x] 0 = own goal line, 1 = toward the opponent; [y] 0..1 across the pitch (ascending ESPN place). */
data class PitchSpot(val player: LineupPlayer, val x: Float, val y: Float)

/**
 * Places a starting XI on a half-pitch from the formation string and ESPN's `formationPlace` (1 = keeper, then the rows of the
 * formation in order: "4-2-3-1" -> places 2-5, 6-7, 8-10, 11). Anything else - unknown or inconsistent formation, missing or
 * duplicate places, not exactly 11 starters - returns empty (TV then shows no Lineups chip).
 * UNVERIFIED: that ascending place runs the same way across the pitch for every team (assumed top to bottom).
 */
object LineupPitchPolicy {
    private const val KEEPER_X = 0.06f
    private const val FIRST_ROW_X = 0.22f
    private const val LAST_ROW_X = 0.90f

    fun positions(formation: String?, players: List<LineupPlayer>): List<PitchSpot> {
        val rows = formation?.split('-')?.map { it.trim().toIntOrNull() ?: return emptyList() }
            ?.takeIf { r -> r.isNotEmpty() && r.all { it > 0 } && r.sum() == 10 } ?: return emptyList()
        val xi = players.filter { it.starter && it.place != null }
        if (xi.size != 11 || xi.map { it.place!! }.toSet() != (1..11).toSet()) return emptyList()
        val byPlace = xi.associateBy { it.place!! }
        val out = ArrayList<PitchSpot>(11)
        out += PitchSpot(byPlace.getValue(1), KEEPER_X, 0.5f)
        var next = 2
        rows.forEachIndexed { k, n ->
            val x = if (rows.size == 1) (FIRST_ROW_X + LAST_ROW_X) / 2 else FIRST_ROW_X + (LAST_ROW_X - FIRST_ROW_X) * k / (rows.size - 1)
            for (i in 0 until n) out += PitchSpot(byPlace.getValue(next++), x, (i + 1f) / (n + 1f))
        }
        return out
    }
}

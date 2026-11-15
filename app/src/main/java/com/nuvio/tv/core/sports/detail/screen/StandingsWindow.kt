package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.StandingsGroup

/**
 * For panels that cannot scroll (TV): at most [max] rows IN TOTAL, centred on the game's teams, never pushing a game team out of
 * view. A table that already fits is returned unchanged. When space is short, groups without a game team are dropped and the
 * budget is split across the groups that have one; inside a group the rows nearest the game teams are kept, in the table's own order.
 */
object StandingsWindow {
    fun window(b: GameBlock.StandingsTable, max: Int): GameBlock.StandingsTable {
        if (max <= 0) return b.copy(groups = emptyList())
        if (b.groups.sumOf { it.rows.size } <= max) return b
        val withTeam = b.groups.filter { g -> g.rows.any { it.isGameTeam } }
        if (withTeam.isEmpty()) {
            val first = b.groups.firstOrNull() ?: return b
            return b.copy(groups = listOf(first.copy(rows = first.rows.take(max))))
        }
        val n = withTeam.size
        val groups = withTeam.mapIndexed { i, g ->
            val share = max / n + if (i < max % n) 1 else 0
            g.window(maxOf(share, g.rows.count { it.isGameTeam }))
        }
        return b.copy(groups = groups)
    }

    private fun StandingsGroup.window(budget: Int): StandingsGroup {
        if (rows.size <= budget) return this
        val keep = sortedSetOf<Int>()
        rows.forEachIndexed { i, r -> if (r.isGameTeam) keep += i }
        var radius = 1
        while (keep.size < budget && radius < rows.size) {
            for (i in rows.indices.filter { rows[it].isGameTeam }) {
                for (j in listOf(i - radius, i + radius)) {
                    if (keep.size < budget && j in rows.indices) keep += j
                }
            }
            radius++
        }
        return copy(rows = keep.map { rows[it] })
    }
}

package com.nuvio.tv.ui.screens.iptv

/**
 * Change 8 of the IPTV redesign (2026-11-15, 02 "Groups that scale"): with 100+ groups a held UP / DOWN in the group
 * list jumps a letter at a time instead of a row at a time (UX358: ~150 presses to reach a group). Only the groups
 * part jumps; the pinned rows, Search and the recent-group copies above it move row by row as usual. Pure.
 */
internal object GuideLetterJumpPolicy {
    /** The letter a group sorts under: its first letter or digit, upper-cased; '#' when it has none. */
    fun letterOf(name: String): Char =
        name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'

    /**
     * Where a held press from [current] lands: DOWN, the first group of the next letter; UP, the first group of the
     * previous letter (the top of its run, as a letter index does); else the far end of the groups part. Null when [current] is not in the groups part (the caller
     * then moves one row, as for a single press). [inGroupsPart] says which rows take part.
     */
    fun target(names: List<String>, inGroupsPart: List<Boolean>, current: Int, down: Boolean): Int? {
        if (current !in names.indices || !inGroupsPart[current]) return null
        val letter = letterOf(names[current])
        val range = if (down) (current + 1..names.lastIndex) else (current - 1 downTo 0)
        var last: Int? = null
        for (i in range) {
            if (!inGroupsPart[i]) break
            last = i
            if (letterOf(names[i]) != letter) return if (down) i else topOfRun(names, inGroupsPart, i)
        }
        return last
    }

    private fun topOfRun(names: List<String>, inGroupsPart: List<Boolean>, index: Int): Int {
        val letter = letterOf(names[index])
        var top = index
        while (top - 1 >= 0 && inGroupsPart[top - 1] && letterOf(names[top - 1]) == letter) top--
        return top
    }
}

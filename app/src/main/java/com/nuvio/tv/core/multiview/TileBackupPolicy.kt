// Ported from homelessbrian/NuvioPlusIPTV @ a86756891 (GPL-3.0), MultiviewPlaybackSession.kt (switchToBackup).
package com.nuvio.tv.core.multiview

/**
 * Picks the next backup for a failing tile. Candidates come from [findBackup], which is given the
 * keys already tried so it can skip them. The tried-set of a run of backups travels with each pick,
 * so a chain A -> B -> C never returns to A or B.
 */
internal class TileBackupPolicy {
    private val triedByChannel = HashMap<String, Set<String>>()

    /** The next untried, allowed backup for [failed], or null when there is none. Remembers the chain. */
    fun <C : MultiviewChannel> next(
        failed: C,
        findBackup: (tried: Set<String>) -> C?,
        mayPlay: (C) -> Boolean,
    ): C? {
        val result = choose(failed.key, triedByChannel[failed.key].orEmpty(), findBackup, mayPlay)
        val pick = result.backup ?: return null
        triedByChannel[pick.key] = result.tried
        return pick
    }

    fun clear() = triedByChannel.clear()

    class Choice<C>(val backup: C?, val tried: Set<String>)

    companion object {
        const val MAX_CANDIDATES = 8

        /**
         * Asks [findBackup] up to [MAX_CANDIDATES] times. A candidate already tried is skipped, one that
         * may not play is added to the tried-set, the first allowed one wins. A null answer or an
         * exception from the supplier ends the search.
         */
        fun <C : MultiviewChannel> choose(
            failedKey: String,
            priorTried: Set<String>,
            findBackup: (tried: Set<String>) -> C?,
            mayPlay: (C) -> Boolean,
        ): Choice<C> {
            val tried = HashSet(priorTried).apply { add(failedKey) }
            repeat(MAX_CANDIDATES) {
                val candidate = runCatching { findBackup(tried.toSet()) }.getOrNull()
                    ?: return Choice(null, tried)
                if (candidate.key in tried) return@repeat
                if (runCatching { mayPlay(candidate) }.getOrDefault(false)) return Choice(candidate, tried)
                tried += candidate.key
            }
            return Choice(null, tried)
        }
    }
}

package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameHeader
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.SideKey
import com.nuvio.tv.core.sports.detail.TimelineItem
import kotlin.math.roundToInt

/** One thing that really changed between two snapshots. The UI animates the net change once; it never invents in-between state. */
sealed interface Moment {
    data class ScoreChange(val side: SideKey, val to: String) : Moment
    data class WinChanceMove(val fromHome: Int, val toHome: Int) : Moment
    data class NewPlay(val item: TimelineItem) : Moment
}

object GameMomentPolicy {
    /** prev == null, a different game, or a state change into LIVE -> emptyList(): nothing animates when the screen opens. */
    fun diff(prev: GameDetail?, next: GameDetail): List<Moment> {
        if (prev == null || prev.gameId != next.gameId) return emptyList()
        if (prev.state != GameState.LIVE && next.state == GameState.LIVE) return emptyList()
        return buildList {
            addAll(scoreChanges(prev, next))
            winChance(prev, next)?.let(::add)
            newPlay(prev, next)?.let(::add)
        }
    }

    private fun scores(d: GameDetail): Pair<String?, String?> = when (val h = d.header) {
        is GameHeader.Teams -> h.home.score to h.away.score
        is GameHeader.Versus -> h.a.score to h.b.score
        is GameHeader.Event -> null to null
    }

    private fun scoreChanges(prev: GameDetail, next: GameDetail): List<Moment> {
        val (ph, pa) = scores(prev)
        val (nh, na) = scores(next)
        return buildList {
            if (ph != null && nh != null && ph != nh) add(Moment.ScoreChange(SideKey.HOME, nh))
            if (pa != null && na != null && pa != na) add(Moment.ScoreChange(SideKey.AWAY, na))
        }
    }

    private fun winChance(prev: GameDetail, next: GameDetail): Moment? {
        val a = prev.block(com.nuvio.tv.core.sports.detail.BlockType.WIN_PROBABILITY) as? GameBlock.WinProbability ?: return null
        val b = next.block(com.nuvio.tv.core.sports.detail.BlockType.WIN_PROBABILITY) as? GameBlock.WinProbability ?: return null
        val from = a.homePercent.roundToInt(); val to = b.homePercent.roundToInt()
        return if (from != to) Moment.WinChanceMove(from, to) else null
    }

    /** The newest play: first of [GameBlock.Plays] (newest first), last of the scoring [GameBlock.Timeline] (oldest first). */
    fun newest(d: GameDetail): TimelineItem? =
        d.blocks.filterIsInstance<GameBlock.Plays>().firstOrNull()?.items?.firstOrNull()
            ?: d.blocks.filterIsInstance<GameBlock.Timeline>().firstOrNull()?.items?.lastOrNull()

    private fun same(a: TimelineItem, b: TimelineItem): Boolean =
        if (a.id != null && b.id != null) a.id == b.id else a.time == b.time && a.text == b.text && a.period == b.period

    private fun newPlay(prev: GameDetail, next: GameDetail): Moment? {
        val n = newest(next) ?: return null
        val p = newest(prev)
        return if (p == null || !same(p, n)) Moment.NewPlay(n) else null
    }
}

/** The moments of ONE update. [seq] increases per batch, so a UI effect keyed on it plays each moment exactly once. */
data class MomentBatch(val seq: Int, val moments: List<Moment>) {
    companion object { val None = MomentBatch(0, emptyList()) }
}

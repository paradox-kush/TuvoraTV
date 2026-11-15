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
    // Phase B
    data class FieldMove(val fromYard: Int, val toYard: Int, val loss: Boolean, val firstDown: Boolean) : Moment
    data class Touchdown(val side: SideKey) : Moment
    data class PossessionChange(val to: SideKey) : Moment
    data class SoccerMoment(val event: com.nuvio.tv.core.sports.detail.SoccerEvent) : Moment
    // Phase C
    data class PowerPlay(val side: SideKey?, val started: Boolean) : Moment
    data class FoulCount(val side: SideKey, val to: Int) : Moment
    data class BonusReached(val side: SideKey) : Moment
    data class TimeoutUsed(val side: SideKey) : Moment
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
            touchdown(prev, next)?.let(::add)
            fieldMove(prev, next)?.let(::add)
            soccer(prev, next)?.let(::add)
            hockey(prev, next)?.let(::add)
            addAll(basketball(prev, next))
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

    private fun intScore(s: String?): Int? = s?.trim()?.toIntOrNull()

    /** A score rise of 6 or more for the side that had the ball in the previous snapshot. */
    private fun touchdown(prev: GameDetail, next: GameDetail): Moment? {
        val side = prev.blocks.filterIsInstance<GameBlock.Field>().firstOrNull()?.possession ?: return null
        val (ph, pa) = scores(prev)
        val (nh, na) = scores(next)
        val rise = if (side == SideKey.HOME) (intScore(nh) ?: return null) - (intScore(ph) ?: return null) else (intScore(na) ?: return null) - (intScore(pa) ?: return null)
        return if (rise >= 6) Moment.Touchdown(side) else null
    }

    /** One FieldMove for any gain/loss with the same team on the ball (a jump over several plays is still one); a flip is a PossessionChange. */
    private fun fieldMove(prev: GameDetail, next: GameDetail): Moment? {
        val a = prev.blocks.filterIsInstance<GameBlock.Field>().firstOrNull() ?: return null
        val b = next.blocks.filterIsInstance<GameBlock.Field>().firstOrNull() ?: return null
        val pa = a.possession ?: return null
        val pb = b.possession ?: return null
        if (pa != pb) return Moment.PossessionChange(pb)
        if (a.yardLine == b.yardLine) return null
        val loss = if (pb == SideKey.HOME) b.yardLine < a.yardLine else b.yardLine > a.yardLine
        return Moment.FieldMove(a.yardLine, b.yardLine, loss = loss, firstDown = b.down == 1 && a.down != 1)
    }

    /** A different real last event than before (compared by kind, minute and text); the event disappearing animates nothing. */
    private fun soccer(prev: GameDetail, next: GameDetail): Moment? {
        val n = next.blocks.filterIsInstance<GameBlock.SoccerStrip>().firstOrNull()?.lastEvent ?: return null
        val p = prev.blocks.filterIsInstance<GameBlock.SoccerStrip>().firstOrNull()?.lastEvent
        return if (p == null || p.kind != n.kind || p.minute != n.minute || p.text != n.text) Moment.SoccerMoment(n) else null
    }

    /** The power play side changing: a side gained it (started) or it went back to even strength. The bar stepping is not a moment. */
    private fun hockey(prev: GameDetail, next: GameDetail): Moment? {
        val a = prev.blocks.filterIsInstance<GameBlock.HockeyStrip>().firstOrNull() ?: return null
        val b = next.blocks.filterIsInstance<GameBlock.HockeyStrip>().firstOrNull() ?: return null
        if (a.powerPlaySide == b.powerPlaySide) return null
        return if (b.powerPlaySide != null) Moment.PowerPlay(b.powerPlaySide, started = true) else Moment.PowerPlay(null, started = false)
    }

    /** New team fouls, the bonus switching on, a timeout used. A new period resetting the fouls is not a moment. */
    private fun basketball(prev: GameDetail, next: GameDetail): List<Moment> {
        val a = prev.blocks.filterIsInstance<GameBlock.BasketballStrip>().firstOrNull() ?: return emptyList()
        val b = next.blocks.filterIsInstance<GameBlock.BasketballStrip>().firstOrNull() ?: return emptyList()
        return buildList {
            for (key in listOf(SideKey.HOME, SideKey.AWAY)) {
                val side = if (key == SideKey.HOME) "home" else "away"
                val pf = if (key == SideKey.HOME) a.homeFouls else a.awayFouls
                val nf = if (key == SideKey.HOME) b.homeFouls else b.awayFouls
                if (pf != null && nf != null && nf > pf) add(Moment.FoulCount(key, nf))
                if (!BasketballStripPolicy.inBonus(side, a) && BasketballStripPolicy.inBonus(side, b)) add(Moment.BonusReached(key))
                val pt = if (key == SideKey.HOME) a.homeTimeouts else a.awayTimeouts
                val nt = if (key == SideKey.HOME) b.homeTimeouts else b.awayTimeouts
                if (pt != null && nt != null && nt < pt) add(Moment.TimeoutUsed(key))
            }
        }
    }

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

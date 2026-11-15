package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.ScoringRun
import com.nuvio.tv.core.sports.detail.SideKey

/** Which side is in the bonus. ONE place, so the reading can be flipped without touching anything else. */
object BasketballStripPolicy {
    /**
     * A competitor whose OWN `bonusState` is anything but NONE is the team that is in the bonus (the field sits on the team that benefits,
     * i.e. the opponent of the team that ran out of fouls). Supported by the NBA capture of 2026-11-14 (MIA 2 fouls "DOUBLE", DET 4 fouls
     * "NONE"). UNVERIFIED against a live game's on-air bonus indicator. The label is always "<Team> in the bonus": the NBA has no double
     * bonus, so ESPN's raw value is never printed. [side] is "home" | "away".
     */
    fun inBonus(side: String, b: GameBlock.BasketballStrip): Boolean {
        val v = (if (side == "home") b.homeBonus else b.awayBonus) ?: return false
        return v.isNotBlank() && !v.equals("NONE", ignoreCase = true)
    }
}

/** One play as the run policy needs it, oldest first. [side] "home" | "away" | null. */
data class RunPlay(val side: String?, val points: Int, val scoring: Boolean)

/**
 * DERIVED (behind the `basketballRun` switch, off by default): the points the last-scoring team has scored since the opponent last scored,
 * shown only from 8 points. Arithmetic on the feed's own scores, but a label of ours.
 */
object BasketballRunPolicy {
    const val MIN_RUN_POINTS = 8

    fun run(plays: List<RunPlay>): ScoringRun? {
        var side: String? = null
        var points = 0
        for (p in plays.asReversed()) {
            if (!p.scoring || p.points <= 0) continue
            val s = p.side ?: return null // a scoring play we cannot attribute makes the run ambiguous
            if (side == null) { side = s; points = p.points } else if (s == side) points += p.points else break
        }
        return side?.takeIf { points >= MIN_RUN_POINTS }?.let { ScoringRun(if (it == "home") SideKey.HOME else SideKey.AWAY, points) }
    }
}

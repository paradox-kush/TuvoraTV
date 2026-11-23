package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.radar.DelayedValueBuffer
import com.nuvio.tv.core.radar.ScoreSpoilerPolicy
import com.nuvio.tv.core.radar.ScoreSpoilerSettings
import com.nuvio.tv.core.sports.detail.BlockType
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameHeader
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.Side

/** What the game screen renders under the spoiler settings. Hidden/Pending carry an already scrubbed model. */
sealed interface GameRender {
    val detail: GameDetail
    data class Shown(override val detail: GameDetail) : GameRender
    /** "Hide scores" is on. */
    data class Hidden(override val detail: GameDetail) : GameRender
    /** Delay is on and no snapshot is old enough yet: scoreboard shows "–". */
    data class Pending(override val detail: GameDetail) : GameRender
}

/** Removes everything on the game page that tells the result or the score. Pure. */
object GameSpoilerFilter {
    private val spoilerBlocks = setOf(
        BlockType.SITUATION, BlockType.LINESCORE, BlockType.STATS, BlockType.LEADERS, BlockType.TIMELINE,
        BlockType.PLAYS, BlockType.WIN_PROBABILITY, BlockType.FIELD, BlockType.SOCCER_STRIP,
        BlockType.BASKETBALL_STRIP, BlockType.HOCKEY_STRIP,
    )

    fun scrub(detail: GameDetail): GameDetail {
        if (detail.state == GameState.UPCOMING || detail.state == GameState.POSTPONED) return detail
        val header = when (val h = detail.header) {
            is GameHeader.Teams -> GameHeader.Teams(h.home.noScore(), h.away.noScore())
            is GameHeader.Versus -> GameHeader.Versus(h.a.noScore(), h.b.noScore())
            is GameHeader.Event -> h
        }
        return detail.copy(header = header, blocks = detail.blocks.filterNot { it.type in spoilerBlocks })
    }

    private fun Side.noScore(): Side = copy(score = null)
}

object GameScorePolicy {
    fun render(
        settings: ScoreSpoilerSettings,
        gameId: String,
        buffer: DelayedValueBuffer<String, GameDetail>,
        latest: GameDetail?,
        finishedAtFirstSight: Boolean,
        nowMs: Long,
    ): GameRender? {
        if (latest == null) return null
        if (ScoreSpoilerPolicy.hidesDetails(settings)) return GameRender.Hidden(GameSpoilerFilter.scrub(latest))
        val delayMs = settings.delayMs
        if (delayMs <= 0L || finishedAtFirstSight) return GameRender.Shown(latest)
        val old = buffer.valueAt(gameId, nowMs, delayMs) ?: return GameRender.Pending(GameSpoilerFilter.scrub(latest))
        return GameRender.Shown(old)
    }
}

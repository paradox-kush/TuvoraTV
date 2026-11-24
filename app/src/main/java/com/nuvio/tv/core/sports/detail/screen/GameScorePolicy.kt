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
    // ALLOW-list (twin of Mobile): a started game keeps only what cannot tell the result. A block type added later
    // is hidden until someone decides it is safe.
    private val safeBlocks = setOf(
        BlockType.WATCH, BlockType.STANDINGS, BlockType.STANDINGS_TABLE, BlockType.LINEUP, BlockType.FORM, BlockType.NEXT,
    )

    fun scrub(detail: GameDetail): GameDetail {
        if (detail.state != GameState.LIVE && detail.state != GameState.FINAL) return detail
        val header = when (val h = detail.header) {
            is GameHeader.Teams -> GameHeader.Teams(h.home.noScore(), h.away.noScore())
            is GameHeader.Versus -> GameHeader.Versus(h.a.noScore(), h.b.noScore())
            is GameHeader.Event -> h
        }
        return detail.copy(header = header, blocks = detail.blocks.filter { it.type in safeBlocks })
    }

    // The win-loss record moves with every result, so it is a spoiler as well.
    private fun Side.noScore(): Side = copy(score = null, record = null)
}

/** The game page's per-game reveal control (the global setting is only ever changed from the hub). */
enum class GameRevealChip {
    NONE, SHOW_THIS, HIDE_AGAIN;

    companion object {
        /** [globallyHidden] = the profile setting hides scores; [revealed] = this game was revealed for the session. */
        fun of(globallyHidden: Boolean, revealed: Boolean, state: GameState): GameRevealChip = when {
            !globallyHidden -> NONE
            state != GameState.LIVE && state != GameState.FINAL -> NONE
            revealed -> HIDE_AGAIN
            else -> SHOW_THIS
        }
    }
}

/** Whether the moment baseline resets (a mode flip or a delay change is not a score change). */
object GameRenderBaseline {
    fun <M> shouldReset(prevMode: M?, mode: M, prevDelayMs: Long?, delayMs: Long): Boolean =
        mode != prevMode || (prevDelayMs != null && prevDelayMs != delayMs)
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

/**
 * True when "Hide scores" redaction left a started game with nothing to show beside the scoreboard (no section chip,
 * no leaders / stats / plays card): the screen then says so and offers the per-game reveal instead of a blank pane.
 */
fun GameDetailTvModel.hiddenOverviewEmpty(hidden: Boolean): Boolean =
    hidden && (state == GameState.LIVE || state == GameState.FINAL) &&
        sections.isEmpty() && leaders.isEmpty() && stats.isEmpty() && latestPlays.isEmpty() && playRows.isEmpty() &&
        leadersAll.isEmpty() && statLines.isEmpty() && standings == null

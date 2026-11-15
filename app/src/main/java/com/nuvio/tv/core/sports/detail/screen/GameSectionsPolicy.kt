package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail

/** The tabs (phone) / section chips (TV) of the game screen. TV shows every section except OVERVIEW as a chip. */
enum class GameSection { OVERVIEW, PLAYS, STATS, LEADERS, LINEUPS, STANDINGS }

/**
 * Which sections a game has and which blocks fill each. A section exists only when a non-empty block for it is present, so a
 * missing feed hides its chip instead of showing an empty one. OVERVIEW is always first.
 *
 *  PLAYS <- [GameBlock.Plays], else the scoring [GameBlock.Timeline] · STATS <- Stats · LEADERS <- Leaders · LINEUPS <- Lineup ·
 *  STANDINGS <- [GameBlock.StandingsTable], else the soccer-shaped [GameBlock.Standings] · everything else is OVERVIEW.
 */
object GameSectionsPolicy {
    fun sections(d: GameDetail): List<GameSection> =
        listOf(GameSection.OVERVIEW) +
            listOf(GameSection.PLAYS, GameSection.STATS, GameSection.LEADERS, GameSection.LINEUPS, GameSection.STANDINGS)
                .filter { blocksFor(it, d).isNotEmpty() }

    fun blocksFor(s: GameSection, d: GameDetail): List<GameBlock> {
        val live = d.blocks.filterNot { GameDetailUiPolicy.isEmpty(it) }
        return when (s) {
            GameSection.PLAYS -> live.filterIsInstance<GameBlock.Plays>().take(1).ifEmpty { live.filterIsInstance<GameBlock.Timeline>().take(1) }
            GameSection.STATS -> live.filterIsInstance<GameBlock.Stats>().take(1)
            GameSection.LEADERS -> live.filterIsInstance<GameBlock.Leaders>().take(1)
            GameSection.LINEUPS -> live.filterIsInstance<GameBlock.Lineup>().take(1)
            GameSection.STANDINGS -> live.filterIsInstance<GameBlock.StandingsTable>().take(1).ifEmpty { live.filterIsInstance<GameBlock.Standings>().take(1) }
            GameSection.OVERVIEW -> live.filterNot {
                it is GameBlock.Plays || it is GameBlock.Timeline || it is GameBlock.Stats || it is GameBlock.Leaders ||
                    it is GameBlock.Lineup || it is GameBlock.StandingsTable || it is GameBlock.Standings
            }
        }
    }
}

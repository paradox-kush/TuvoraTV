package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.LeaderItem
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.SideKey
import com.nuvio.tv.core.sports.detail.SourceId
import com.nuvio.tv.core.sports.detail.StatRow
import com.nuvio.tv.core.sports.detail.TimelineItem
import com.nuvio.tv.core.sports.detail.TimelineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelFitTest {
    @Test fun `without a preview the caps are the spec numbers at the nominal height`() {
        val h = PanelFit.panelHeightDp(hasPreview = false)
        assertEquals("plays", 9, PanelFit.rows(GameSection.PLAYS, h, false))
        assertEquals("stats", 6, PanelFit.rows(GameSection.STATS, h, false))
        assertEquals("leaders", 4, PanelFit.rows(GameSection.LEADERS, h, false))
        assertEquals("standings", 7, PanelFit.rows(GameSection.STANDINGS, h, false))
    }

    @Test fun `with a preview the caps are the smaller spec numbers`() {
        val h = PanelFit.panelHeightDp(hasPreview = true)
        assertEquals("plays", 4, PanelFit.rows(GameSection.PLAYS, h, true))
        assertEquals("stats", 3, PanelFit.rows(GameSection.STATS, h, true))
        assertEquals("leaders", 2, PanelFit.rows(GameSection.LEADERS, h, true))
        assertEquals("standings", 3, PanelFit.rows(GameSection.STANDINGS, h, true))
    }

    @Test fun `a short panel drops rows instead of clipping one`() {
        val tight = 45 + 3 * PanelFit.ROW_PLAYS_DP + 5
        assertEquals("three whole rows fit", 3, PanelFit.rows(GameSection.PLAYS, tight, false))
        assertEquals("nothing fits is zero, never negative", 0, PanelFit.rows(GameSection.PLAYS, 10, false))
    }

    @Test fun `never more rows than exist`() =
        assertEquals("two exist", 2, PanelFit.rows(GameSection.PLAYS, 420, false, available = 2))

    @Test fun `the extra compartment (by period under stats, form under leaders) only fits without a preview and with room`() {
        val h = PanelFit.panelHeightDp(false)
        assertTrue("stats + by-period", PanelFit.fit(GameSection.STATS, h, false, hasExtra = true).extra)
        assertTrue("leaders + form", PanelFit.fit(GameSection.LEADERS, h, false, hasExtra = true).extra)
        assertFalse("preview shown", PanelFit.fit(GameSection.STATS, PanelFit.panelHeightDp(true), true, hasExtra = true).extra)
        assertFalse("nothing to show", PanelFit.fit(GameSection.STATS, h, false, hasExtra = false).extra)
        assertFalse("no room", PanelFit.fit(GameSection.STATS, 45 + 6 * PanelFit.ROW_STATS_DP + 10, false, hasExtra = true).extra)
    }

    @Test fun `every capped panel fits inside the right column`() {
        for (preview in listOf(false, true)) {
            val h = PanelFit.panelHeightDp(preview)
            for (s in listOf(GameSection.PLAYS, GameSection.STATS, GameSection.LEADERS, GameSection.STANDINGS)) {
                val n = PanelFit.rows(s, h, preview)
                val rowDp = when (s) { GameSection.PLAYS -> PanelFit.ROW_PLAYS_DP; GameSection.STATS -> PanelFit.ROW_STATS_DP; GameSection.LEADERS -> PanelFit.ROW_LEADERS_DP; else -> PanelFit.ROW_STANDINGS_DP }
                val used = PanelFit.CARD_PADDING_DP + PanelFit.TITLE_DP + (if (s == GameSection.STANDINGS) PanelFit.STANDINGS_HEADER_DP else 0) + n * rowDp
                assertTrue("$s preview=$preview used $used <= $h", used <= h)
            }
        }
    }
}

class GameScreenFocusTest {
    private val all = setOf(FocusKey.PRIMARY, FocusKey.CHANNELS, FocusKey.SECTION_PLAYS, FocusKey.SECTION_STATS, FocusKey.SECTION_LEADERS, FocusKey.SECTION_STANDINGS)
    private fun mv(from: FocusKey, d: FocusDir, sel: FocusKey? = FocusKey.SECTION_STATS, av: Set<FocusKey> = all) = GameDetailFocus.move(from, d, av, sel)

    @Test fun `right walks PRIMARY then CHANNELS then the selected chip then the next chips`() {
        assertEquals("primary to channels", FocusKey.CHANNELS, mv(FocusKey.PRIMARY, FocusDir.RIGHT))
        assertEquals("channels to the SELECTED chip", FocusKey.SECTION_STATS, mv(FocusKey.CHANNELS, FocusDir.RIGHT))
        assertEquals("chip to the next chip", FocusKey.SECTION_LEADERS, mv(FocusKey.SECTION_STATS, FocusDir.RIGHT))
        assertEquals("next", FocusKey.SECTION_STANDINGS, mv(FocusKey.SECTION_LEADERS, FocusDir.RIGHT))
        assertNull("end of the row", mv(FocusKey.SECTION_STANDINGS, FocusDir.RIGHT))
    }

    @Test fun `left reverses`() {
        assertEquals("chip to previous chip", FocusKey.SECTION_PLAYS, mv(FocusKey.SECTION_STATS, FocusDir.LEFT))
        assertEquals("first chip to channels", FocusKey.CHANNELS, mv(FocusKey.SECTION_PLAYS, FocusDir.LEFT))
        assertEquals("channels to primary", FocusKey.PRIMARY, mv(FocusKey.CHANNELS, FocusDir.LEFT))
        assertNull("primary is the left end", mv(FocusKey.PRIMARY, FocusDir.LEFT))
    }

    @Test fun `up from the buttons goes to the selected chip and down from a chip goes to primary`() {
        assertEquals("up from primary", FocusKey.SECTION_STATS, mv(FocusKey.PRIMARY, FocusDir.UP))
        assertEquals("up from channels", FocusKey.SECTION_STATS, mv(FocusKey.CHANNELS, FocusDir.UP))
        assertEquals("down from any chip", FocusKey.PRIMARY, mv(FocusKey.SECTION_STANDINGS, FocusDir.DOWN))
        assertNull("down from primary stays", mv(FocusKey.PRIMARY, FocusDir.DOWN))
        assertNull("up from a chip stays", mv(FocusKey.SECTION_PLAYS, FocusDir.UP))
    }

    @Test fun `missing controls are skipped and an unavailable selection falls back to the first chip`() {
        val noPrimary = all - FocusKey.PRIMARY
        assertEquals("down lands on channels", FocusKey.CHANNELS, mv(FocusKey.SECTION_PLAYS, FocusDir.DOWN, av = noPrimary))
        assertEquals("selected chip vanished: first chip", FocusKey.SECTION_PLAYS, mv(FocusKey.CHANNELS, FocusDir.RIGHT, sel = FocusKey.SECTION_LINEUPS))
        assertEquals("no channels: primary goes to the chip", FocusKey.SECTION_STATS, mv(FocusKey.PRIMARY, FocusDir.RIGHT, av = all - FocusKey.CHANNELS))
    }

    @Test fun `controls include one key per section and the focused section vanishing moves focus to PRIMARY`() {
        val watch = WatchUi(WatchMode.BROWSING, null, PrimaryAction.Play(WatchCandidate("a", "A", 0, 1)), WatchStatus.READY, 0)
        val ui = GameDetailUiState.Loaded(detailOf())
        val with = GameDetailFocus.controls(ui, watch, sections = listOf(GameSection.PLAYS, GameSection.STANDINGS))
        assertEquals("keys", setOf(FocusKey.PRIMARY, FocusKey.CHANNELS, FocusKey.SECTION_PLAYS, FocusKey.SECTION_STANDINGS), with)
        val after = GameDetailFocus.controls(ui, watch, sections = listOf(GameSection.PLAYS))
        assertEquals("standings gone -> primary", FocusKey.PRIMARY, GameDetailFocus.repair(FocusKey.SECTION_STANDINGS, after))
        assertEquals("a surviving chip keeps focus by id after a refresh", FocusKey.SECTION_PLAYS, GameDetailFocus.target(FocusKey.SECTION_PLAYS, userMoved = true, available = after))
    }

    @Test fun `initial focus stays on the primary action even with chips`() {
        val watch = WatchUi(WatchMode.BROWSING, null, PrimaryAction.Play(WatchCandidate("a", "A", 0, 1)), WatchStatus.READY, 0)
        assertEquals("primary", FocusKey.PRIMARY, GameDetailFocus.initial(GameDetailUiState.Loaded(detailOf()), watch))
    }
}

class GameScreenModelTest {
    private val p1 = TimelineItem("5:12", "1st Quarter", SideKey.HOME, "Layup", TimelineKind.SCORE, id = "a", score = "2–0")
    private val p2 = TimelineItem("4:50", "1st Quarter", SideKey.AWAY, "Three", TimelineKind.SCORE, id = "b", score = "2–3")

    private fun model(vararg blocks: GameBlock, state: GameState = GameState.LIVE) =
        GameDetailTvModel.from(detailOf(*blocks, state = state), 0L)

    @Test fun `no sections without the new blocks, so today's cards stay`() =
        assertTrue("empty", model().sections.isEmpty())

    @Test fun `sections come from the policy without overview or lineups`() {
        val m = model(GameBlock.Plays(SourceId.ESPN, listOf(p2, p1)), GameBlock.Stats(SourceId.ESPN, listOf(StatRow("Rebounds", "40", "35"))),
            GameBlock.Leaders(SourceId.ESPN, listOf(LeaderItem("Points", "A", "30", SideKey.HOME))))
        assertEquals("sections", listOf(GameSection.PLAYS, GameSection.STATS, GameSection.LEADERS), m.sections)
    }

    @Test fun `the last play is the newest and play rows are newest first`() {
        val fromPlays = model(GameBlock.Plays(SourceId.ESPN, listOf(p2, p1)))
        assertEquals("newest of plays", "Three", fromPlays.lastPlay?.text)
        assertEquals("rows newest first", listOf("Three", "Layup"), fromPlays.playRows.map { it.text })
        assertEquals("running score", "2–3", fromPlays.playRows.first().score)
        val fromTimeline = model(GameBlock.Timeline(SourceId.ESPN, listOf(p1, p2)))
        assertEquals("timeline is oldest first so the newest is last", "Three", fromTimeline.lastPlay?.text)
        assertEquals("rows reversed", listOf("Three", "Layup"), fromTimeline.playRows.map { it.text })
    }

    @Test fun `no plays means no last play card`() = assertNull("hidden", model().lastPlay)

    @Test fun `stat lines flip the leader for lower-is-better rows`() {
        val m = model(GameBlock.Stats(SourceId.ESPN, listOf(StatRow("Rebounds", "40", "35"), StatRow("Turnovers", "9", "14"), StatRow("FG", "4/11", "5/9"))))
        assertEquals("more rebounds leads", SideKey.HOME, m.statLines[0].leader)
        assertEquals("fewer turnovers leads", SideKey.HOME, m.statLines[1].leader)
        assertNull("non-numeric has no bar and no leader", m.statLines[2].homeFraction)
        assertNull("no leader", m.statLines[2].leader)
    }
}

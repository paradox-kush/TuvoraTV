package com.nuvio.tv.ui.screens.gamedetail

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.tv.material3.Text
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.SideKey
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.screen.GameSection
import com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel
import com.nuvio.tv.core.sports.detail.screen.MotionSpec
import com.nuvio.tv.core.sports.detail.screen.Moment
import com.nuvio.tv.core.sports.detail.screen.MomentBatch
import com.nuvio.tv.core.sports.detail.screen.PanelFit
import com.nuvio.tv.core.sports.detail.screen.StandingsWindow
import com.nuvio.tv.core.sports.detail.screen.TeamColor
import com.nuvio.tv.core.sports.detail.screen.TvChart
import com.nuvio.tv.core.sports.detail.screen.TvLastPlay
import com.nuvio.tv.core.sports.detail.screen.TvLeader
import com.nuvio.tv.core.sports.detail.screen.TvPlay
import com.nuvio.tv.core.sports.detail.screen.TvStatLine
import com.nuvio.tv.core.sports.detail.screen.tvName
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Team colours made safe for the card surface (TeamColor), with the theme accents as the fallback. */
internal data class TeamColors(val home: Color, val away: Color)

@Composable
internal fun rememberTeamColors(sides: List<Side>): TeamColors {
    val surface = NuvioTheme.colors.BackgroundCard
    val accent = NuvioTheme.colors.Secondary
    val neutral = NuvioTheme.colors.Info
    return remember(sides, surface, accent, neutral) {
        if (sides.size != 2) TeamColors(accent, neutral) else {
            val surfaceHex = "%06x".format(surface.toArgb() and 0xFFFFFF)
            val (h, a) = TeamColor.pair(sides[0], sides[1], surfaceHex)
            TeamColors(h?.let(::hexColor) ?: accent, a?.let(::hexColor) ?: neutral)
        }
    }
}

private fun hexColor(hex: String): Color = Color(0xFF000000.toInt() or hex.toInt(16))

private val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// ---- animation: one effect per real moment, never a loop ----------------------------------------------------------------

/**
 * The score: counts to the new value over COUNT_MS and pops (1 -> 1.25 -> 1, accent tint) over PULSE_MS, once per
 * [MomentBatch] that carries a ScoreChange for [side]. On open (first composition) it just shows the value.
 */
@Composable
internal fun AnimatedScore(text: String, side: SideKey, batch: MomentBatch, modifier: Modifier, color: Color, accent: Color, fontSize: Int) {
    val target = text.toIntOrNull()
    val shown = remember { Animatable((target ?: 0).toFloat()) }
    val pulse = remember { Animatable(0f) }
    val change = batch.moments.filterIsInstance<Moment.ScoreChange>().firstOrNull { it.side == side }
    LaunchedEffect(batch.seq, text) {
        if (target == null) return@LaunchedEffect
        if (change != null && change.to == text && shown.value.roundToInt() != target) {
            pulse.snapTo(0f)
            kotlinx.coroutines.coroutineScope {
                launch { shown.animateTo(target.toFloat(), tween(MotionSpec.COUNT_MS, easing = Standard)) }
                launch { pulse.animateTo(1f, tween(MotionSpec.PULSE_MS, easing = Standard)); pulse.snapTo(0f) }
            }
        } else shown.snapTo(target.toFloat())
    }
    val bump = sin(PI.toFloat() * pulse.value) // 0 -> 1 -> 0
    Text(
        text = if (target != null) shown.value.roundToInt().toString() else text,
        fontSize = fontSize.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1,
        color = lerp(color, accent, bump),
        modifier = modifier.graphicsLayer { val sc = 1f + 0.25f * bump; scaleX = sc; scaleY = sc },
    )
}

// ---- left column ---------------------------------------------------------------------------------------------------------

/** One line: the newest play. Content cross-fades (QUICK_MS) when a new play arrives. */
@Composable
internal fun LastPlayCard(play: TvLastPlay, colors: TeamColors) {
    Card {
        Crossfade(targetState = play, animationSpec = tween(MotionSpec.QUICK_MS, easing = Enter), label = "lastPlay") { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.time, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.width(52.dp), maxLines = 1)
                p.tag?.let { TagChip(it, p.side, if (p.side == SideKey.AWAY) colors.away else colors.home); Spacer(Modifier.width(8.dp)) }
                Text(p.text, fontSize = 16.sp, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Win-chance chart: 50 % dashed line, the home share in the home colour, an end dot. The newest segment draws over COUNT_MS. */
@Composable
internal fun WinChart(chart: TvChart, color: Color, modifier: Modifier) {
    val n = chart.homeShare.size
    val draw = remember { Animatable(1f) }
    LaunchedEffect(n) { if (draw.value == 1f && n > 2) { draw.snapTo(0f); draw.animateTo(1f, tween(MotionSpec.COUNT_MS, easing = Standard)) } }
    val grid = NuvioTheme.colors.Border
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun pt(i: Int) = Offset(w * i / (n - 1).coerceAtLeast(1), h * (1f - chart.homeShare[i]))
        drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        for (i in 0 until n - 2) drawLine(color, pt(i), pt(i + 1), 3f, StrokeCap.Round)
        val a = pt(n - 2); val b = pt(n - 1)
        val end = Offset(a.x + (b.x - a.x) * draw.value, a.y + (b.y - a.y) * draw.value)
        drawLine(color, a, end, 3f, StrokeCap.Round)
        drawCircle(color, 5f, end)
    }
}

@Composable
internal fun animatedPercent(value: Int): Int {
    val f by animateFloatAsState(value.toFloat(), tween(MotionSpec.COUNT_MS, easing = Standard), label = "winCount")
    return f.roundToInt()
}

@Composable
internal fun animatedShare(value: Int): Float {
    val f by animateFloatAsState(value.toFloat(), tween(MotionSpec.MOVE_MS, easing = Standard), label = "winBar")
    return f
}

// ---- right column --------------------------------------------------------------------------------------------------------

private fun chipLabel(s: GameSection) = when (s) {
    GameSection.PLAYS -> "Plays"; GameSection.STATS -> "Stats"; GameSection.LEADERS -> "Leaders"
    GameSection.LINEUPS -> "Lineups"; GameSection.STANDINGS -> "Standings"; GameSection.OVERVIEW -> "Overview"
}

/** The section chips. Focusing a chip selects it (no OK press); every chip is visibly highlighted when focused. */
@Composable
internal fun SectionChips(
    sections: List<GameSection>, selected: GameSection?, requesters: (GameSection) -> FocusRequester, onFocused: (GameSection) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(PanelFit.CHIPS_DP.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        sections.forEach { s ->
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(20.dp)
            val on = s == selected
            Box(
                Modifier.fillMaxHeight().clip(shape)
                    .background(if (on) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundElevated)
                    .border(if (focused) NuvioTheme.focusRing.border(3.dp) else BorderStroke(1.dp, NuvioTheme.colors.Border), shape)
                    .focusRequester(requesters(s))
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused(s) }
                    .clickable { onFocused(s) } // OK does nothing new: focusing already selected it
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(chipLabel(s), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = if (on) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary)
            }
        }
    }
}

/** The one panel of the selected section. Rows are capped by [PanelFit] from the measured height: whole rows only, never clipped. */
@Composable
internal fun SectionPanel(section: GameSection, model: GameDetailTvModel, hasPreview: Boolean, colors: TeamColors, modifier: Modifier) {
    if (section == GameSection.LINEUPS) { PitchPanel(model, colors, modifier); return }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val h = maxHeight.value.toInt()
        Card(Modifier.fillMaxSize()) {
            when (section) {
                GameSection.PLAYS -> {
                    val n = PanelFit.rows(section, h, hasPreview, model.playRows.size)
                    CardTitle(if (model.isLive) "Plays" else "Scoring & plays")
                    model.playRows.take(n).forEach { PlayLine(it, colors) }
                }
                GameSection.STATS -> {
                    val fit = PanelFit.fit(section, h, hasPreview, model.statLines.size, hasExtra = model.linescore != null)
                    CardTitle("Team stats")
                    model.statLines.take(fit.rows).forEach { StatLine(it, colors) }
                    if (fit.extra) model.linescore?.let { Spacer(Modifier.height(6.dp)); ByPeriodCompact(it) }
                }
                GameSection.LEADERS -> {
                    val fit = PanelFit.fit(section, h, hasPreview, model.leadersAll.size, hasExtra = model.form != null)
                    CardTitle("Top performers")
                    model.leadersAll.take(fit.rows).forEach { LeaderLine(it, colors) }
                    if (fit.extra) model.form?.let { Spacer(Modifier.height(6.dp)); FormCard(it, model, Modifier) }
                }
                GameSection.STANDINGS -> {
                    val n = PanelFit.rows(section, h, hasPreview)
                    CardTitle("Standings")
                    model.standings?.let { StandingsRows(StandingsWindow.window(it, n)) }
                }
                GameSection.LINEUPS -> { Spacer(Modifier.height(0.dp)) }
                else -> Unit
            }
        }
    }
}

@Composable
private fun PlayLine(p: TvPlay, colors: TeamColors) {
    Row(Modifier.fillMaxWidth().height(PanelFit.ROW_PLAYS_DP.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(58.dp)) {
            Text(p.time, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
            p.period?.let { Text(it.take(8), fontSize = 10.sp, color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.7f), maxLines = 1) }
        }
        p.tag?.let { TagChip(it, p.side, if (p.side == SideKey.AWAY) colors.away else colors.home); Spacer(Modifier.width(8.dp)) }
        Text(p.text, fontSize = 15.sp, color = NuvioTheme.colors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        p.score?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (p.scoring) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(start = 8.dp), maxLines = 1) }
    }
}

@Composable
private fun StatLine(st: TvStatLine, colors: TeamColors) {
    Column(Modifier.fillMaxWidth().height(PanelFit.ROW_STATS_DP.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(st.home, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (st.leader == SideKey.HOME) colors.home else NuvioTheme.colors.TextPrimary, modifier = Modifier.width(72.dp))
            Text(st.label, fontSize = 14.sp, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
            Text(st.away, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (st.leader == SideKey.AWAY) colors.away else NuvioTheme.colors.TextPrimary, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
        }
        st.homeFraction?.let { f0 ->
            val f = f0.coerceIn(0.02f, 0.98f)
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(NuvioTheme.colors.Border)) {
                Box(Modifier.weight(f).fillMaxHeight().background(colors.home))
                Box(Modifier.weight(1f - f).fillMaxHeight().background(colors.away))
            }
        }
    }
}

@Composable
private fun LeaderLine(l: TvLeader, colors: TeamColors) {
    Row(Modifier.fillMaxWidth().height(PanelFit.ROW_LEADERS_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(l.category, fontSize = 14.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.width(100.dp), maxLines = 1)
        Text(l.athlete, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        l.tag?.let { TagChip(it, l.side, if (l.side == SideKey.AWAY) colors.away else colors.home); Spacer(Modifier.width(8.dp)) }
        Text(l.value, fontSize = if (l.value.length > 4) 15.sp else 20.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
    }
}

@Composable
private fun StandingsRows(t: GameBlock.StandingsTable) {
    t.groups.forEach { g ->
        Row(Modifier.fillMaxWidth().height(PanelFit.STANDINGS_HEADER_DP.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(g.name.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1)
            g.columns.forEach { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp)) }
        }
        g.rows.forEach { r ->
            Row(
                Modifier.fillMaxWidth().height(PanelFit.ROW_STANDINGS_DP.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (r.isGameTeam) NuvioTheme.colors.BackgroundElevated else Color.Transparent).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.name, fontSize = 15.sp, fontWeight = if (r.isGameTeam) FontWeight.Bold else FontWeight.Normal, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.cells.forEach { Text(it, fontSize = 14.sp, color = NuvioTheme.colors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp), maxLines = 1) }
            }
        }
    }
}

/** The by-period table, compact, under Stats. */
@Composable
private fun ByPeriodCompact(t: com.nuvio.tv.core.sports.detail.screen.TvLinescore) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(80.dp))
            t.columns.forEach { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1) }
            t.totalLabel?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp)) }
        }
        t.rows.forEach { r ->
            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, modifier = Modifier.width(80.dp), maxLines = 1)
                r.cells.forEach { Text(it, fontSize = 14.sp, color = NuvioTheme.colors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1) }
                r.total?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.width(44.dp)) }
            }
        }
    }
}

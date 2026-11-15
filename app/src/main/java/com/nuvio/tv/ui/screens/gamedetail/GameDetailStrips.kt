package com.nuvio.tv.ui.screens.gamedetail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.SideKey
import com.nuvio.tv.core.sports.detail.SoccerEvent
import com.nuvio.tv.core.sports.detail.screen.FieldGeometry
import com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel
import com.nuvio.tv.core.sports.detail.screen.MomentBatch
import com.nuvio.tv.core.sports.detail.screen.Moment
import com.nuvio.tv.core.sports.detail.screen.MotionSpec
import com.nuvio.tv.core.sports.detail.screen.PitchSpot
import com.nuvio.tv.core.sports.detail.screen.tvName
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private fun SideKey.str() = if (this == SideKey.HOME) "home" else "away"

// ---- football field strip ------------------------------------------------------------------------------------------------

/**
 * The live field: home end zone on the LEFT in the home colour, away on the RIGHT, 10-yard lines, the drive span, the first-down line,
 * the ball. Above: the feed's down-and-distance text and who has the ball. Everything is drawn from [FieldGeometry]; the ball, the drive span
 * and the first-down line slide over MOVE_MS between two real snapshots (nothing loops). A loss flashes the border once.
 */
@Composable
internal fun FootballFieldStrip(field: GameBlock.Field, sides: List<Side>, colors: TeamColors, moments: MomentBatch) {
    val possession = field.possession
    val ball by animateFloatAsState(FieldGeometry.x(field.yardLine), tween(MotionSpec.MOVE_MS, easing = Standard), label = "ball")
    val fd = FieldGeometry.firstDownX(field.yardLine, field.distance, possession?.str())
    val firstDown by animateFloatAsState(fd ?: ball, tween(MotionSpec.MOVE_MS, easing = Standard), label = "firstDown")
    val span = FieldGeometry.driveSpan(field.drive?.startYardLine, field.yardLine)?.takeIf { possession != null }
    val spanA by animateFloatAsState(span?.first ?: 0f, tween(MotionSpec.MOVE_MS, easing = Standard), label = "spanA")
    val spanB by animateFloatAsState(span?.second ?: 0f, tween(MotionSpec.MOVE_MS, easing = Standard), label = "spanB")
    val teamColor = when (possession) { SideKey.HOME -> colors.home; SideKey.AWAY -> colors.away; null -> NuvioTheme.colors.TextSecondary }

    // One-shot emphasis from real moments only: a loss flashes the border, a touchdown pulses that end zone.
    val flash = remember { Animatable(0f) }
    val tdPulse = remember { Animatable(0f) }
    val loss = moments.moments.filterIsInstance<Moment.FieldMove>().firstOrNull()?.loss == true
    val td = moments.moments.filterIsInstance<Moment.Touchdown>().firstOrNull()
    LaunchedEffect(moments.seq) {
        coroutineScope {
            if (loss) launch { flash.snapTo(1f); flash.animateTo(0f, tween(MotionSpec.PULSE_MS, easing = Standard)) }
            if (td != null) launch { tdPulse.snapTo(1f); tdPulse.animateTo(0f, tween(MotionSpec.PULSE_MS, easing = Standard)) }
        }
    }

    val borderColor = when {
        flash.value > 0f -> lerp(NuvioTheme.colors.Border, NuvioTheme.colors.Error, flash.value)
        field.redZone -> teamColor
        else -> NuvioTheme.colors.Border
    }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(NuvioTheme.colors.BackgroundCard).border(1.dp, borderColor, shape).padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(field.line.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (possession != null) {
                val name = sides.getOrNull(if (possession == SideKey.HOME) 0 else 1)?.tvName.orEmpty()
                // The arrow points the way the offence is heading.
                val arrow = if (FieldGeometry.attackingToward(possession.str()) == 1f) "$name ball ▶▶" else "◀◀ $name ball"
                Text(arrow, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = teamColor, maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        val grid = NuvioTheme.colors.Border
        val strong = NuvioTheme.colors.TextSecondary
        val accent = NuvioTheme.colors.Secondary
        val turf = NuvioTheme.colors.BackgroundElevated
        val homeZone = colors.home; val awayZone = colors.away
        Canvas(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(4.dp))) {
            val w = size.width; val h = size.height
            drawRect(turf)
            val ez = w * 0.08f
            val homePulse = if (td?.side == SideKey.HOME) tdPulse.value else 0f
            val awayPulse = if (td?.side == SideKey.AWAY) tdPulse.value else 0f
            drawRect(homeZone.copy(alpha = 0.55f + 0.45f * homePulse), Offset(0f, 0f), Size(ez, h))
            drawRect(awayZone.copy(alpha = 0.55f + 0.45f * awayPulse), Offset(w - ez, 0f), Size(ez, h))
            for (i in 1..9) {
                val x = ez + (w - 2 * ez) * i / 10f
                drawLine(if (i == 5) strong else grid, Offset(x, 0f), Offset(x, h), if (i == 5) 2f else 1f)
            }
            fun fx(x: Float) = ez + (w - 2 * ez) * x
            if (span != null) drawRect(teamColor.copy(alpha = 0.38f), Offset(fx(spanA), 0f), Size(fx(spanB) - fx(spanA), h))
            if (fd != null) drawLine(accent, Offset(fx(firstDown), 0f), Offset(fx(firstDown), h), 3.dp.toPx())
            drawCircle(Color.White, h * 0.22f, Offset(fx(ball), h / 2f))
            drawCircle(teamColor, h * 0.14f, Offset(fx(ball), h / 2f))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(field.drive?.summary.orEmpty(), fontSize = 12.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** Three timeout dots under a team name on the scoreboard (filled = left). */
@Composable
internal fun TimeoutDots(left: Int?, color: Color) {
    if (left == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i -> Box(Modifier.size(7.dp).clip(CircleShape).background(if (i < left) color else NuvioTheme.colors.Border)) }
    }
}

// ---- soccer strip ----------------------------------------------------------------------------------------------------------

private fun kindLabel(kind: String) = when (kind) {
    "goal" -> "GOAL"; "yellow" -> "YELLOW"; "red" -> "RED"; "sub" -> "SUB"; "corner" -> "CORNER"; "penalty" -> "PENALTY"
    "var" -> "VAR"; "shot" -> "SHOT"; "offside" -> "OFFSIDE"; else -> kind.uppercase()
}

/**
 * Event chip (kind + team) left, possession right with the bar in the team colours, then corners and cards. A new real last event pops
 * its chip in once (QUICK_MS); the possession bar slides over MOVE_MS and its numbers count over COUNT_MS.
 */
@Composable
internal fun SoccerStrip(strip: GameBlock.SoccerStrip, sides: List<Side>, colors: TeamColors, moments: MomentBatch) {
    val pop = remember { Animatable(1f) }
    val ev = moments.moments.filterIsInstance<Moment.SoccerMoment>().firstOrNull()
    LaunchedEffect(moments.seq) { if (ev != null) { pop.snapTo(0.6f); pop.animateTo(1f, tween(MotionSpec.QUICK_MS, easing = Enter)) } }
    val homeP = strip.homePossession
    val bar by animateFloatAsState(homeP?.toFloat() ?: 50f, tween(MotionSpec.MOVE_MS, easing = Standard), label = "possBar")
    val count by animateFloatAsState(homeP?.toFloat() ?: 0f, tween(MotionSpec.COUNT_MS, easing = Standard), label = "possCount")
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            strip.lastEvent?.let { e ->
                val side = e.side
                val tag = side?.let { sides.getOrNull(if (it == SideKey.HOME) 0 else 1)?.tvName }
                EventChip(e, tag, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) })
            }
            Spacer(Modifier.weight(1f))
            if (homeP != null) Text("Possession ${count.roundToInt()}% – ${100 - count.roundToInt()}%", fontSize = 14.sp, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        }
        if (homeP != null) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(NuvioTheme.colors.Border)) {
                if (bar > 0f) Box(Modifier.weight(bar).fillMaxSize().background(colors.home))
                if (100f - bar > 0f) Box(Modifier.weight(100f - bar).fillMaxSize().background(colors.away))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (strip.homeCorners != null || strip.awayCorners != null) {
                Text("Corners ${strip.homeCorners ?: 0} – ${strip.awayCorners ?: 0}", fontSize = 14.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1)
            } else Spacer(Modifier.weight(1f))
            CardsText(strip)
        }
    }
}

@Composable
private fun EventChip(e: SoccerEvent, team: String?, modifier: Modifier) {
    val (bg, fg) = when (e.kind) {
        "goal" -> NuvioTheme.colors.Secondary to NuvioTheme.colors.OnSecondary
        "yellow" -> Color(0xFFF5C518) to Color(0xFF1A1A1A)
        "red" -> NuvioTheme.colors.Error to Color.White
        else -> NuvioTheme.colors.BackgroundElevated to NuvioTheme.colors.TextPrimary
    }
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(listOfNotNull(e.minute, kindLabel(e.kind), team).joinToString(" · "), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

@Composable
private fun CardsText(s: GameBlock.SoccerStrip) {
    if (s.homeYellow + s.awayYellow + s.homeRed + s.awayRed == 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Cards", fontSize = 14.sp, color = NuvioTheme.colors.TextSecondary)
        Box(Modifier.size(9.dp).background(Color(0xFFF5C518)))
        Text("${s.homeYellow} – ${s.awayYellow}", fontSize = 14.sp, color = NuvioTheme.colors.TextPrimary)
        if (s.homeRed + s.awayRed > 0) {
            Box(Modifier.padding(start = 6.dp).size(9.dp).background(NuvioTheme.colors.Error))
            Text("${s.homeRed} – ${s.awayRed}", fontSize = 14.sp, color = NuvioTheme.colors.TextPrimary)
        }
    }
}

/** At most three scorer lines then "+N", for under a team name on the scoreboard. */
internal fun scorerText(scorers: List<String>): String? = when {
    scorers.isEmpty() -> null
    scorers.size <= 3 -> scorers.joinToString(", ")
    else -> scorers.take(3).joinToString(", ") + " +${scorers.size - 3}"
}

// ---- pitch lineups (Lineups chip) ------------------------------------------------------------------------------------------

/** Home on the left half, away mirrored on the right, shirt numbers in team-coloured dots; formations in the header. */
@Composable
internal fun PitchPanel(model: GameDetailTvModel, colors: TeamColors, modifier: Modifier) {
    val pitch = model.pitch ?: return
    val ink = NuvioTheme.colors.TextPrimary
    Card(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${model.sides.getOrNull(0)?.tvName.orEmpty()} ${pitch.homeFormation.orEmpty()}".trim(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.home, modifier = Modifier.weight(1f), maxLines = 1)
            Text("${pitch.awayFormation.orEmpty()} ${model.sides.getOrNull(1)?.tvName.orEmpty()}".trim(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.away, textAlign = TextAlign.End, modifier = Modifier.weight(1f), maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        val lines = NuvioTheme.colors.Border
        val turf = NuvioTheme.colors.BackgroundElevated
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(turf)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                drawLine(lines, Offset(w / 2, 0f), Offset(w / 2, h), 2f)
                drawCircle(lines, h * 0.16f, Offset(w / 2, h / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                drawRect(lines, Offset(0f, h * 0.22f), Size(w * 0.12f, h * 0.56f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                drawRect(lines, Offset(w * 0.88f, h * 0.22f), Size(w * 0.12f, h * 0.56f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                fun dot(spot: PitchSpot, home: Boolean) {
                    val x = if (home) spot.x * (w / 2) else w - spot.x * (w / 2)
                    drawCircle(if (home) colors.home else colors.away, 13.dp.toPx(), Offset(x, spot.y * h))
                }
                pitch.home.forEach { dot(it, true) }
                pitch.away.forEach { dot(it, false) }
            }
            // Shirt numbers sit on the dots; positioned with the same maths as the canvas, in dp via the measured box.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                (pitch.home.map { it to true } + pitch.away.map { it to false }).forEach { (spot, home) ->
                    val x = if (home) spot.x * (maxWidth.value / 2) else maxWidth.value - spot.x * (maxWidth.value / 2)
                    Text(
                        spot.player.number.orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, maxLines = 1,
                        modifier = Modifier.padding(start = (x - 13).dp, top = (spot.y * maxHeight.value - 8).dp).width(26.dp),
                    )
                }
            }
        }
    }
}

package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.iptv.XtreamCatchUp
import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Calendar
import java.util.Locale

/**
 * The catch-up half of the Live TV guide: programme cells the D-pad can actually stop on, the
 * replay badges that say what the panel kept, and the two-button sheet.
 *
 * Kept beside [XtreamLiveGuideScreen] rather than inside it because the guide file is already the
 * player host, the category column and the fullscreen overlay; this is a self-contained layer over
 * the same rows.
 */

/** What the guide may offer for one programme on one channel. */
internal fun guideActionFor(
    programme: XtreamProgram,
    channel: GuideChannel,
    nowMs: Long,
    catchUpSupported: Boolean,
): ProgrammeAction {
    val action = XtreamCatchUp.actionFor(
        programmeStartMs = programme.startMs,
        programmeEndMs = programme.endMs,
        nowMs = nowMs,
        hasArchive = channel.hasArchive,
        catchUpDays = channel.catchUpDays,
        programmeHasArchive = programme.hasArchive,
    )
    // A playlist that cannot build a catch-up URL must never show a replay affordance: a Stalker
    // portal builds its archive URLs server-side and an M3U playlist has no panel to ask, so
    // offering either a badge or a press there is a promise nothing can keep.
    if (catchUpSupported) return action
    return when (action) {
        ProgrammeAction.START_OVER -> ProgrammeAction.PLAY_LIVE
        ProgrammeAction.REPLAY -> ProgrammeAction.NONE
        else -> action
    }
}

/**
 * Duration-proportional programme cells for one channel across the visible window.
 *
 * On the focused row (and only there — the D-pad can only be in one place, and making every row's
 * cells focusable would put thousands of targets in the tree) the actionable cells take focus, so
 * the traversal naturally skips future programmes and anything the provider did not keep.
 */
@Composable
internal fun RowScope.GuideProgrammeCells(
    programmes: List<XtreamProgram>,
    channel: GuideChannel,
    windowStartMs: Long,
    nowMs: Long,
    catchUpSupported: Boolean,
    interactive: Boolean,
    onProgrammeClick: (XtreamProgram) -> Unit,
    onTravel: (Int) -> Boolean,
    leadingEdgeFocus: FocusRequester? = null,
    trailingEdgeFocus: FocusRequester? = null,
    /** Focus target for the strip itself when the window holds nothing actionable (B114). */
    stripFocus: FocusRequester? = null,
    nowFraction: Float? = null,
    onExitTimeline: (() -> Unit)? = null,
) {
    val windowEndMs = windowStartMs + GuideTimeTravel.WINDOW_MS
    val visible = programmes
        .filter { it.endMs > windowStartMs && it.startMs < windowEndMs && it.endMs > it.startMs }
        .sortedBy { it.startMs }
    val actionable = if (!interactive) emptyList() else visible.filter {
        GuideCellIntent.isFocusable(guideActionFor(it, channel, nowMs, catchUpSupported))
    }
    val actionableStarts = actionable.map { it.startMs }
    val firstActionableStart = actionableStarts.firstOrNull()
    val lastActionableStart = actionableStarts.lastOrNull()
    var focusedStart by remember { mutableStateOf<Long?>(null) }
    // The strip holds the cursor when the window has no cell to stop on, so time travel never
    // depends on one existing — see GuideTimelineNavPolicy.
    var stripFocused by remember { mutableStateOf(false) }
    // The window a page left from, while that page is in flight: the window reaches the screen a
    // frame or more after the key, and until it does the cursor waits on the strip (which survives
    // the move) instead of the departing cell — see GuideTimelineNavPolicy.stripFocusable.
    var travelFromWindowMs by remember { mutableStateOf<Long?>(null) }
    val travelling = travelFromWindowMs == windowStartMs
    LaunchedEffect(windowStartMs, interactive) {
        if (!interactive || travelFromWindowMs != windowStartMs) travelFromWindowMs = null
    }
    // Cells arriving under a waiting cursor (history landing after the travel) take it over, the
    // same landing a travel makes. Not the departing window's cells while a page is in flight.
    LaunchedEffect(stripFocused, actionable.isNotEmpty()) {
        if (stripFocused && actionable.isNotEmpty() && !travelling) {
            leadingEdgeFocus?.requestFocusOrFalse()
        }
    }
    fun travel(slots: Int) {
        val from = windowStartMs
        if (onTravel(slots)) {
            travelFromWindowMs = from
            if (!stripFocused) stripFocus?.requestFocusOrFalse()
        }
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(start = NuvioTheme.spacing.sm, top = 3.dp, bottom = 3.dp)
            // LEFT and RIGHT are the time-travel keys: inside the strip they step between cells,
            // and AT the window's edge they scroll the window itself. That is why BACK, not LEFT,
            // is the way out of the timeline.
            .onPreviewKeyEvent { event ->
                if (!interactive) return@onPreviewKeyEvent false
                // BACK leaves the timeline in one press (Compose would spend it on FocusDirection.Exit).
                if (event.key == Key.Back && onExitTimeline != null) {
                    when (GuideTimelineNavPolicy.onBack(isKeyDown = event.type == KeyEventType.KeyDown)) {
                        GuideTimelineNavPolicy.BackOutcome.LEAVE_TIMELINE -> onExitTimeline()
                        GuideTimelineNavPolicy.BackOutcome.CONSUME -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> GuideTimelineNavPolicy.Direction.BACK
                    Key.DirectionRight -> GuideTimelineNavPolicy.Direction.FORWARD
                    else -> return@onPreviewKeyEvent false
                }
                // Edge presses PAGE (a full window), they do not step a slot — see
                // GuideTimeTravel.EDGE_TRAVEL_SLOTS for the field report behind this.
                when (
                    GuideTimelineNavPolicy.onHorizontalKey(
                        direction = direction,
                        focusedCellStart = if (stripFocused) null else focusedStart,
                        actionableStarts = actionableStarts,
                    )
                ) {
                    GuideTimelineNavPolicy.KeyOutcome.WALK -> false
                    GuideTimelineNavPolicy.KeyOutcome.TRAVEL_BACK -> { travel(-GuideTimeTravel.EDGE_TRAVEL_SLOTS); true }
                    GuideTimelineNavPolicy.KeyOutcome.TRAVEL_FORWARD -> { travel(GuideTimeTravel.EDGE_TRAVEL_SLOTS); true }
                }
            }
            // The strip is a focus target on the timeline row only. It stays in the tree for the
            // whole visit and is switched with canFocus rather than added and removed, so cells
            // arriving under a focused strip never detach the node that holds the cursor.
            .then(
                if (interactive && stripFocus != null) {
                    Modifier
                        .focusRequester(stripFocus)
                        // Read at request time: a page parks the cursor here in the same key
                        // handler that starts it, before any recomposition.
                        .focusProperties {
                            canFocus = GuideTimelineNavPolicy.stripFocusable(
                                interactive = interactive,
                                hasActionableCell = actionable.isNotEmpty(),
                                stripFocused = stripFocused,
                                travelling = travelFromWindowMs == windowStartMs,
                            )
                        }
                        .onFocusChanged {
                            stripFocused = it.isFocused
                            if (it.isFocused) focusedStart = null
                        }
                        // The app's D-pad vocabulary: a focused strip is outlined like a cell.
                        .border(
                            if (stripFocused) 2.dp else 0.dp,
                            if (stripFocused) NuvioTheme.colors.BorderFocused else Color.Transparent,
                            RoundedCornerShape(NuvioTheme.radii.xs),
                        )
                        // clickable for reliable D-pad focus (see GuideCell); OK on an empty
                        // stretch of time has nothing to do.
                        .clickable { }
                } else Modifier
            )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (visible.isEmpty()) {
                GuideCell(
                    title = null,
                    weightMs = GuideTimeTravel.WINDOW_MS,
                    action = ProgrammeAction.NONE,
                    live = false,
                    filler = true,
                    onClick = {},
                )
            } else {
                var cursor = windowStartMs
                for (p in visible) {
                    val start = maxOf(p.startMs, windowStartMs)
                    val end = minOf(p.endMs, windowEndMs)
                    if (start > cursor) {
                        GuideCell(null, start - cursor, ProgrammeAction.NONE, false, true, {})
                    }
                    val action = guideActionFor(p, channel, nowMs, catchUpSupported)
                    val focusable = interactive && GuideCellIntent.isFocusable(action)
                    GuideCell(
                        title = p.title,
                        weightMs = end - start,
                        action = action,
                        live = nowMs in p.startMs until p.endMs,
                        filler = false,
                        onClick = { onProgrammeClick(p) },
                        focusable = focusable,
                        // The window's edge cells carry the travel requesters, so a window that has
                        // just scrolled can put the cursor back where the viewer was pushing.
                        // A single actionable cell is BOTH edges: forward travel lands on the trailing
                        // requester, which used to be missing there and dumped the cursor.
                        focusRequesters = if (!focusable) emptyList() else listOfNotNull(
                            leadingEdgeFocus.takeIf { p.startMs == firstActionableStart },
                            trailingEdgeFocus.takeIf { p.startMs == lastActionableStart },
                        ),
                        onFocused = { focusedStart = p.startMs },
                    )
                    cursor = end
                }
                if (cursor < windowEndMs) {
                    GuideCell(null, windowEndMs - cursor, ProgrammeAction.NONE, false, true, {})
                }
            }
        }
        // The now-line. It is what tells a travelled window from a live one at a glance, so it is
        // drawn over the cells rather than tinted into one of them.
        if (nowFraction != null) {
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(nowFraction.coerceIn(0.001f, 0.999f)))
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(NuvioTheme.colors.Error)
                )
                Spacer(Modifier.weight((1f - nowFraction).coerceIn(0.001f, 0.999f)))
            }
        }
    }
}

/**
 * One programme cell.
 *
 * Focus is the app's D-pad vocabulary at full strength — a solid Primary fill plus a focus-ring
 * border — deliberately louder than the "airing now" tint (Primary at 20%) so the cursor and the
 * live marker can never be mistaken for each other on a ten-foot screen.
 */
@Composable
private fun RowScope.GuideCell(
    title: String?,
    weightMs: Long,
    action: ProgrammeAction,
    live: Boolean,
    filler: Boolean,
    onClick: () -> Unit,
    focusable: Boolean = false,
    focusRequesters: List<FocusRequester> = emptyList(),
    onFocused: () -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnFocused by rememberUpdatedState(onFocused)
    val replayable = GuideCellIntent.showsReplayBadge(action)
    Box(
        modifier = Modifier
            .weight(weightMs.coerceAtLeast(60_000L).toFloat())
            .fillMaxHeight()
            .clip(RoundedCornerShape(NuvioTheme.radii.xs))
            .background(
                when {
                    isFocused -> NuvioTheme.colors.Primary
                    live -> NuvioTheme.colors.Primary.copy(alpha = 0.20f)
                    filler -> NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.4f)
                    else -> NuvioTheme.colors.BackgroundElevated
                }
            )
            .border(
                if (isFocused) 2.dp else 0.dp,
                if (isFocused) NuvioTheme.colors.BorderFocused else Color.Transparent,
                RoundedCornerShape(NuvioTheme.radii.xs)
            )
            .then(focusRequesters.fold<FocusRequester, Modifier>(Modifier) { m, r -> m.focusRequester(r) })
            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) latestOnFocused() }
            // clickable, not focusable(): the same reason GuideCategoryRow gives — plain
            // focusable() does not reliably take D-pad focus inside these lists.
            .then(if (focusable) Modifier.clickable { latestOnClick() } else Modifier)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (title == null) return@Box
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (replayable) {
                Text(
                    text = REPLAY_GLYPH,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) NuvioTheme.colors.OnPrimary else NuvioTheme.colors.Primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    isFocused -> NuvioTheme.colors.OnPrimary
                    live -> NuvioTheme.colors.TextPrimary
                    else -> NuvioTheme.colors.TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The half-hour ruler, plus the day the window is sitting on and a live/back-in-time marker.
 *
 * The day label is the whole point of time travel: two hours of half-hour ticks look identical
 * whether they are this evening or last Tuesday, so without it the viewer cannot tell how far back
 * they have gone.
 */
@Composable
internal fun GuideTimeHeaderWithDay(
    windowStartMs: Long,
    nowMs: Long,
    labelWidth: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.md + NuvioTheme.spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.width(labelWidth + NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val atLive = GuideTimeTravel.isAtLiveEdge(windowStartMs, nowMs)
            Text(
                text = guideDayLabel(windowStartMs, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (atLive) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.Primary,
                fontWeight = if (atLive) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val slots = (GuideTimeTravel.WINDOW_MS / GuideTimeTravel.SLOT_MS).toInt()
        repeat(slots) { i ->
            Text(
                text = guideHhMm(windowStartMs + i * GuideTimeTravel.SLOT_MS),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }
    }
}

/**
 * "Today" / "Yesterday" / "Sat 14 Aug", by calendar day rather than by elapsed hours.
 *
 * A window that has the now-line on screen is labelled by now's day: just after midnight the live
 * window opens one lookback slot back, on the previous day, and labelling it by its start read
 * "Yesterday" over tonight's live schedule.
 */
internal fun guideDayLabel(windowStartMs: Long, nowMs: Long): String {
    val anchorMs = if (GuideTimeTravel.containsNow(windowStartMs, nowMs)) nowMs else windowStartMs
    val days = calendarDaysBetween(anchorMs, nowMs)
    return when (days) {
        0 -> "Today"
        1 -> "Yesterday"
        -1 -> "Tomorrow"
        else -> {
            val c = Calendar.getInstance().apply { timeInMillis = anchorMs }
            String.format(Locale.getDefault(), "%1\$ta %1\$te %1\$tb", c)
        }
    }
}

/** Whole calendar days [nowMs] is ahead of [thenMs] — 23:30 to 00:30 is one day, not zero. */
private fun calendarDaysBetween(thenMs: Long, nowMs: Long): Int {
    fun dayIndex(ms: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis / (24 * 60 * 60 * 1000L)
    }
    return (dayIndex(nowMs) - dayIndex(thenMs)).toInt()
}

internal fun guideHhMm(ms: Long): String {
    if (ms <= 0L) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

/**
 * The two-button sheet — shown in exactly one situation, the airing programme on a channel with an
 * archive, because it is the only state with two reasonable destinations and no obvious default.
 * Every other state acts on one press.
 */
@Composable
internal fun GuideStartOverSheet(
    channelName: String,
    programme: XtreamProgram,
    onStartOver: () -> Unit,
    onWatchLive: () -> Unit,
) {
    val startOverFocus = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(NuvioTheme.colors.BackgroundElevated)
                .padding(NuvioTheme.spacing.xl)
        ) {
            Text(
                text = "${channelName.uppercase(Locale.getDefault())} · ${guideHhMm(programme.startMs)}–" +
                    "${guideHhMm(programme.endMs)} · ${XtreamCatchUp.durationMinutes(programme.startMs, programme.endMs)} MIN",
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = programme.title,
                style = MaterialTheme.typography.titleLarge,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (programme.description.isNotBlank()) {
                Spacer(Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = programme.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(NuvioTheme.spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                GuideSheetButton(
                    label = "$REPLAY_GLYPH  Start over",
                    primary = true,
                    focusRequester = startOverFocus,
                    onClick = onStartOver,
                )
                GuideSheetButton(label = "▶  Watch live", primary = false, onClick = onWatchLive)
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(programme.startMs) {
        runCatching { startOverFocus.requestFocus() }
    }
}

/** Sheet button. Focused = solid Primary + focus ring; unfocused primary keeps a Primary outline. */
@Composable
private fun GuideSheetButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val latestOnClick by rememberUpdatedState(onClick)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(
                when {
                    isFocused -> NuvioTheme.colors.Primary
                    primary -> NuvioTheme.colors.Primary.copy(alpha = 0.22f)
                    else -> Color.Transparent
                }
            )
            .border(
                2.dp,
                when {
                    isFocused -> NuvioTheme.colors.BorderFocused
                    primary -> NuvioTheme.colors.Primary
                    else -> NuvioTheme.colors.Border
                },
                RoundedCornerShape(NuvioTheme.radii.sm)
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { latestOnClick() }
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (isFocused) NuvioTheme.colors.OnPrimary else NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** The ⟲ every broadcaster guide uses for "this one was kept". */
private const val REPLAY_GLYPH = "⟲"

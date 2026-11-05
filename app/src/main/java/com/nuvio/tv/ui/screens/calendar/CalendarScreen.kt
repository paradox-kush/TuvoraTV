// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.calendar

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.calendar.CALENDAR_WINDOW_OPTIONS
import com.nuvio.tv.core.calendar.CalendarEpisodeLabel
import com.nuvio.tv.core.calendar.CalendarFilter
import com.nuvio.tv.core.calendar.CalendarGameItem
import com.nuvio.tv.core.calendar.CalendarGameState
import com.nuvio.tv.core.calendar.CalendarSectionKind
import com.nuvio.tv.core.calendar.CalendarViewMode
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Calendar page: List (grouped by Just aired / Today / Tomorrow / days / Later / No date yet) or Month. Thin by
 * design: everything it shows comes from [CalendarViewModel.uiState]; it does no I/O.
 */
@Composable
fun CalendarScreen(
    onOpenTitle: (contentId: String, type: String) -> Unit,
    /** After "Watch now" handed a game to Sports: open the Sports page, which shows its channel matches. */
    onWatchGame: () -> Unit = {},
    showBuiltInHeader: Boolean = true,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shownMonth by viewModel.month.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val formats = remember(locale) { CalendarFormats(locale, DateFormat.is24HourFormat(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenShown()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.xl)
    ) {
        CalendarHeader(
            showTitle = showBuiltInHeader,
            viewMode = state.viewMode,
            settingsOpen = settingsOpen,
            onViewMode = viewModel::setViewMode,
            onSettings = { settingsOpen = !settingsOpen }
        )
        if (!settingsOpen) {
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            CalendarFilterRow(filters = state.filters, onToggle = viewModel::toggleFilter)
        }
        Spacer(Modifier.height(NuvioTheme.spacing.lg))
        if (settingsOpen) {
            CalendarSettingsPanel(state = state, viewModel = viewModel, onClose = { settingsOpen = false })
            return@Column
        }
        when (val status = state.status) {
            is CalendarScreenStatus.Loading -> CalendarLoading(status.remaining)
            is CalendarScreenStatus.Failed -> ErrorState(
                message = stringResource(if (status.timedOut) R.string.calendar_failed_timed_out else R.string.calendar_failed),
                onRetry = viewModel::retry,
                requestInitialFocus = true
            )
            is CalendarScreenStatus.Empty -> EmptyScreenState(
                title = stringResource(
                    if (status.nothingFollowed) R.string.calendar_empty_nothing_followed else R.string.calendar_empty_all_quiet
                ),
                subtitle = if (status.nothingFollowed) stringResource(R.string.calendar_empty_nothing_followed_hint)
                else stringResource(R.string.calendar_empty_all_quiet_hint, state.windowDays)
            )
            is CalendarScreenStatus.Ready -> {
                if (status.updateFailed) CalendarUpdateFailedNote(onRetry = viewModel::retry)
                when (state.viewMode) {
                    CalendarViewMode.LIST -> CalendarListView(state, formats, viewModel, onOpenTitle, onWatchGame)
                    CalendarViewMode.MONTH -> CalendarMonthView(
                        month = shownMonth ?: YearMonth.from(state.today),
                        state = state,
                        formats = formats,
                        onMonth = viewModel::showMonth,
                        viewModel = viewModel,
                        onOpenTitle = onOpenTitle,
                        onWatchGame = onWatchGame
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarHeader(
    showTitle: Boolean,
    viewMode: CalendarViewMode,
    settingsOpen: Boolean,
    onViewMode: (CalendarViewMode) -> Unit,
    onSettings: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.calendar_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (showTitle) NuvioTheme.colors.TextPrimary else Color.Transparent,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            CalendarChip(
                label = stringResource(R.string.calendar_view_list),
                selected = viewMode == CalendarViewMode.LIST && !settingsOpen,
                onClick = { onViewMode(CalendarViewMode.LIST) }
            )
            CalendarChip(
                label = stringResource(R.string.calendar_view_month),
                selected = viewMode == CalendarViewMode.MONTH && !settingsOpen,
                onClick = { onViewMode(CalendarViewMode.MONTH) }
            )
            CalendarIconAction(
                icon = Icons.Default.Settings,
                contentDescription = stringResource(R.string.calendar_settings),
                onClick = onSettings,
                selected = settingsOpen
            )
        }
    }
}

@Composable
private fun CalendarLoading(remaining: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LoadingIndicator()
        Spacer(Modifier.height(NuvioTheme.spacing.md))
        Text(
            text = if (remaining > 0) stringResource(R.string.calendar_loading_remaining, remaining) else stringResource(R.string.calendar_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun CalendarUpdateFailedNote(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.padding(bottom = NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Text(
            text = stringResource(R.string.calendar_update_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        CalendarChip(label = stringResource(R.string.action_retry), selected = false, onClick = onRetry)
    }
}

// ------------------------------------------------------------------ List

@Composable
private fun CalendarListView(
    state: CalendarUiState,
    formats: CalendarFormats,
    viewModel: CalendarViewModel,
    onOpenTitle: (String, String) -> Unit,
    onWatchGame: () -> Unit
) {
    val entries = state.list.entries
    val startIndex = remember(state.list.startRowKey) {
        entries.indexOfFirst { it.key == state.list.startRowKey }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 1).coerceAtLeast(0))
    val startFocus = remember { FocusRequester() }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.list.startRowKey) {
        if (!focusedOnce && state.list.startRowKey != null) {
            focusedOnce = runCatching { startFocus.requestFocus() }.isSuccess
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        items(entries, key = { it.key }) { entry ->
            when (entry) {
                is CalendarHeaderEntry -> CalendarSectionHeader(entry, state.today, formats)
                is CalendarRowEntry -> if (entry.row.kind == CalendarRowKind.GAME) CalendarGameRowCard(
                    row = entry.row,
                    formats = formats,
                    showDate = entry.row.section == CalendarSectionKind.JUST_AIRED,
                    onWatch = { viewModel.watchGame(entry.row); onWatchGame() },
                    modifier = if (entry.key == state.list.startRowKey) Modifier.focusRequester(startFocus) else Modifier
                ) else CalendarRowCard(
                    row = entry.row,
                    formats = formats,
                    showDate = entry.row.section == CalendarSectionKind.JUST_AIRED || entry.row.section == CalendarSectionKind.LATER,
                    onOpen = { entry.row.contentId?.let { onOpenTitle(it, entry.row.apiType) } },
                    onBell = { viewModel.toggleBell(entry.row) },
                    onHide = { viewModel.hide(entry.row) },
                    modifier = if (entry.key == state.list.startRowKey) Modifier.focusRequester(startFocus) else Modifier
                )
            }
        }
    }
}

@Composable
private fun CalendarSectionHeader(entry: CalendarHeaderEntry, today: LocalDate, formats: CalendarFormats) {
    val text = when (entry.kind) {
        CalendarSectionKind.JUST_AIRED -> stringResource(R.string.calendar_section_just_aired)
        CalendarSectionKind.TODAY -> stringResource(R.string.calendar_today) + " · " + formats.shortDate(entry.date ?: today)
        CalendarSectionKind.TOMORROW -> stringResource(R.string.calendar_tomorrow) + " · " + formats.shortDate(entry.date ?: today.plusDays(1))
        CalendarSectionKind.DAY -> entry.date?.let(formats::longDate).orEmpty()
        CalendarSectionKind.LATER -> stringResource(R.string.calendar_section_later)
        CalendarSectionKind.NO_DATE -> stringResource(R.string.calendar_section_no_date)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(top = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.xxs)
    )
}

@Composable
internal fun CalendarRowCard(
    row: CalendarRow,
    formats: CalendarFormats,
    showDate: Boolean,
    onOpen: () -> Unit,
    onBell: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        Card(
            onClick = onOpen,
            modifier = modifier.weight(1f),
            colors = calendarCardColors(),
            border = calendarFocusBorder(shape),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1.01f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = row.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 48.dp, height = 72.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                        .background(NuvioTheme.colors.BackgroundElevated)
                )
                Spacer(Modifier.width(NuvioTheme.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = rowDetail(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = listOfNotNull(
                        rowWhen(row, formats, showDate),
                        labelText(row.label),
                        row.network
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (row.status == CalendarRowStatus.WATCHED) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.calendar_watched),
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                } else if (row.status == CalendarRowStatus.NOT_WATCHED) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(NuvioTheme.colors.Secondary))
                }
            }
        }
        if (row.status.hasBell) {
            val on = row.status == CalendarRowStatus.BELL_ON
            CalendarIconAction(
                icon = if (on) Icons.Default.NotificationsActive else Icons.Outlined.NotificationsNone,
                contentDescription = stringResource(if (on) R.string.calendar_bell_remove else R.string.calendar_bell_add),
                onClick = onBell,
                selected = on
            )
        }
        CalendarIconAction(
            icon = Icons.Default.VisibilityOff,
            contentDescription = stringResource(R.string.calendar_hide),
            onClick = onHide
        )
    }
}

@Composable
private fun rowDetail(row: CalendarRow): String = when (row.kind) {
    CalendarRowKind.EPISODE -> listOfNotNull(
        if (row.season != null && row.episode != null) stringResource(R.string.calendar_episode_code, row.season, row.episode) else null,
        row.episodeTitle
    ).joinToString(" · ")
    CalendarRowKind.FILM_CINEMA -> stringResource(R.string.calendar_film_cinema)
    CalendarRowKind.FILM_DIGITAL -> stringResource(R.string.calendar_film_digital)
    CalendarRowKind.SHOW -> stringResource(R.string.calendar_next_unknown)
    CalendarRowKind.GAME -> row.game?.league.orEmpty()
}

@Composable
private fun rowWhen(row: CalendarRow, formats: CalendarFormats, showDate: Boolean): String? {
    if (row.kind == CalendarRowKind.SHOW) return null
    val time = row.time?.let(formats::time)
        ?: if (row.kind == CalendarRowKind.EPISODE) stringResource(R.string.calendar_time_tba) else null
    val date = if (showDate) row.date?.let(formats::shortDate) else null
    return listOfNotNull(date, time).joinToString(" ").takeIf(String::isNotBlank)
}

@Composable
private fun labelText(label: CalendarEpisodeLabel): String? = when (label) {
    CalendarEpisodeLabel.NONE -> null
    CalendarEpisodeLabel.NEW_SHOW -> stringResource(R.string.calendar_label_new_show)
    CalendarEpisodeLabel.SEASON_PREMIERE -> stringResource(R.string.calendar_label_season_premiere)
    CalendarEpisodeLabel.MID_SEASON_PREMIERE -> stringResource(R.string.calendar_label_mid_season_premiere)
    CalendarEpisodeLabel.MID_SEASON_FINALE -> stringResource(R.string.calendar_label_mid_season_finale)
    CalendarEpisodeLabel.SEASON_FINALE -> stringResource(R.string.calendar_label_season_finale)
    CalendarEpisodeLabel.SERIES_FINALE -> stringResource(R.string.calendar_label_series_finale)
}

// ------------------------------------------------------------------ Month

@Composable
private fun CalendarMonthView(
    month: YearMonth,
    state: CalendarUiState,
    formats: CalendarFormats,
    onMonth: (YearMonth) -> Unit,
    viewModel: CalendarViewModel,
    onOpenTitle: (String, String) -> Unit,
    onWatchGame: () -> Unit
) {
    var focusedDay by remember(month) { mutableStateOf(if (YearMonth.from(state.today) == month) state.today else month.atDay(1)) }
    val grid = remember(month, formats.firstDayOfWeek) { calendarMonthGrid(month, formats.firstDayOfWeek) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
        Column(modifier = Modifier.weight(0.58f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                CalendarIconAction(Icons.Default.ChevronLeft, stringResource(R.string.calendar_month_previous), { onMonth(month.minusMonths(1)) })
                Text(
                    text = formats.month(month),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary
                )
                CalendarIconAction(Icons.Default.ChevronRight, stringResource(R.string.calendar_month_next), { onMonth(month.plusMonths(1)) })
            }
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            Row(Modifier.fillMaxWidth()) {
                grid.take(7).forEach { day ->
                    Text(
                        text = formats.weekday(day.dayOfWeek),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.weight(1f).padding(start = NuvioTheme.spacing.xs)
                    )
                }
            }
            grid.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    week.forEach { day ->
                        val count = state.days[day]?.size ?: 0
                        CalendarDayCell(
                            day = day,
                            inMonth = YearMonth.from(day) == month,
                            today = day == state.today,
                            count = count,
                            selected = day == focusedDay,
                            onFocused = { focusedDay = day },
                            onClick = { if (YearMonth.from(day) != month) onMonth(YearMonth.from(day)) else focusedDay = day },
                            modifier = Modifier.weight(1f).then(if (day == focusedDay) Modifier.focusRequester(firstFocus) else Modifier)
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
            Text(
                text = formats.longDate(focusedDay),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            val rows = state.days[focusedDay].orEmpty()
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.calendar_day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    items(rows, key = { it.key }) { row ->
                        if (row.kind == CalendarRowKind.GAME) CalendarGameRowCard(
                            row = row,
                            formats = formats,
                            showDate = false,
                            onWatch = { viewModel.watchGame(row); onWatchGame() }
                        ) else CalendarRowCard(
                            row = row,
                            formats = formats,
                            showDate = false,
                            onOpen = { row.contentId?.let { onOpenTitle(it, row.apiType) } },
                            onBell = { viewModel.toggleBell(row) },
                            onHide = { viewModel.hide(row) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    day: LocalDate,
    inMonth: Boolean,
    today: Boolean,
    count: Int,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Card(
        onClick = onClick,
        modifier = modifier.height(56.dp).onFocusChanged { if (it.isFocused) onFocused() },
        colors = calendarCardColors(selected = today),
        border = calendarFocusBorder(shape),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.06f)
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp)) {
            Text(
                text = day.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (today || selected) FontWeight.Bold else FontWeight.Normal,
                color = if (inMonth) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary
            )
            if (count > 0) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(count.coerceAtMost(3)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(NuvioTheme.colors.Secondary))
                    }
                    if (count > 3) {
                        Text("+${count - 3}", style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun CalendarSettingsPanel(state: CalendarUiState, viewModel: CalendarViewModel, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(0.7f),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
    ) {
        item {
            Text(stringResource(R.string.calendar_settings_window), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary)
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                CALENDAR_WINDOW_OPTIONS.forEachIndexed { index, days ->
                    CalendarChip(
                        label = stringResource(R.string.calendar_window_days, days),
                        selected = state.windowDays == days,
                        onClick = { viewModel.setWindowDays(days) },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            Text(stringResource(R.string.calendar_settings_zone), style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary)
            Text(stringResource(R.string.calendar_settings_zone_hint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                CALENDAR_ZONE_CHOICES.take(4).forEach { zone -> ZoneChip(zone, state.zoneOverride, viewModel) }
            }
            Spacer(Modifier.height(NuvioTheme.spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                CALENDAR_ZONE_CHOICES.drop(4).forEach { zone -> ZoneChip(zone, state.zoneOverride, viewModel) }
            }
        }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.calendar_settings_corner),
                subtitle = stringResource(R.string.calendar_settings_corner_hint),
                checked = state.cornerMessage,
                onToggle = { viewModel.setCornerMessage(!state.cornerMessage) }
            )
        }
        item {
            SettingsToggleRow(
                title = stringResource(R.string.calendar_settings_coming_up),
                subtitle = stringResource(R.string.calendar_settings_coming_up_hint),
                checked = state.comingUpRow,
                onToggle = { viewModel.setComingUpRow(!state.comingUpRow) }
            )
        }
        if (state.hiddenCount > 0) {
            item {
                SettingsActionRow(
                    title = stringResource(R.string.calendar_settings_unhide),
                    subtitle = stringResource(R.string.calendar_settings_unhide_hint, state.hiddenCount),
                    onClick = viewModel::unhideAll
                )
            }
        }
        item {
            CalendarChip(label = stringResource(R.string.calendar_settings_close), selected = false, onClick = onClose)
        }
    }
}

@Composable
private fun ZoneChip(zone: String?, current: String?, viewModel: CalendarViewModel) {
    CalendarChip(
        label = zone?.substringAfterLast('/')?.replace('_', ' ') ?: stringResource(R.string.calendar_zone_device),
        selected = zone == current,
        onClick = { viewModel.setZoneOverride(zone) }
    )
}

// ------------------------------------------------------------------ Sports

@Composable
private fun CalendarFilterRow(filters: Set<CalendarFilter>, onToggle: (CalendarFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        CalendarFilter.entries.forEach { filter ->
            CalendarChip(
                label = stringResource(
                    when (filter) {
                        CalendarFilter.SHOWS -> R.string.calendar_filter_shows
                        CalendarFilter.MOVIES -> R.string.calendar_filter_movies
                        CalendarFilter.SPORTS -> R.string.calendar_filter_sports
                    }
                ),
                selected = filter in filters,
                onClick = { onToggle(filter) }
            )
        }
    }
}

/**
 * A game: both teams with badges, league, kick-off in the Calendar zone; score + minute when LIVE, score + FT when
 * FINAL. OK on the row is "Watch now" when the game is live or within 15 min of kick-off; otherwise the row is
 * still focusable (so D-pad never skips it) and OK does nothing.
 */
@Composable
internal fun CalendarGameRowCard(
    row: CalendarRow,
    formats: CalendarFormats,
    showDate: Boolean,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val game = row.game ?: return
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        Card(
            onClick = { if (row.watchable) onWatch() },
            modifier = modifier.weight(1f),
            colors = calendarCardColors(),
            border = calendarFocusBorder(shape),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1.01f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TeamBadge(game.homeBadge ?: game.leagueBadge)
                Spacer(Modifier.width(NuvioTheme.spacing.xs))
                TeamBadge(game.awayBadge)
                Spacer(Modifier.width(NuvioTheme.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val kickoff = listOfNotNull(
                        if (showDate) row.date?.let(formats::shortDate) else null,
                        row.time?.let(formats::time)
                    ).joinToString(" ")
                    Text(
                        text = listOfNotNull(game.league, kickoff.takeIf(String::isNotBlank)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                gameStateText(game)?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (game.state == CalendarGameState.LIVE) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
        if (row.watchable) {
            CalendarChip(label = stringResource(R.string.calendar_watch_now), selected = true, onClick = onWatch)
        }
    }
}

@Composable
private fun TeamBadge(url: String?) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(NuvioTheme.radii.sm))
    )
}

@Composable
private fun gameStateText(game: CalendarGameItem): String? {
    val score = if (game.homeScore != null && game.awayScore != null) "${game.homeScore} – ${game.awayScore}" else null
    return when (game.state) {
        CalendarGameState.LIVE -> listOfNotNull(score, game.progress ?: stringResource(R.string.calendar_game_live)).joinToString(" · ")
        CalendarGameState.FINAL -> listOfNotNull(score, stringResource(R.string.calendar_game_final)).joinToString(" · ")
        CalendarGameState.POSTPONED -> stringResource(R.string.calendar_game_postponed)
        CalendarGameState.SCHEDULED -> null
    }
}

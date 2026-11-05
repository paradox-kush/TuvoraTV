// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.calendar.CalendarReminderCard
import com.nuvio.tv.core.calendar.CalendarReminderCenter
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * The Home reminder card, in the same floating slot as the announcement. It never takes focus by itself; Up from
 * the top row reaches it (HomeScreen routes Up to [primaryFocusRequester]).
 */
@Composable
fun CalendarReminderHomeCard(
    card: CalendarReminderCard,
    onOpenCalendar: () -> Unit,
    onDismiss: () -> Unit,
    primaryFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Column(
        modifier = modifier
            .width(460.dp)
            .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border.copy(alpha = 0.35f), shape)
            .background(NuvioTheme.colors.BackgroundElevated, shape)
            .padding(NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(20.dp))
            Text(
                text = if (card.items.size + card.more == 1) stringResource(R.string.calendar_reminder_card_one)
                else stringResource(R.string.calendar_reminder_card_many, card.items.size + card.more),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(start = NuvioTheme.spacing.sm)
            )
        }
        card.items.forEach { reminder ->
            val entry = reminder.entry
            val code = if (entry.season != null && entry.episode != null) {
                stringResource(R.string.calendar_episode_code, entry.season!!, entry.episode!!)
            } else null
            Text(
                text = listOfNotNull(reminder.title.name, code, entry.shownTitle).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (card.more > 0) {
            Text(
                text = stringResource(R.string.calendar_reminder_more, card.more),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            CalendarChip(
                label = stringResource(R.string.calendar_reminder_open),
                selected = false,
                onClick = {
                    onDismiss()
                    onOpenCalendar()
                },
                modifier = Modifier.focusRequester(primaryFocusRequester)
            )
            CalendarChip(
                label = stringResource(R.string.calendar_reminder_later),
                selected = false,
                onClick = {
                    // Hand focus to the rows before the card leaves the tree.
                    focusManager.moveFocus(FocusDirection.Down)
                    onDismiss()
                }
            )
        }
    }
}

/**
 * The corner note, for the whole app while it is in front. The check runs inside
 * `repeatOnLifecycle(RESUMED)`: it stops when the app goes to the background and can never stack (one per
 * composition), and each pass sleeps until the next reminder is due (1 min .. 6 h), reading only stored data.
 * [suppressed] hides it while a player is on screen. It is not focusable: it never steals D-pad focus.
 */
@Composable
fun BoxScope.CalendarCornerNoteHost(viewModel: CalendarReminderViewModel, suppressed: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(viewModel.checkCorner().toMillis())
            }
        }
    }
    val corner by viewModel.corner.collectAsStateWithLifecycle()
    val message = corner
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(CalendarReminderCenter.CORNER_SHOWN_MS)
            viewModel.dismissCorner(message.id)
        }
    }
    AnimatedVisibility(
        visible = message != null && !suppressed,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(NuvioTheme.spacing.xl)
    ) {
        val shown = message ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(NuvioTheme.radii.md)
        Box(
            modifier = Modifier
                .width(380.dp)
                .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border.copy(alpha = 0.35f), shape)
                .background(NuvioTheme.colors.BackgroundElevated, shape)
                .padding(NuvioTheme.spacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(22.dp))
                Column(Modifier.padding(start = NuvioTheme.spacing.sm)) {
                    Text(
                        text = if (shown.count == 1 && shown.names.size == 1) stringResource(R.string.calendar_corner_one, shown.names.single())
                        else stringResource(R.string.calendar_corner_many, shown.count),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.calendar_corner_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
    }
}

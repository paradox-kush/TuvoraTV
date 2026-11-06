package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.recordings.api.ConfirmationKind
import com.nuvio.tv.ui.screens.iptv.RecordConfirmationFocusPolicy.Row
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The confirmation after Record / Record series / Cancel, as a sheet that stays until the viewer acts
 * (a toast that vanished moved the cursor under their next OK). It says what will really happen: "set"
 * only when the wake-up is exact, "may start a little late" when it is not, a clash as a warning
 * (never a block). The cursor starts on Done; Undo is the last row and is never the default.
 */
@Composable
internal fun GuideRecordNoticeSheet(
    notice: GuideRecordNotice,
    offerExactTimes: Boolean,
    onDone: () -> Unit,
    onUndo: (GuideRecordNotice.Scheduled) -> Unit,
    onOpenExactSettings: () -> Unit,
    onViewRecordings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = notice.title
    val scheduled = notice as? GuideRecordNotice.Scheduled
    val message = when (notice) {
        is GuideRecordNotice.Cancelled -> stringResource(R.string.rec_notice_cancelled, title)
        is GuideRecordNotice.SeriesStopped -> stringResource(R.string.rec_notice_series_stopped, title)
        is GuideRecordNotice.Failed -> stringResource(R.string.rec_notice_failed, title)
        is GuideRecordNotice.Scheduled -> {
            val c = notice.confirmation
            val base = when (c.kind) {
                ConfirmationKind.SET ->
                    if (notice.isSeries) pluralStringResource(R.plurals.rec_notice_series_set, c.count, title, c.count)
                    else stringResource(R.string.rec_notice_set, title)
                ConfirmationKind.MAY_START_LATE ->
                    if (notice.isSeries) pluralStringResource(R.plurals.rec_notice_series_late, c.count, title, c.count)
                    else stringResource(R.string.rec_notice_late, title)
                ConfirmationKind.SAVES_ON_NEXT_OPEN -> stringResource(R.string.rec_notice_next_open, title)
                ConfirmationKind.ALREADY_SCHEDULED -> stringResource(R.string.rec_notice_already, title)
                ConfirmationKind.CANNOT_RECORD -> stringResource(R.string.rec_notice_cannot, title)
                ConfirmationKind.SERIES_NOTHING_YET -> stringResource(R.string.rec_notice_series_none, title)
            }
            if (c.clashTitles.isEmpty()) base
            else stringResource(R.string.rec_notice_clash, base, c.clashTitles.joinToString(", "))
        }
    }
    // Space is tight for the estimated size: say so as its own line (never blocks, never takes the cursor).
    val spaceTightNote = if (scheduled?.confirmation?.spaceTight == true) stringResource(R.string.rec_notice_space_tight) else null
    val rows = RecordConfirmationFocusPolicy.rows(
        undoable = scheduled != null && RecordConfirmationFocusPolicy.undoable(scheduled.confirmation.kind, scheduled.isSeries),
        offerExact = scheduled != null && offerExactTimes,
        canViewRecordings = scheduled != null,
    )
    val defaultRow = RecordConfirmationFocusPolicy.defaultFocus(rows)
    val defaultFocus = remember(notice) { FocusRequester() }
    val latestDone by rememberUpdatedState(onDone)
    BackHandler { latestDone() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(NuvioTheme.colors.BackgroundElevated)
                .padding(NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
            )
            spaceTightNote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
                )
            }
            rows.forEach { row ->
                val rowModifier = Modifier.fillMaxWidth()
                val focus = defaultFocus.takeIf { row == defaultRow }
                when (row) {
                    Row.DONE -> GuideSheetButton(
                        label = stringResource(R.string.rec_notice_done),
                        primary = true, focusRequester = focus, modifier = rowModifier, onClick = onDone,
                    )
                    Row.VIEW_RECORDINGS -> GuideSheetButton(
                        label = stringResource(R.string.rec_notice_view),
                        primary = false, focusRequester = focus, modifier = rowModifier, onClick = onViewRecordings,
                    )
                    Row.ALLOW_EXACT -> GuideSheetButton(
                        label = stringResource(R.string.rec_notice_allow_exact),
                        primary = false, focusRequester = focus, modifier = rowModifier, onClick = onOpenExactSettings,
                    )
                    Row.UNDO -> GuideSheetButton(
                        label = stringResource(R.string.rec_notice_undo),
                        primary = false, focusRequester = focus, modifier = rowModifier,
                        onClick = { scheduled?.let(onUndo) },
                    )
                }
            }
        }
    }
    LaunchedEffect(notice) {
        GuideFocusRequestLoop.requestUntilLanded { defaultFocus.requestFocusOrFalse() }
    }
}

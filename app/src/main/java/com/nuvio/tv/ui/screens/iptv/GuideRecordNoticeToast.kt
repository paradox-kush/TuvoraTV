package com.nuvio.tv.ui.screens.iptv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.recordings.api.ConfirmationKind
import com.nuvio.tv.ui.components.NuvioUndoToast

private const val NOTICE_MS = 8_000L

/**
 * The confirmation after Record / Record series / Cancel. It says what will really happen: "set" only
 * when the wake-up is exact, "may start a little late" when it is not, a clash as a warning (never a
 * block). Its one button is Undo, "Allow exact times" (first inexact schedule only), or OK.
 */
@Composable
internal fun GuideRecordNoticeToast(
    notice: GuideRecordNotice,
    onUndo: (GuideRecordNotice.Scheduled) -> Unit,
    onOpenExactSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = notice.title
    var action = GuideRecordSheetPolicy.NoticeAction.DISMISS
    val message = when (notice) {
        is GuideRecordNotice.Cancelled -> stringResource(R.string.rec_notice_cancelled, title)
        is GuideRecordNotice.Failed -> stringResource(R.string.rec_notice_failed, title)
        is GuideRecordNotice.Scheduled -> {
            val c = notice.confirmation
            action = GuideRecordSheetPolicy.noticeAction(c, notice.isSeries)
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
    val label = stringResource(
        when (action) {
            GuideRecordSheetPolicy.NoticeAction.UNDO -> R.string.rec_notice_undo
            GuideRecordSheetPolicy.NoticeAction.OPEN_EXACT_SETTINGS -> R.string.rec_notice_allow_exact
            GuideRecordSheetPolicy.NoticeAction.DISMISS -> R.string.rec_ok
        },
    )
    NuvioUndoToast(
        message = message,
        actionLabel = label,
        onAction = {
            when (action) {
                GuideRecordSheetPolicy.NoticeAction.UNDO -> (notice as? GuideRecordNotice.Scheduled)?.let(onUndo)
                GuideRecordSheetPolicy.NoticeAction.OPEN_EXACT_SETTINGS -> { onOpenExactSettings(); onDismiss() }
                GuideRecordSheetPolicy.NoticeAction.DISMISS -> onDismiss()
            }
        },
        onDismiss = onDismiss,
        durationMillis = NOTICE_MS,
        key = notice,
        modifier = modifier,
    )
}

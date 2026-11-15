package com.nuvio.tv.ui.screens.multiview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.multiview.GridHint
import com.nuvio.tv.core.multiview.MultiviewNotice
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The one-line hint bar: what OK, Hold OK and Back do for the focused tile. Not focusable. It shares the label strip's
 * 5 s auto-hide (the caller fades it), and is not shown while a panel is open.
 */
@Composable
internal fun GridHintBar(hint: GridHint, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hint.soundKeptOn != null) {
            Text(
                text = stringResource(R.string.multiview_hint_sound_kept_on, hint.soundKeptOn),
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (hint.kind) {
            GridHint.Kind.PLAYING -> {
                HintKey(R.string.multiview_key_ok, stringResource(R.string.multiview_hint_full_screen))
                HintKey(R.string.multiview_key_hold_ok, stringResource(R.string.multiview_hint_options))
                HintKey(R.string.multiview_key_back, stringResource(R.string.multiview_hint_leave))
            }
            GridHint.Kind.EMPTY -> {
                HintKey(R.string.multiview_key_ok, stringResource(R.string.multiview_menu_add_channel))
                HintKey(R.string.multiview_key_hold_ok, stringResource(R.string.multiview_hint_options))
                HintKey(R.string.multiview_key_back, stringResource(R.string.multiview_hint_leave))
            }
            GridHint.Kind.FAILED -> {
                hint.failureOk?.let { HintKey(R.string.multiview_key_ok, stringResource(it.labelRes())) }
                HintKey(R.string.multiview_key_hold_ok, stringResource(R.string.multiview_hint_more))
                HintKey(R.string.multiview_key_back, stringResource(R.string.multiview_hint_leave))
            }
            GridHint.Kind.FULLSCREEN -> {
                HintKey(R.string.multiview_key_back, stringResource(R.string.multiview_hint_your_grid))
                HintKey(R.string.multiview_key_ok, stringResource(R.string.multiview_hint_options))
            }
        }
    }
}

@Composable
private fun HintKey(keyRes: Int, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyCap(stringResource(keyRes))
        Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
}

/** The one notice line, top centre: `BackgroundElevated`, 1 dp `Border`, 14 sp. Never focusable, so the D-pad keeps moving. */
@Composable
internal fun NoticeLine(notice: MultiviewNotice, modifier: Modifier = Modifier) {
    val text = when (notice) {
        is MultiviewNotice.Capacity -> stringResource(R.string.multiview_capacity_notice, notice.about)
        is MultiviewNotice.PickFailed -> stringResource(R.string.multiview_notice_pick_failed, notice.name, notice.reason)
        is MultiviewNotice.CannotAdd -> stringResource(R.string.multiview_notice_cannot_add, notice.name)
        is MultiviewNotice.AlreadyOnScreen -> stringResource(R.string.multiview_notice_on_screen, notice.name)
        MultiviewNotice.LeaveGuard -> stringResource(R.string.multiview_notice_leave_guard)
    }
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Text(
        text = text,
        fontSize = 14.sp,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 2,
        modifier = modifier
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(1.dp, NuvioTheme.colors.Border, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

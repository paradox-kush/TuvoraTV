package com.nuvio.tv.ui.screens.iptv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.tv.core.multiview.QueueResult
import com.nuvio.tv.ui.components.NuvioUndoToast

/**
 * The "Add to Multiview" notice. One place decides which kind it is, from [QueueResult.offersOpenNow]:
 *  - Added: [MultiviewPassiveNotice]. No button, no focus; the channel row it came from keeps D-pad focus.
 *  - AlreadyQueued / Full: a focusable [NuvioUndoToast] with "Open now", so a duplicate or a full queue is
 *    never a dead end. It owns focus, so on any dismissal (arrows, BACK, timeout) [onRestoreFocus] hands it back.
 */
@Composable
fun MultiviewQueueNotice(
    result: QueueResult,
    message: String,
    openLabel: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onRestoreFocus: () -> Unit,
    durationMillis: Long,
    modifier: Modifier = Modifier,
) {
    if (!result.offersOpenNow) {
        MultiviewPassiveNotice(message = message, onDismiss = onDismiss, durationMillis = durationMillis, key = result, modifier = modifier)
        return
    }
    NuvioUndoToast(
        message = message,
        actionLabel = openLabel,
        onAction = {
            onDismiss()
            onOpen()
        },
        onDismiss = {
            onRestoreFocus()
            onDismiss()
        },
        durationMillis = durationMillis,
        key = result,
        modifier = modifier,
    )
}

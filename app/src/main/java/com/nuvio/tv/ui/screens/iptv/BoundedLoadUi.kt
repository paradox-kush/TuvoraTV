package com.nuvio.tv.ui.screens.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.LoadStatus
import kotlinx.coroutines.delay

/**
 * [status] as the screen should show it now: a [LoadStatus.Loading] past its own deadline reads as
 * [LoadStatus.Failed] — the guarantee that a wait ends even if the work behind it never returns. Wakes once,
 * at the deadline (no polling), and only while this composable is on screen.
 */
@Composable
fun rememberEffectiveLoadStatus(status: LoadStatus): LoadStatus {
    var now by remember { mutableLongStateOf(BoundedLoad.clock()) }
    LaunchedEffect(status) {
        if (status is LoadStatus.Loading) {
            val wait = status.deadlineAtMs - BoundedLoad.clock()
            if (wait > 0) delay(wait)
            now = BoundedLoad.clock()
        }
    }
    return BoundedLoad.effectiveAt(status, now)
}

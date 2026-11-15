package com.nuvio.tv.ui.screens.iptv

import androidx.compose.runtime.withFrameNanos

/**
 * Lands a one-shot focus intent (entry focus, a group opened with OK / RIGHT, "Browse all channels").
 *
 * Device pass 1 (2026-11-15), findings 1 and 2: each landing used to retire its intent FIRST and ask for focus
 * after. The intent is a key of the very LaunchedEffect doing the asking, so retiring it recomposed and cancelled
 * that effect before its first ask landed (always, when the rows were composed in the same frame the effect
 * started); focus stayed wherever a disposed row had dropped it, the Live TV tab. Now the intent is retired only
 * once the ask has run. A landing cancelled mid-flight (its list changed, the viewer pressed a key) keeps the
 * intent, so a relaunched effect lands it, and a key press that cleared the intent stops it for good.
 * Pure apart from the injected frame wait, so it tests without Compose.
 */
internal object GuidePendingFocusLanding {
    suspend fun land(
        request: () -> Boolean,
        fallback: () -> Boolean,
        retire: () -> Unit,
        maxFrames: Int = 90,
        minimumAsks: Int = 3,
        awaitFrame: suspend () -> Unit = { withFrameNanos { } },
    ): Boolean {
        val landed = GuideFocusRequestLoop.requestUntilLanded(maxFrames, minimumAsks, awaitFrame, request)
        if (!landed) fallback()
        retire()
        return landed
    }
}

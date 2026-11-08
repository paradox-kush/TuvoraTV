package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.analytics.LivePlaybackFreezeReporter
import com.nuvio.tv.core.analytics.PlaybackBreadcrumbIdentity

/**
 * Called from each first-frame path only — never while a source is resolving. A repeat (a seek's
 * re-rendered first frame, a reconnect) with the same identity is the same viewing and no-ops.
 * The container is a code derived from the URL path; the URL itself never leaves this function.
 */
internal fun PlayerRuntimeController.recordPlaybackOutputStarted() {
    playbackBreadcrumbs.onOutputStarted(
        PlaybackBreadcrumbIdentity(
            kind = if (_uiState.value.isLive) {
                PlaybackBreadcrumbIdentity.KIND_LIVE
            } else {
                PlaybackBreadcrumbIdentity.KIND_VOD
            },
            engine = if (isUsingMpvEngine()) {
                PlaybackBreadcrumbIdentity.ENGINE_LIBMPV
            } else {
                PlaybackBreadcrumbIdentity.ENGINE_MEDIA3
            },
            surface = PlaybackBreadcrumbIdentity.SURFACE_FULLSCREEN,
            container = LivePlaybackFreezeReporter.streamContainerOf(currentStreamUrl),
        ),
    )
}

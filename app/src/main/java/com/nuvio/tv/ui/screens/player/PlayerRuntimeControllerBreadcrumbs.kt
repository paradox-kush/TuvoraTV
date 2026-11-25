package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.analytics.LivePlaybackFreezeReporter
import com.nuvio.tv.core.analytics.PlaybackBreadcrumbIdentity

/**
 * Called from each first-frame path only — never while a source is resolving. A repeat (a seek's
 * re-rendered first frame, a reconnect) with the same identity is the same viewing and no-ops.
 * The container is a code derived from the URL path; the URL itself never leaves this function.
 */
internal fun PlayerRuntimeController.recordPlaybackOutputStarted() {
    reportTimeToFirstFrame()
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

/**
 * Playback telemetry: the load attempt's time from request to the first rendered frame (this player's existing
 * first-frame paths all call [recordPlaybackOutputStarted]). Counted once per load attempt into `session_health`
 * as `ttff:<kind>:<engine>:<bucket>`; a seek's re-rendered frame or a reconnect on the same attempt is not a new start.
 * An engine switch begins a new attempt (the load diagnostics reset), so its time is that of the engine that played.
 */
private fun PlayerRuntimeController.reportTimeToFirstFrame() {
    val startedAt = loadingDiagnosticsStartedAtMs
    if (startedAt <= 0L || ttffReportedForLoadAtMs == startedAt) return
    ttffReportedForLoadAtMs = startedAt
    com.nuvio.tv.core.telemetry.playback.PlaybackTelemetry.ttff(
        kind = if (_uiState.value.isLive) {
            com.nuvio.tv.core.telemetry.playback.PlayKind.LIVE
        } else {
            com.nuvio.tv.core.telemetry.playback.PlayKind.VOD
        },
        engine = if (isUsingMpvEngine()) {
            com.nuvio.tv.core.telemetry.playback.PlayEngine.LIBMPV
        } else {
            com.nuvio.tv.core.telemetry.playback.PlayEngine.MEDIA3
        },
        ms = android.os.SystemClock.elapsedRealtime() - startedAt,
    )
}

/**
 * Playback telemetry: this player moved to the other engine. [byUser] is a switch the viewer asked for (OSD, error
 * dialog); otherwise the trigger comes from the last failure's name (an enum out, the name is never kept).
 */
internal fun PlayerRuntimeController.reportEngineFallback(
    targetEngine: com.nuvio.tv.data.local.InternalPlayerEngine,
    byUser: Boolean,
) {
    val from = if (isUsingMpvEngine()) {
        com.nuvio.tv.core.telemetry.playback.PlayEngine.LIBMPV
    } else {
        com.nuvio.tv.core.telemetry.playback.PlayEngine.MEDIA3
    }
    val to = when (targetEngine) {
        com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER -> com.nuvio.tv.core.telemetry.playback.PlayEngine.LIBMPV
        com.nuvio.tv.data.local.InternalPlayerEngine.EXOPLAYER -> com.nuvio.tv.core.telemetry.playback.PlayEngine.MEDIA3
        com.nuvio.tv.data.local.InternalPlayerEngine.AUTO -> return
    }
    if (from == to) return
    com.nuvio.tv.core.telemetry.playback.PlaybackTelemetry.fallback(
        from = from,
        to = to,
        trigger = if (byUser) {
            com.nuvio.tv.core.telemetry.playback.FallbackTrigger.USER
        } else {
            com.nuvio.tv.core.telemetry.playback.PlaybackTelemetryKeys.triggerForFailureName(lastPlaybackFailureCode)
        },
    )
}

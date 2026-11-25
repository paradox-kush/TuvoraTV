package com.nuvio.tv.playback.wiring

import com.nuvio.tv.core.telemetry.playback.FallbackTrigger
import com.nuvio.tv.core.telemetry.playback.PlayEngine
import com.nuvio.tv.core.telemetry.playback.PlayKind
import com.nuvio.tv.core.telemetry.playback.PlaybackTelemetry
import com.nuvio.tv.core.telemetry.playback.PlaybackTelemetryKeys
import com.nuvio.tv.core.telemetry.playback.StallOutcome
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PlaybackSignalKind
import com.nuvio.tv.playback.core.PlaybackSignalSink

/**
 * Maps the clean pipeline's playback signals onto the playback telemetry counts. Only enum values and
 * numbers cross: no URL, host, channel or message.
 */
object PlaybackTelemetrySignals : PlaybackSignalSink {
    override fun firstFrame(kind: PlaybackSignalKind, engine: EngineType?, ms: Long) =
        PlaybackTelemetry.ttff(kind.toPlayKind(), engine.toPlayEngine(), ms)

    override fun handoff(from: EngineType, to: EngineType, cause: FailureCode?) =
        PlaybackTelemetry.fallback(from.toPlayEngine(), to.toPlayEngine(), triggerOf(cause))

    override fun stall(kind: PlaybackSignalKind, recovered: Boolean, ms: Long) =
        PlaybackTelemetry.stall(
            kind.toPlayKind(),
            if (recovered) StallOutcome.RECOVERED else StallOutcome.GAVE_UP,
            ms,
        )

    override fun teardown(engine: EngineType, durationMs: Long, failed: Boolean) =
        PlaybackTelemetry.teardown(engine.toPlayEngine(), durationMs, failed)

    internal fun triggerOf(cause: FailureCode?): FallbackTrigger = PlaybackTelemetryKeys.triggerForFailureName(cause?.name)
}

internal fun PlaybackSignalKind.toPlayKind(): PlayKind = when (this) {
    PlaybackSignalKind.LIVE -> PlayKind.LIVE
    PlaybackSignalKind.VOD -> PlayKind.VOD
    PlaybackSignalKind.PREVIEW -> PlayKind.PREVIEW
    PlaybackSignalKind.MULTIVIEW_TILE -> PlayKind.MULTIVIEW_TILE
}

internal fun EngineType?.toPlayEngine(): PlayEngine = when (this) {
    EngineType.MEDIA3 -> PlayEngine.MEDIA3
    EngineType.LIBMPV -> PlayEngine.LIBMPV
    null -> PlayEngine.OTHER
}

package com.nuvio.tv.playback.live

import com.nuvio.tv.core.analytics.PlaybackBreadcrumbIdentity
import com.nuvio.tv.core.analytics.PlaybackBreadcrumbPhase
import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.StreamEvidence

/** Maps the clean pipeline's [PlaybackSnapshot] onto breadcrumb pairing inputs. Pure. */
object LivePlaybackBreadcrumbs {

    fun phaseOf(snapshot: PlaybackSnapshot): PlaybackBreadcrumbPhase = when (snapshot.state) {
        PlaybackState.PLAYING, PlaybackState.DEGRADED ->
            if (snapshot.progress.renderedVideoFrame || snapshot.progress.renderedAudio) {
                PlaybackBreadcrumbPhase.OUTPUT
            } else {
                PlaybackBreadcrumbPhase.TRANSIENT
            }
        PlaybackState.STOPPED, PlaybackState.FAILED, PlaybackState.RELEASING -> PlaybackBreadcrumbPhase.ENDED
        else -> PlaybackBreadcrumbPhase.TRANSIENT
    }

    fun identityOf(
        snapshot: PlaybackSnapshot,
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
    ): PlaybackBreadcrumbIdentity = PlaybackBreadcrumbIdentity(
        kind = if (selection.contentType == ContentType.LIVE) {
            PlaybackBreadcrumbIdentity.KIND_LIVE
        } else {
            PlaybackBreadcrumbIdentity.KIND_VOD
        },
        engine = when (snapshot.graph?.engine) {
            EngineType.MEDIA3 -> PlaybackBreadcrumbIdentity.ENGINE_MEDIA3
            EngineType.LIBMPV -> PlaybackBreadcrumbIdentity.ENGINE_LIBMPV
            null -> PlaybackBreadcrumbIdentity.ENGINE_UNKNOWN
        },
        surface = if (profile == SessionProfile.FULLSCREEN) {
            PlaybackBreadcrumbIdentity.SURFACE_FULLSCREEN
        } else {
            PlaybackBreadcrumbIdentity.SURFACE_PREVIEW
        },
        container = containerCodeOf(selection.declaredEvidence),
    )

    /** Same codes as [com.nuvio.tv.core.analytics.LivePlaybackFreezeReporter.streamContainerOf]. */
    fun containerCodeOf(evidence: StreamEvidence): String {
        when (evidence.container?.value) {
            ContainerType.MPEG_TS -> return "ts"
            ContainerType.MP4, ContainerType.FMP4 -> return "mp4"
            ContainerType.MATROSKA -> return "mkv"
            ContainerType.WEBM -> return "webm"
            else -> Unit
        }
        return when (evidence.delivery?.value) {
            DeliveryType.HLS -> "m3u8"
            DeliveryType.DASH -> "mpd"
            DeliveryType.RAW_TRANSPORT_STREAM -> "ts"
            null, DeliveryType.UNKNOWN -> PlaybackBreadcrumbIdentity.CONTAINER_UNKNOWN
            else -> "other"
        }
    }
}

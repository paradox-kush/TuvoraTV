package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.android.AndroidPlaybackQuirkOverride
import com.nuvio.tv.playback.android.AndroidRuntimeCapabilitySnapshot
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.PlaybackEnvironmentSnapshot
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.settings.MpvOutputPreference
import com.nuvio.tv.playback.settings.ResolvedPlaybackPreferences

internal data class PlaybackEnvironmentMappingInput(
    val preferences: ResolvedPlaybackPreferences,
    val android: AndroidRuntimeCapabilitySnapshot,
    val profile: SessionProfile,
    val resourceBudget: ResourceBudget = ResourceBudget(),
    val previewViewport: VideoDimensions? = null,
    val baseEligibleEngines: Set<EngineType> = EngineType.entries.toSet(),
    val baseAllowedSurfaceModes: Set<SurfaceMode> = SurfaceMode.entries.toSet(),
    /** Supplied by request/DRM evidence. This mapper never infers secure output from device identity. */
    val secureOutputRequired: Boolean,
)

/** Pure WP3 wiring from resolved intent and observed Android facts to the core environment contract. */
internal object PlaybackEnvironmentSnapshotMapper {
    fun map(input: PlaybackEnvironmentMappingInput): PlaybackEnvironmentSnapshot {
        val effectiveEngine = input.preferences.engine.effective?.toEngineType()
        val explicitWithoutFallback =
            input.preferences.requested.playback.engine != EnginePreference.AUTO &&
                input.preferences.automaticFallback.effective == false
        val eligibleEngines = if (explicitWithoutFallback) {
            input.baseEligibleEngines.intersect(effectiveEngine?.let(::setOf).orEmpty())
        } else {
            input.baseEligibleEngines
        }

        val supportedSurfaces = supportedSurfaces(input.android)
        val baseSurfaces = input.baseAllowedSurfaceModes.intersect(supportedSurfaces)
        val media3Surfaces = baseSurfaces.intersect(MEDIA3_SURFACES)
        val libmpvSurfaces = baseSurfaces.intersect(
            when (input.preferences.effective.expert.mpvOutput) {
                MpvOutputPreference.AUTO -> LIBMPV_SURFACES
                MpvOutputPreference.DIRECT -> setOf(SurfaceMode.NATIVE_EMBED)
                MpvOutputPreference.RENDER -> setOf(SurfaceMode.GPU_RENDER)
            },
        )
        var allowedSurfaces = if (explicitWithoutFallback) {
            when (effectiveEngine) {
                EngineType.MEDIA3 -> media3Surfaces
                EngineType.LIBMPV -> libmpvSurfaces
                null -> emptySet()
            }
        } else {
            media3Surfaces + libmpvSurfaces
        }

        // A forced surface pins the engine family that owns it (the AFTKM guide quirk is about Media3's
        // black SurfaceView). It must never strip the OTHER family's surfaces: on a 1.6 GB box that left
        // Media3 as the only guide graph, so a failed Media3 start (HTTP 456) had nowhere to hand off
        // to and read "this device cannot play this stream" (rejected_by=HANDOFF_EXCLUDED).
        input.android.forcedSurface(input.profile)?.let { forced ->
            allowedSurfaces = when (forced) {
                in MEDIA3_SURFACES -> allowedSurfaces.filter { it !in MEDIA3_SURFACES || it == forced }.toSet()
                else -> allowedSurfaces.filter { it !in LIBMPV_SURFACES || it == forced }.toSet()
            }
        }

        return PlaybackEnvironmentSnapshot(
            runtimeCapabilities = input.android.capabilities,
            resourceBudget = input.resourceBudget,
            previewViewport = input.previewViewport,
            eligibleEngines = eligibleEngines,
            preferredEngineOrder = effectiveEngine?.takeIf(eligibleEngines::contains)?.let(::listOf).orEmpty(),
            allowedSurfaceModes = allowedSurfaces,
            secureOutputRequired = input.secureOutputRequired,
        )
    }

    private fun supportedSurfaces(snapshot: AndroidRuntimeCapabilitySnapshot): Set<SurfaceMode> = buildSet {
        val surfaces = snapshot.capabilities.surfaces
        if (surfaces.surfaceViewSupported) add(SurfaceMode.SURFACE_VIEW)
        if (surfaces.textureViewSupported) add(SurfaceMode.TEXTURE_VIEW)
        if (surfaces.nativeEmbedSupported) add(SurfaceMode.NATIVE_EMBED)
        if (surfaces.gpuRenderingSupported) add(SurfaceMode.GPU_RENDER)
    }

    private fun AndroidRuntimeCapabilitySnapshot.forcedSurface(
        profile: SessionProfile,
    ): SurfaceMode? = appliedQuirks.firstNotNullOfOrNull { applied ->
        val override = applied.quirk.override as? AndroidPlaybackQuirkOverride.ForceEmbeddedSurface
            ?: return@firstNotNullOfOrNull null
        override.surfaceMode.takeIf { override.profile == profile }
    }

    private fun EnginePreference.toEngineType(): EngineType? = when (this) {
        EnginePreference.AUTO -> null
        EnginePreference.MEDIA3 -> EngineType.MEDIA3
        EnginePreference.LIBMPV -> EngineType.LIBMPV
    }

    private val MEDIA3_SURFACES = setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW)
    private val LIBMPV_SURFACES = setOf(SurfaceMode.NATIVE_EMBED, SurfaceMode.GPU_RENDER)
}

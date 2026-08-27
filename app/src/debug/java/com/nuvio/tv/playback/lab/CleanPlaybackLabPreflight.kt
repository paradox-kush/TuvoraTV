package com.nuvio.tv.playback.lab

import com.nuvio.tv.playback.android.AndroidPlaybackQuirkOverride
import com.nuvio.tv.playback.android.AndroidRuntimeCapabilitySnapshot
import com.nuvio.tv.playback.core.DefaultPlaybackRequirementsResolver
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackPolicy
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.PlaybackRequirementsInput
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.settings.CleanPlaybackPreferences
import com.nuvio.tv.playback.settings.MpvOutputPreference
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolutionContext
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolver
import com.nuvio.tv.playback.wiring.PlaybackEnvironmentMappingInput
import com.nuvio.tv.playback.wiring.PlaybackEnvironmentSnapshotMapper

internal sealed interface LabEnginePreflight {
    val reason: LabEligibilityReason

    data class Eligible(
        val graph: PlaybackGraph,
        val requirements: PlaybackRequirements,
        override val reason: LabEligibilityReason,
    ) : LabEnginePreflight

    data class Ineligible(override val reason: LabEligibilityReason) : LabEnginePreflight
}

internal suspend fun preflightLabEngine(
    fixture: SelectedDebugFixture,
    intent: LabPlaybackIntent,
    android: AndroidRuntimeCapabilitySnapshot,
    viewport: VideoDimensions,
    engine: EngineType,
): LabEnginePreflight {
    staticEligibility(fixture, engine)?.let { return LabEnginePreflight.Ineligible(it) }
    val requested = CleanPlaybackPreferences.recommended().let { defaults ->
        defaults.copy(
            playback = defaults.playback.copy(
                engine = if (engine == EngineType.MEDIA3) EnginePreference.MEDIA3 else EnginePreference.LIBMPV,
                automaticFallback = false,
                // The two engine cases intentionally carry the same GUIDE/PREVIEW semantics.
                subtitles = defaults.playback.subtitles.copy(enabled = false),
            ),
            expert = defaults.expert.copy(
                mpvOutput = if (engine == EngineType.LIBMPV) {
                    MpvOutputPreference.DIRECT
                } else {
                    defaults.expert.mpvOutput
                },
            ),
        )
    }
    val resolved = PlaybackPreferenceResolver.resolve(
        requested,
        PlaybackPreferenceResolutionContext(
            request = intent.request.summary(),
            evidence = intent.evidence,
            capabilities = android.capabilities,
            eligibleEngines = setOf(engine),
        ),
    )
    if (resolved.engine.effective == null) {
        return LabEnginePreflight.Ineligible(LabEligibilityReason.PREFERENCE_REJECTED)
    }
    val labAndroid = if (engine == EngineType.LIBMPV) {
        android.copy(
            capabilities = android.capabilities.copy(
                surfaces = android.capabilities.surfaces.copy(nativeEmbedSupported = true),
            ),
            // This Media3-only quirk cannot describe libmpv's NATIVE_EMBED output.
            appliedQuirks = android.appliedQuirks.filterNot { applied ->
                applied.quirk.override is AndroidPlaybackQuirkOverride.ForceEmbeddedSurface
            },
        )
    } else {
        android
    }
    val environment = PlaybackEnvironmentSnapshotMapper.map(
        PlaybackEnvironmentMappingInput(
            preferences = resolved,
            android = labAndroid,
            profile = intent.profile,
            previewViewport = viewport,
            baseEligibleEngines = setOf(engine),
            baseAllowedSurfaceModes = if (engine == EngineType.MEDIA3) {
                setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW)
            } else {
                setOf(SurfaceMode.NATIVE_EMBED)
            },
            secureOutputRequired = false,
        ),
    )
    val requirements = when (
        val result = DefaultPlaybackRequirementsResolver().resolve(
            PlaybackRequirementsInput(
                requestSummary = intent.request.summary(),
                evidence = intent.evidence,
                profile = intent.profile,
                effectivePreferences = resolved.effective.playback,
                environment = environment,
            ),
        )
    ) {
        is PlaybackResult.Failure -> {
            return LabEnginePreflight.Ineligible(LabEligibilityReason.REQUIREMENTS_REJECTED)
        }
        is PlaybackResult.Success -> result.value
    }
    val candidates = if (engine == EngineType.MEDIA3) {
        media3LabCandidates(requirements)
    } else {
        mpvLabCandidates(requirements)
    }
    return when (
        val selection = PlaybackPolicy().selectPrimary(
            PlaybackPolicy.SelectionInput(requirements, candidates),
        )
    ) {
        is PlaybackPolicy.Selection.Rejected -> {
            LabEnginePreflight.Ineligible(LabEligibilityReason.NO_ELIGIBLE_GRAPH)
        }
        is PlaybackPolicy.Selection.Selected -> LabEnginePreflight.Eligible(
            graph = selection.graph,
            requirements = requirements,
            reason = successfulLabEligibilityReason(intent, engine),
        )
    }
}

internal fun successfulLabEligibilityReason(
    intent: LabPlaybackIntent,
    engine: EngineType,
): LabEligibilityReason = if (
    engine == EngineType.LIBMPV &&
    intent.request.dnsPolicy == DnsPolicy.SHARED_APPLICATION_RESOLVER
) {
    LabEligibilityReason.SYSTEM_DNS_FALLBACK
} else {
    LabEligibilityReason.ELIGIBLE
}

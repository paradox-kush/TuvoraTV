package com.nuvio.tv.playback.core

/** The single deterministic policy consumes already-resolved requirements, never raw platform state. */
class PlaybackPolicy(
    private val watchdogConfiguration: WatchdogConfiguration = WatchdogConfiguration(),
    private val liveReconnectConfiguration: LiveReconnectConfiguration = LiveReconnectConfiguration(),
) {
    /** Fleet-derived anti-hammer guardrails; successes must remain stable before pressure resets. */
    data class LiveReconnectConfiguration(
        val maxAttemptsPerIncident: Int = 3,
        val minimumStablePlaybackBeforeResetMs: Long = 30_000L,
        val rollingWindowMs: Long = 120_000L,
        val maxAttemptsPerRollingWindow: Int = 6,
    ) {
        init {
            require(maxAttemptsPerIncident > 0)
            require(minimumStablePlaybackBeforeResetMs > 0)
            require(rollingWindowMs > 0)
            require(maxAttemptsPerRollingWindow >= maxAttemptsPerIncident)
        }
    }
    /** Bootstrap values from the approved adaptive architecture; telemetry may tune them later. */
    data class WatchdogConfiguration(
        val enabled: Boolean = true,
        val liveBytesToTracksMs: Long = 1_500L,
        val vodBytesToTracksMs: Long = 3_000L,
        // The reference table starts its next budget at renderer-ready. A silent codec-init wedge
        // has no such callback, so v1 conservatively uses the same live/VOD bound for that gap.
        val liveTracksToReadyMs: Long = 1_000L,
        val vodTracksToReadyMs: Long = 2_000L,
        val liveReadyToFirstFrameMs: Long = 1_000L,
        val vodReadyToFirstFrameMs: Long = 2_000L,
        val runtimeVideoProgressWindowMs: Long = 5_000L,
        val surfaceAttachmentProgressMs: Long = 3_000L,
    ) {
        init {
            if (enabled) {
                require(liveBytesToTracksMs > 0)
                require(vodBytesToTracksMs > 0)
                require(liveTracksToReadyMs > 0)
                require(vodTracksToReadyMs > 0)
                require(liveReadyToFirstFrameMs > 0)
                require(vodReadyToFirstFrameMs > 0)
                require(runtimeVideoProgressWindowMs > 0)
                require(surfaceAttachmentProgressMs > 0)
            }
        }
    }

    enum class WatchdogPhase {
        WAITING_FOR_SURFACE,
        FIRST_MEDIA_BYTE,
        BYTES_TO_TRACKS,
        VIDEO_TRACKS_TO_READY,
        READY_TO_FIRST_VIDEO_FRAME,
        RUNTIME_VIDEO_PROGRESS,
    }

    fun watchdogEnabled(): Boolean = watchdogConfiguration.enabled

    fun watchdogDelayMs(
        phase: WatchdogPhase,
        contentType: ContentType,
        network: PlaybackNetworkRequest,
    ): Long = when (phase) {
        WatchdogPhase.WAITING_FOR_SURFACE -> watchdogConfiguration.surfaceAttachmentProgressMs
        WatchdogPhase.FIRST_MEDIA_BYTE -> minOf(
            network.readTimeoutMs.toLong(),
            network.callTimeoutMs?.toLong() ?: Long.MAX_VALUE,
        )
        WatchdogPhase.BYTES_TO_TRACKS -> if (contentType == ContentType.LIVE) {
            watchdogConfiguration.liveBytesToTracksMs
        } else {
            watchdogConfiguration.vodBytesToTracksMs
        }
        WatchdogPhase.VIDEO_TRACKS_TO_READY -> if (contentType == ContentType.LIVE) {
            watchdogConfiguration.liveTracksToReadyMs
        } else {
            watchdogConfiguration.vodTracksToReadyMs
        }
        WatchdogPhase.READY_TO_FIRST_VIDEO_FRAME -> if (contentType == ContentType.LIVE) {
            watchdogConfiguration.liveReadyToFirstFrameMs
        } else {
            watchdogConfiguration.vodReadyToFirstFrameMs
        }
        WatchdogPhase.RUNTIME_VIDEO_PROGRESS -> watchdogConfiguration.runtimeVideoProgressWindowMs
    }

    /**
     * A timeout is classified from the last proven phase, never from a URL suffix or playhead.
     * Network/no-byte failures are intentionally kept out of engine compatibility domains.
     */
    fun watchdogFailure(
        phase: WatchdogPhase,
        evidence: StreamEvidence,
    ): PlaybackFailure = when (phase) {
        WatchdogPhase.WAITING_FOR_SURFACE -> PlaybackFailure(
            code = FailureCode.SURFACE_LOST,
            domain = FailureDomain.VIDEO_RENDERER_SURFACE,
            phase = FailurePhase.SURFACE_ATTACHMENT,
            retryability = Retryability.HANDOFF_ELIGIBLE,
        )
        WatchdogPhase.FIRST_MEDIA_BYTE -> PlaybackFailure(
            code = FailureCode.NETWORK_TIMEOUT,
            domain = FailureDomain.NETWORK,
            phase = FailurePhase.ENGINE_START,
            retryability = Retryability.RETRYABLE_WITH_FRESH_REQUEST,
        )
        WatchdogPhase.BYTES_TO_TRACKS -> {
            val manifestDelivery = evidence.delivery?.value in setOf(DeliveryType.HLS, DeliveryType.DASH)
            PlaybackFailure(
                code = if (manifestDelivery) FailureCode.MANIFEST_INVALID else FailureCode.DEMUX_FAILED,
                domain = if (manifestDelivery) FailureDomain.MANIFEST else FailureDomain.DEMUX,
                phase = FailurePhase.ENGINE_START,
                retryability = Retryability.HANDOFF_ELIGIBLE,
            )
        }
        WatchdogPhase.VIDEO_TRACKS_TO_READY -> PlaybackFailure(
            code = FailureCode.VIDEO_DECODER_FAILED,
            domain = FailureDomain.VIDEO_DECODER,
            phase = FailurePhase.ENGINE_START,
            retryability = Retryability.HANDOFF_ELIGIBLE,
        )
        WatchdogPhase.READY_TO_FIRST_VIDEO_FRAME -> PlaybackFailure(
            code = FailureCode.VIDEO_RENDERER_FAILED,
            domain = FailureDomain.VIDEO_RENDERER_SURFACE,
            phase = FailurePhase.ENGINE_START,
            retryability = Retryability.HANDOFF_ELIGIBLE,
        )
        WatchdogPhase.RUNTIME_VIDEO_PROGRESS -> PlaybackFailure(
            code = FailureCode.NO_PROGRESS,
            domain = FailureDomain.VIDEO_RENDERER_SURFACE,
            phase = FailurePhase.PLAYBACK,
            retryability = Retryability.HANDOFF_ELIGIBLE,
        )
    }

    /** Null means the engine cannot expose the common rendered-frame fact. */
    fun renderedVideoAdvanced(
        before: PlaybackEngineMetricsSnapshot,
        after: PlaybackEngineMetricsSnapshot,
    ): Boolean? {
        if (before.generation != after.generation) return null
        val beforeFrames = before.videoFramesRendered ?: return null
        val afterFrames = after.videoFramesRendered ?: return null
        return afterFrames > beforeFrames
    }

    data class SelectionInput(
        val requirements: PlaybackRequirements,
        val candidates: List<PlaybackGraph>,
        val excludedEngines: Set<EngineType> = emptySet(),
    )

    sealed interface Selection {
        data class Selected(
            val graph: PlaybackGraph,
            val reason: SelectionReason,
        ) : Selection

        data class Rejected(val failure: PlaybackFailure) : Selection
    }

    enum class SelectionReason {
        EFFECTIVE_ENGINE_ORDER,
        MEDIA3_DEFAULT,
        GUIDE_MPV_DIRECT_FALLBACK,
        ONLY_ELIGIBLE_GRAPH,
    }

    fun selectPrimary(input: SelectionInput): Selection = select(input, handoff = false)

    fun selectHandoff(
        requirements: PlaybackRequirements,
        candidates: List<PlaybackGraph>,
        failedGraph: PlaybackGraph,
    ): Selection = select(
        SelectionInput(
            requirements = requirements,
            candidates = candidates,
            excludedEngines = setOf(failedGraph.engine),
        ),
        handoff = true,
    )

    fun liveReconnectDelayMs(attempt: Int): Long {
        require(attempt >= 0) { "Reconnect attempt must not be negative" }
        return RECONNECT_DELAYS_MS.getOrElse(attempt) { RECONNECT_DELAYS_MS.last() }
    }

    fun liveReconnectAllowed(
        attemptInIncident: Int,
        recentAttemptsEpochMs: Collection<Long>,
        nowEpochMs: Long,
    ): Boolean {
        if (attemptInIncident >= liveReconnectConfiguration.maxAttemptsPerIncident) return false
        val windowStart = nowEpochMs - liveReconnectConfiguration.rollingWindowMs
        return recentAttemptsEpochMs.count { it >= windowStart } <
            liveReconnectConfiguration.maxAttemptsPerRollingWindow
    }

    fun liveReconnectWindowStart(nowEpochMs: Long): Long =
        nowEpochMs - liveReconnectConfiguration.rollingWindowMs

    fun stablePlaybackResetsReconnectPressure(stableForMs: Long): Boolean =
        stableForMs >= liveReconnectConfiguration.minimumStablePlaybackBeforeResetMs

    fun liveReconnectExhaustedFailure(): PlaybackFailure = PlaybackFailure(
        code = FailureCode.LIVE_RECONNECT_EXHAUSTED,
        domain = FailureDomain.NETWORK,
        phase = FailurePhase.RECOVERY,
        retryability = Retryability.FATAL,
    )

    /** Adapters report authorization evidence; provider-link freshness is a core concern. */
    fun normalizeAuthorizationRecovery(
        failure: PlaybackFailure,
        providerCanMintFreshRequest: Boolean,
    ): PlaybackFailure {
        if (failure.domain != FailureDomain.AUTHORIZATION_PROVIDER_LIMIT ||
            failure.code != FailureCode.AUTHORIZATION_REJECTED
        ) return failure
        return failure.copy(
            retryability = if (providerCanMintFreshRequest) {
                Retryability.RETRYABLE_WITH_FRESH_REQUEST
            } else {
                Retryability.FATAL
            },
        )
    }

    private fun select(input: SelectionInput, handoff: Boolean): Selection {
        val eligible = input.candidates
            .asSequence()
            .filter(PlaybackGraph::isStructurallyValid)
            .filter { it.engine in input.requirements.eligibleEngines }
            .filterNot { it.engine in input.excludedEngines }
            .filter { !input.requirements.secureOutputRequired || it.secureOutput }
            .filter { it.surfaceMode in input.requirements.allowedSurfaceModes }
            .filter { graph -> decoderEligible(graph, input.requirements) }
            .filter { graph -> audioEligible(graph, input.requirements.audioOutput) }
            .filter {
                it.outputProfile != GraphOutputProfile.MPV_RENDER ||
                    input.requirements.gpuRenderingAllowed && it.surfaceMode == SurfaceMode.GPU_RENDER
            }
            .filterNot {
                it.outputProfile == GraphOutputProfile.MPV_DIRECT &&
                    input.requirements.subtitlesEnabled &&
                    input.requirements.subtitleFidelity == SubtitleFidelity.FULL
            }
            .distinctBy(PlaybackGraph::id)
            .toList()

        if (eligible.isEmpty()) return Selection.Rejected(noEligibleGraphFailure())

        val engineOrder = effectiveEngineOrder(input.requirements)
        val selected = eligible.minWithOrNull(
            compareBy<PlaybackGraph>(
                { engineOrder.indexOf(it.engine).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE },
                { outputRank(it, input.requirements.profile) },
                { decoderRank(it) },
                PlaybackGraph::id,
            ),
        ) ?: return Selection.Rejected(noEligibleGraphFailure())

        val reason = when {
            eligible.size == 1 -> SelectionReason.ONLY_ELIGIBLE_GRAPH
            handoff && input.requirements.profile == SessionProfile.GUIDE &&
                selected.outputProfile == GraphOutputProfile.MPV_DIRECT -> {
                SelectionReason.GUIDE_MPV_DIRECT_FALLBACK
            }
            input.requirements.preferredEngineOrder.isNotEmpty() -> SelectionReason.EFFECTIVE_ENGINE_ORDER
            selected.engine == EngineType.MEDIA3 -> SelectionReason.MEDIA3_DEFAULT
            else -> SelectionReason.EFFECTIVE_ENGINE_ORDER
        }
        return Selection.Selected(selected, reason)
    }

    private fun effectiveEngineOrder(requirements: PlaybackRequirements): List<EngineType> {
        val requested = requirements.preferredEngineOrder
        if (requested.isNotEmpty()) return requested
        return DEFAULT_ENGINE_ORDER.filter(requirements.eligibleEngines::contains)
    }

    private fun outputRank(graph: PlaybackGraph, profile: SessionProfile): Int = when (graph.outputProfile) {
        GraphOutputProfile.MEDIA3_STANDARD -> 0
        GraphOutputProfile.MPV_DIRECT -> if (profile == SessionProfile.GUIDE) 0 else 1
        GraphOutputProfile.MPV_RENDER -> if (profile == SessionProfile.FULLSCREEN) 0 else 2
    }

    private fun decoderRank(graph: PlaybackGraph): Int = when (graph.decoderMode) {
        DecoderMode.HARDWARE -> 0
        DecoderMode.SOFTWARE -> 1
    }

    private fun decoderEligible(
        graph: PlaybackGraph,
        requirements: PlaybackRequirements,
    ): Boolean = when (requirements.decoderPreference) {
        DecoderPreference.HARDWARE_ONLY -> graph.decoderMode == DecoderMode.HARDWARE
        DecoderPreference.SOFTWARE_ONLY -> graph.decoderMode == DecoderMode.SOFTWARE
        DecoderPreference.AUTO ->
            graph.decoderMode == DecoderMode.HARDWARE || requirements.softwareDecodeFallbackAllowed
    }

    private fun audioEligible(
        graph: PlaybackGraph,
        output: AudioOutputPreference,
    ): Boolean = when (output) {
        AudioOutputPreference.AUTO -> true
        AudioOutputPreference.PASSTHROUGH -> graph.audioMode == AudioMode.PASSTHROUGH
        AudioOutputPreference.PCM -> graph.audioMode == AudioMode.DECODE
    }

    private fun noEligibleGraphFailure() = PlaybackFailure(
        code = FailureCode.NO_ELIGIBLE_GRAPH,
        domain = FailureDomain.UNKNOWN,
        phase = FailurePhase.GRAPH_SELECTION,
        retryability = Retryability.FATAL,
        deterministic = true,
    )

    private companion object {
        val DEFAULT_ENGINE_ORDER = listOf(EngineType.MEDIA3, EngineType.LIBMPV)
        val RECONNECT_DELAYS_MS = longArrayOf(0L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L)
    }
}

/** Pure, typed VOD audio ladder; video failures are deliberately outside this policy. */
object AudioOutputPolicy {
    fun nextRequirements(
        current: PlaybackRequirements,
        failure: PlaybackFailure,
        failedEngine: EngineType,
    ): PlaybackRequirements? = when (failure.domain) {
        FailureDomain.AUDIO_DECODER -> {
            val alternate = current.preferredEngineOrder.firstOrNull { it != failedEngine }
            if (alternate != null) {
                current.copy(preferredEngineOrder = listOf(alternate) + current.preferredEngineOrder.filterNot { it == alternate })
            } else if (current.audioOutput != AudioOutputPreference.PCM) {
                current.copy(audioOutput = AudioOutputPreference.PCM, audioDownmixToStereo = true)
            } else {
                null
            }
        }
        FailureDomain.AUDIO_SINK -> if (current.audioOutput != AudioOutputPreference.PCM) {
            current.copy(audioOutput = AudioOutputPreference.PCM, audioDownmixToStereo = true)
        } else {
            null
        }
        else -> null
    }
}

/** Pure WP3 resolver: it translates effective intent and runtime evidence into one engine contract. */
class DefaultPlaybackRequirementsResolver : PlaybackRequirementsResolver {
    override suspend fun resolve(input: PlaybackRequirementsInput): PlaybackResult<PlaybackRequirements> {
        val environment = input.environment
        val capabilities = environment.runtimeCapabilities
        val secureOutputRequired = environment.secureOutputRequired
        val allowedSurfaces = resolveAllowedSurfaces(input, capabilities, secureOutputRequired)
        val eligibleEngines = resolveEligibleEngines(input, allowedSurfaces)
        if (eligibleEngines.isEmpty() || allowedSurfaces.isEmpty()) return rejectedRequirements()

        val preferences = input.effectivePreferences
        val adaptive = input.evidence.adaptive?.value == true
        val adaptiveCeiling = if (adaptive) {
            resolveAdaptiveCeiling(input, capabilities)
        } else {
            null
        }
        val preferredDimensions = if (adaptive && input.profile == SessionProfile.GUIDE) {
            environment.previewViewport
                ?.cappedBy(capabilities.display.currentDimensions)
                ?.let { preferred -> adaptiveCeiling?.let { preferred.cappedBy(it) } ?: preferred }
        } else {
            null
        }
        val guide = input.profile == SessionProfile.GUIDE
        val gpuAllowed = !guide &&
            environment.resourceBudget.gpuCost != ResourceAllowance.DISALLOWED &&
            SurfaceMode.GPU_RENDER in allowedSurfaces
        val requestedHdr = preferences.video.hdr
        val effectiveHdr = if (guide) {
            if (requestedHdr == HdrPreference.SDR) HdrPreference.SDR else HdrPreference.AUTO
        } else {
            supportedHdr(requestedHdr, capabilities.display.hdrTypes)
        }
        val processingRequested = preferences.audio.downmixToStereo ||
            preferences.audio.normalization ||
            preferences.audio.skipSilence
        val requestedAudioOutput = if (guide) {
            if (processingRequested) AudioOutputPreference.PCM else AudioOutputPreference.AUTO
        } else {
            preferences.audio.output
        }
        val streamAudioCodec = input.evidence.audioCodec?.value
        val passthroughProven = streamAudioCodec != null &&
            streamAudioCodec != AudioCodec.UNKNOWN &&
            streamAudioCodec in capabilities.audioRoute.encodedFormats
        val effectiveAudioOutput = if (
            requestedAudioOutput == AudioOutputPreference.PASSTHROUGH && !passthroughProven
        ) {
            AudioOutputPreference.AUTO
        } else {
            requestedAudioOutput
        }
        val pcmProcessingAllowed = effectiveAudioOutput != AudioOutputPreference.PASSTHROUGH
        val effectiveBuffering = if (guide && input.requestSummary.contentType == ContentType.LIVE) {
            BufferingPreference.LOW_LATENCY_LIVE
        } else {
            preferences.buffering
        }

        return PlaybackResult.Success(
            PlaybackRequirements(
                profile = input.profile,
                priority = if (guide) SessionPriority.STARTUP_SPEED else SessionPriority.QUALITY_AND_STABILITY,
                qualityIntent = if (guide) VideoQualityIntent.PREVIEW else VideoQualityIntent.FULL,
                preferredAdaptiveDimensions = preferredDimensions,
                adaptiveDimensionCeiling = adaptiveCeiling,
                bitrateCeiling = environment.resourceBudget.networkBitrateCeiling.takeIf { adaptive },
                displayModeSwitchAllowed = !guide &&
                    capabilities.display.modeSwitchSupported &&
                    (preferences.display.frameRate != FrameRatePreference.OFF ||
                        preferences.display.resolutionMatching),
                resolutionMatchingEnabled = !guide && preferences.display.resolutionMatching,
                frameRatePreference = if (guide) FrameRatePreference.OFF else preferences.display.frameRate,
                hdrPreference = effectiveHdr,
                decoderPreference = preferences.decoder,
                softwareDecodeFallbackAllowed = preferences.softwareDecodeFallback &&
                    environment.resourceBudget.decoderCost != ResourceAllowance.DISALLOWED &&
                    !capabilities.resources.lowMemory &&
                    capabilities.resources.thermalState in setOf(ThermalState.NOMINAL, ThermalState.FAIR),
                subtitleFidelity = preferences.subtitles.fidelity,
                subtitlesEnabled = preferences.subtitles.enabled,
                audioOutput = effectiveAudioOutput,
                pcmProcessingAllowed = pcmProcessingAllowed,
                buffering = effectiveBuffering,
                customBuffer = preferences.customBuffer.takeIf {
                    effectiveBuffering == BufferingPreference.CUSTOM
                },
                audioDownmixToStereo = preferences.audio.downmixToStereo && pcmProcessingAllowed,
                audioNormalization = preferences.audio.normalization && pcmProcessingAllowed,
                audioSkipSilence = preferences.audio.skipSilence && pcmProcessingAllowed,
                preferredAudioLanguage = preferences.audio.preferredLanguage,
                audioDelayMs = preferences.audio.delayMs,
                preferredSubtitleLanguage = preferences.subtitles.preferredLanguage,
                subtitleDelayMs = preferences.subtitles.delayMs,
                gpuRenderingAllowed = gpuAllowed,
                eligibleEngines = eligibleEngines,
                preferredEngineOrder = resolveEngineOrder(input, eligibleEngines),
                allowedSurfaceModes = allowedSurfaces,
                secureOutputRequired = secureOutputRequired,
                resourceBudget = environment.resourceBudget,
            ),
        )
    }

    private fun resolveAllowedSurfaces(
        input: PlaybackRequirementsInput,
        capabilities: RuntimeCapabilities,
        secureOutputRequired: Boolean,
    ): Set<SurfaceMode> {
        val environment = input.environment
        if (environment.resourceBudget.surfaceCost == ResourceAllowance.DISALLOWED) return emptySet()
        val supported = buildSet {
            if (capabilities.surfaces.surfaceViewSupported) add(SurfaceMode.SURFACE_VIEW)
            if (capabilities.surfaces.textureViewSupported) add(SurfaceMode.TEXTURE_VIEW)
            if (capabilities.surfaces.nativeEmbedSupported) add(SurfaceMode.NATIVE_EMBED)
            if (capabilities.surfaces.gpuRenderingSupported &&
                environment.resourceBudget.gpuCost != ResourceAllowance.DISALLOWED
            ) {
                add(SurfaceMode.GPU_RENDER)
            }
        }
        val secureSupported = if (secureOutputRequired) {
            buildSet {
                if (capabilities.surfaces.secureSurfaceSupported) add(SurfaceMode.SURFACE_VIEW)
                if (capabilities.surfaces.secureNativeEmbedSupported) add(SurfaceMode.NATIVE_EMBED)
                if (capabilities.surfaces.secureGpuRenderingSupported) add(SurfaceMode.GPU_RENDER)
            }
        } else {
            supported
        }
        return environment.allowedSurfaceModes
            .intersect(supported)
            .intersect(secureSupported)
    }

    private fun resolveEligibleEngines(
        input: PlaybackRequirementsInput,
        allowedSurfaces: Set<SurfaceMode>,
    ): Set<EngineType> {
        val surfaceEligible = buildSet {
            if (allowedSurfaces.any { it == SurfaceMode.SURFACE_VIEW || it == SurfaceMode.TEXTURE_VIEW }) {
                add(EngineType.MEDIA3)
            }
            if (allowedSurfaces.any { it == SurfaceMode.NATIVE_EMBED || it == SurfaceMode.GPU_RENDER }) {
                add(EngineType.LIBMPV)
            }
        }
        val hasDrm = input.requestSummary.hasDrm || input.evidence.drmScheme != null
        val drmEligible = if (hasDrm) setOf(EngineType.MEDIA3) else EngineType.entries.toSet()
        var eligible = input.environment.eligibleEngines.intersect(surfaceEligible).intersect(drmEligible)
        val explicit = when (input.effectivePreferences.engine) {
            EnginePreference.AUTO -> null
            EnginePreference.MEDIA3 -> EngineType.MEDIA3
            EnginePreference.LIBMPV -> EngineType.LIBMPV
        }
        if (explicit != null && !input.effectivePreferences.automaticFallback) {
            eligible = eligible.intersect(setOf(explicit))
        }
        return eligible
    }

    private fun resolveEngineOrder(
        input: PlaybackRequirementsInput,
        eligible: Set<EngineType>,
    ): List<EngineType> {
        val explicit = when (input.effectivePreferences.engine) {
            EnginePreference.AUTO -> emptyList()
            EnginePreference.MEDIA3 -> listOf(EngineType.MEDIA3)
            EnginePreference.LIBMPV -> listOf(EngineType.LIBMPV)
        }
        val productDefault = when (input.requestSummary.contentType) {
            ContentType.LIVE -> LIVE_ENGINE_ORDER
            ContentType.VOD, ContentType.CATCH_UP -> VOD_ENGINE_ORDER
        }
        // Environment order is deterministic compatibility/history evidence. It therefore ranks
        // after an explicit user override but before the product default.
        val requested = explicit + input.environment.preferredEngineOrder + productDefault
        return requested.distinct().filter(eligible::contains)
    }

    private fun resolveAdaptiveCeiling(
        input: PlaybackRequirementsInput,
        capabilities: RuntimeCapabilities,
    ): VideoDimensions? {
        val display = capabilities.display.currentDimensions
        val profileCeiling = if (input.profile == SessionProfile.GUIDE) {
            input.environment.previewViewport?.withHeadroom(PREVIEW_HEADROOM)?.cappedBy(display)
        } else {
            display
        }
        return listOfNotNull(
            profileCeiling,
            input.effectivePreferences.video.maximumDimensions,
            input.environment.resourceBudget.adaptiveDimensionCeiling,
        ).reduceOrNull { current, constraint -> current.cappedBy(constraint) }
    }

    private fun supportedHdr(requested: HdrPreference, available: Set<HdrType>): HdrPreference = when (requested) {
        HdrPreference.AUTO, HdrPreference.SDR -> requested
        HdrPreference.HDR10 -> if (HdrType.HDR10 in available) requested else HdrPreference.AUTO
        HdrPreference.DOLBY_VISION -> if (HdrType.DOLBY_VISION in available) requested else HdrPreference.AUTO
    }

    private fun rejectedRequirements(): PlaybackResult.Failure = PlaybackResult.Failure(
        PlaybackFailure(
            code = FailureCode.NO_ELIGIBLE_GRAPH,
            domain = FailureDomain.DEVICE_RESOURCE,
            phase = FailurePhase.GRAPH_SELECTION,
            retryability = Retryability.FATAL,
            deterministic = true,
        ),
    )

    private fun VideoDimensions.cappedBy(other: VideoDimensions): VideoDimensions = VideoDimensions(
        width = minOf(width, other.width),
        height = minOf(height, other.height),
    )

    private fun VideoDimensions.withHeadroom(multiplier: Double): VideoDimensions = VideoDimensions(
        width = kotlin.math.ceil(width * multiplier).toInt(),
        height = kotlin.math.ceil(height * multiplier).toInt(),
    )

    private companion object {
        const val PREVIEW_HEADROOM = 1.5
        val LIVE_ENGINE_ORDER = listOf(EngineType.LIBMPV, EngineType.MEDIA3)
        val VOD_ENGINE_ORDER = listOf(EngineType.MEDIA3, EngineType.LIBMPV)
    }
}

/** Conservative transition classification; adapters do not weaken this contract implicitly. */
object PlaybackRequirementsDiffClassifier {
    fun classify(previous: PlaybackRequirements, next: PlaybackRequirements): PlaybackRequirementsDiff {
        val changed = buildSet {
            if (previous.profile != next.profile) add(RequirementsField.PROFILE)
            if (previous.preferredAdaptiveDimensions != next.preferredAdaptiveDimensions ||
                previous.adaptiveDimensionCeiling != next.adaptiveDimensionCeiling
            ) add(RequirementsField.ADAPTIVE_QUALITY)
            if (previous.bitrateCeiling != next.bitrateCeiling) add(RequirementsField.NETWORK_BITRATE)
            if (previous.displayModeSwitchAllowed != next.displayModeSwitchAllowed ||
                previous.resolutionMatchingEnabled != next.resolutionMatchingEnabled ||
                previous.frameRatePreference != next.frameRatePreference
            ) add(RequirementsField.DISPLAY_OUTPUT)
            if (previous.hdrPreference != next.hdrPreference) add(RequirementsField.HDR)
            if (previous.decoderPreference != next.decoderPreference ||
                previous.softwareDecodeFallbackAllowed != next.softwareDecodeFallbackAllowed
            ) add(RequirementsField.DECODER)
            if (previous.subtitlesEnabled != next.subtitlesEnabled ||
                previous.preferredSubtitleLanguage != next.preferredSubtitleLanguage ||
                previous.subtitleDelayMs != next.subtitleDelayMs
            ) add(RequirementsField.SUBTITLE_SELECTION)
            if (previous.subtitleFidelity != next.subtitleFidelity) {
                add(RequirementsField.SUBTITLE_RENDERER)
            }
            if (previous.audioOutput != next.audioOutput ||
                previous.pcmProcessingAllowed != next.pcmProcessingAllowed
            ) add(RequirementsField.AUDIO_OUTPUT)
            if (previous.audioDownmixToStereo != next.audioDownmixToStereo ||
                previous.audioNormalization != next.audioNormalization
            ) add(RequirementsField.AUDIO_PIPELINE)
            if (previous.audioSkipSilence != next.audioSkipSilence) {
                add(RequirementsField.AUDIO_RUNTIME_PROCESSING)
            }
            if (previous.preferredAudioLanguage != next.preferredAudioLanguage ||
                previous.audioDelayMs != next.audioDelayMs
            ) add(RequirementsField.AUDIO_SELECTION)
            if (previous.buffering != next.buffering || previous.customBuffer != next.customBuffer) {
                add(RequirementsField.BUFFERING)
            }
            if (previous.gpuRenderingAllowed != next.gpuRenderingAllowed) add(RequirementsField.GPU_RENDERING)
            if (previous.eligibleEngines != next.eligibleEngines) add(RequirementsField.ENGINE_ELIGIBILITY)
            if (previous.preferredEngineOrder != next.preferredEngineOrder) add(RequirementsField.ENGINE_ORDER)
            if (previous.allowedSurfaceModes != next.allowedSurfaceModes) add(RequirementsField.SURFACE_ELIGIBILITY)
            if (previous.secureOutputRequired != next.secureOutputRequired) add(RequirementsField.SECURE_OUTPUT)
            if (previous.resourceBudget != next.resourceBudget) add(RequirementsField.RESOURCE_BUDGET)
        }
        val impact = when {
            changed.any(RESELECT_FIELDS::contains) -> ChangeImpact.RESELECT_GRAPH
            changed.any(REBUILD_FIELDS::contains) -> ChangeImpact.REBUILD_CURRENT_GRAPH
            else -> ChangeImpact.APPLY_IN_PLACE
        }
        return PlaybackRequirementsDiff(impact, changed)
    }

    private val RESELECT_FIELDS = setOf(
        RequirementsField.DECODER,
        RequirementsField.SUBTITLE_RENDERER,
        RequirementsField.AUDIO_OUTPUT,
        RequirementsField.GPU_RENDERING,
        RequirementsField.ENGINE_ELIGIBILITY,
        RequirementsField.ENGINE_ORDER,
        RequirementsField.SURFACE_ELIGIBILITY,
        RequirementsField.SECURE_OUTPUT,
    )
    private val REBUILD_FIELDS = setOf(
        RequirementsField.DISPLAY_OUTPUT,
        RequirementsField.HDR,
        RequirementsField.BUFFERING,
        RequirementsField.AUDIO_PIPELINE,
    )
}

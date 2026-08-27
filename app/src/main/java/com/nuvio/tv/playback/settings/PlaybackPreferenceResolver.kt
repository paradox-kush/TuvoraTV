package com.nuvio.tv.playback.settings

import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.CompatibilityOutcome
import com.nuvio.tv.playback.core.CompatibilityGraphFingerprint
import com.nuvio.tv.playback.core.CompatibilityRecord
import com.nuvio.tv.playback.core.CompatibilityRuntimeFingerprint
import com.nuvio.tv.playback.core.CompatibilityScopeKey
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.CustomBufferPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.HdrType
import com.nuvio.tv.playback.core.PreferenceAvailability
import com.nuvio.tv.playback.core.PreferenceConflict
import com.nuvio.tv.playback.core.PreferenceReason
import com.nuvio.tv.playback.core.PreferenceResolution
import com.nuvio.tv.playback.core.RequestSummary
import com.nuvio.tv.playback.core.ResolutionAuthority
import com.nuvio.tv.playback.core.RuntimeCapabilities
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SubtitleFormat
import com.nuvio.tv.playback.core.VideoCodec
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.core.ChangeImpact

enum class PlaybackPreferenceConflictCode {
    PASSTHROUGH_DISALLOWS_PCM_PROCESSING,
    AUTO_AUDIO_SELECTED_PCM_PROCESSING,
    DOLBY_VISION_DISPLAY_UNAVAILABLE,
    ASS_REQUIRES_RENDER_PATH,
    AFR_DEFERRED_DURING_RAPID_ZAP,
    SOFTWARE_DECODE_EXCEEDS_BUDGET,
    CUSTOM_BUFFER_CLAMPED,
}

data class PlaybackPreferenceResolutionContext(
    val request: RequestSummary,
    val evidence: StreamEvidence,
    val capabilities: RuntimeCapabilities,
    val eligibleEngines: Set<EngineType> = EngineType.entries.toSet(),
    val compatibilityScopeKey: CompatibilityScopeKey? = null,
    val compatibilityRecords: List<CompatibilityRecord> = emptyList(),
    /** Graphs already proven eligible by stream/output policy; history cannot invent candidates. */
    val eligibleGraphFingerprints: Set<CompatibilityGraphFingerprint> = emptySet(),
    val compatibilityRuntime: CompatibilityRuntimeFingerprint? = null,
    val nowEpochMs: Long = 0,
    val appVersion: String = "",
    val engineVersions: Map<EngineType, String> = emptyMap(),
    val temporaryEngineOverride: EnginePreference? = null,
    val rapidLiveZapping: Boolean = false,
)

data class ResolvedPlaybackPreferences(
    val requested: CleanPlaybackPreferences,
    val effective: CleanPlaybackPreferences,
    val engine: PreferenceResolution<EnginePreference>,
    val automaticFallback: PreferenceResolution<Boolean>,
    val decoder: PreferenceResolution<DecoderPreference>,
    val softwareDecodeFallback: PreferenceResolution<Boolean>,
    val buffering: PreferenceResolution<BufferingPreference>,
    val customBuffer: PreferenceResolution<CustomBufferPreference?>,
    val audioOutput: PreferenceResolution<AudioOutputPreference>,
    val downmix: PreferenceResolution<Boolean>,
    val normalization: PreferenceResolution<Boolean>,
    val skipSilence: PreferenceResolution<Boolean>,
    val audioLanguage: PreferenceResolution<String?>,
    val audioDelayMs: PreferenceResolution<Long>,
    val subtitlesEnabled: PreferenceResolution<Boolean>,
    val subtitleFidelity: PreferenceResolution<SubtitleFidelity>,
    val subtitleLanguage: PreferenceResolution<String?>,
    val subtitleDelayMs: PreferenceResolution<Long>,
    val hdr: PreferenceResolution<HdrPreference>,
    val frameRate: PreferenceResolution<FrameRatePreference>,
    val resolutionMatching: PreferenceResolution<Boolean>,
    val maximumDimensions: PreferenceResolution<VideoDimensions?>,
    val autoplayNext: PreferenceResolution<Boolean>,
    val stillWatchingEnabled: PreferenceResolution<Boolean>,
    val showStatusIndicators: PreferenceResolution<Boolean>,
    val mpvOutput: PreferenceResolution<MpvOutputPreference>,
    val diagnostics: Map<String, String>,
)

/** Pure requested-to-effective preference resolver. It creates no player or platform object. */
object PlaybackPreferenceResolver {
    private const val LOW_MEMORY_BUFFER_CAP_MS = 30_000
    private const val VERY_LOW_MEMORY_BUFFER_CAP_MS = 15_000
    private const val MIN_SAFE_SOFTWARE_4K_MEMORY_BYTES = 1_000_000_000L
    private val nonCompatibilityFailureDomains = setOf(
        FailureDomain.NETWORK,
        FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
        FailureDomain.TLS,
    )

    fun resolve(
        requested: CleanPlaybackPreferences,
        context: PlaybackPreferenceResolutionContext,
    ): ResolvedPlaybackPreferences {
        val defaults = CleanPlaybackPreferences.recommended().playback
        val mpvOutput = resolveMpvOutput(requested, context)
        val engine = resolveEngine(
            requested,
            context,
            mpvOutput.effective ?: MpvOutputPreference.AUTO,
        )
        val automaticFallback = resolved(
            requested.playback.automaticFallback,
            defaults.automaticFallback,
            ChangeImpact.NEXT_SESSION_ONLY,
        )
        val decoder = resolveDecoder(requested, context)
        val softwareDecodeFallback = resolved(
            requested.playback.softwareDecodeFallback,
            defaults.softwareDecodeFallback,
            ChangeImpact.NEXT_SESSION_ONLY,
        )
        val buffering = resolved(
            requested.playback.buffering,
            defaults.buffering,
            ChangeImpact.REBUILD_CURRENT_GRAPH,
        )
        val audio = resolveAudio(requested)
        val audioLanguage = resolved(
            requested.playback.audio.preferredLanguage,
            defaults.audio.preferredLanguage,
            ChangeImpact.APPLY_IN_PLACE,
        )
        val audioDelay = resolved(
            requested.playback.audio.delayMs,
            defaults.audio.delayMs,
            ChangeImpact.APPLY_IN_PLACE,
        )
        val hdr = resolveHdr(requested, context)
        val frameRate = resolveFrameRate(requested, context)
        val subtitlesEnabled = resolved(
            requested.playback.subtitles.enabled,
            defaults.subtitles.enabled,
            ChangeImpact.APPLY_IN_PLACE,
        )
        val subtitleFidelity = resolved(
            requested.playback.subtitles.fidelity,
            defaults.subtitles.fidelity,
            ChangeImpact.RESELECT_GRAPH,
        )
        val subtitleLanguage = resolved(
            requested.playback.subtitles.preferredLanguage,
            defaults.subtitles.preferredLanguage,
            ChangeImpact.APPLY_IN_PLACE,
        )
        val subtitleDelay = resolved(
            requested.playback.subtitles.delayMs,
            defaults.subtitles.delayMs,
            ChangeImpact.APPLY_IN_PLACE,
        )
        val resolutionMatching = resolved(
            requested.playback.display.resolutionMatching,
            defaults.display.resolutionMatching,
            ChangeImpact.RESELECT_GRAPH,
        )
        val maximumDimensions = resolved(
            requested.playback.video.maximumDimensions,
            defaults.video.maximumDimensions,
            ChangeImpact.RESELECT_GRAPH,
        )
        val autoplayNext = resolved(
            requested.playback.behavior.autoplayNext,
            defaults.behavior.autoplayNext,
            ChangeImpact.APPLY_IN_PLACE,
        )
        val stillWatching = resolved(
            requested.playback.behavior.stillWatchingEnabled,
            defaults.behavior.stillWatchingEnabled,
            ChangeImpact.APPLY_IN_PLACE,
        )
        val showStatus = resolved(
            requested.playback.behavior.showStatusIndicators,
            defaults.behavior.showStatusIndicators,
            ChangeImpact.APPLY_IN_PLACE,
        )
        val customBuffer = resolveCustomBuffer(requested, context)

        val effectiveAudio = requested.playback.audio.copy(
            output = audio.output.effective ?: AudioOutputPreference.PCM,
            downmixToStereo = audio.downmix.effective ?: false,
            normalization = audio.normalization.effective ?: false,
            skipSilence = audio.skipSilence.effective ?: false,
            preferredLanguage = audioLanguage.effective,
            delayMs = audioDelay.effective ?: 0,
        )
        val effectivePlayback = requested.playback.copy(
            engine = engine.effective ?: requested.playback.engine,
            automaticFallback = automaticFallback.effective ?: defaults.automaticFallback,
            decoder = decoder.effective ?: requested.playback.decoder,
            softwareDecodeFallback = softwareDecodeFallback.effective ?: defaults.softwareDecodeFallback,
            buffering = buffering.effective ?: BufferingPreference.RECOMMENDED,
            customBuffer = customBuffer.effective,
            audio = effectiveAudio,
            subtitles = requested.playback.subtitles.copy(
                enabled = subtitlesEnabled.effective ?: defaults.subtitles.enabled,
                fidelity = subtitleFidelity.effective ?: defaults.subtitles.fidelity,
                preferredLanguage = subtitleLanguage.effective,
                delayMs = subtitleDelay.effective ?: defaults.subtitles.delayMs,
            ),
            display = requested.playback.display.copy(
                frameRate = frameRate.effective ?: FrameRatePreference.OFF,
                resolutionMatching = resolutionMatching.effective ?: defaults.display.resolutionMatching,
            ),
            video = requested.playback.video.copy(
                hdr = hdr.effective ?: HdrPreference.AUTO,
                maximumDimensions = maximumDimensions.effective,
            ),
            behavior = requested.playback.behavior.copy(
                autoplayNext = autoplayNext.effective ?: defaults.behavior.autoplayNext,
                stillWatchingEnabled = stillWatching.effective ?: defaults.behavior.stillWatchingEnabled,
                showStatusIndicators = showStatus.effective ?: defaults.behavior.showStatusIndicators,
            ),
        )
        val effectivePreferences = requested.copy(
            playback = effectivePlayback,
            expert = requested.expert.copy(
                mpvOutput = mpvOutput.effective ?: MpvOutputPreference.AUTO,
            ),
        )
        return ResolvedPlaybackPreferences(
            requested = requested,
            effective = effectivePreferences,
            engine = engine,
            automaticFallback = automaticFallback,
            decoder = decoder,
            softwareDecodeFallback = softwareDecodeFallback,
            buffering = buffering,
            customBuffer = customBuffer,
            audioOutput = audio.output,
            downmix = audio.downmix,
            normalization = audio.normalization,
            skipSilence = audio.skipSilence,
            audioLanguage = audioLanguage,
            audioDelayMs = audioDelay,
            subtitlesEnabled = subtitlesEnabled,
            subtitleFidelity = subtitleFidelity,
            subtitleLanguage = subtitleLanguage,
            subtitleDelayMs = subtitleDelay,
            hdr = hdr,
            frameRate = frameRate,
            resolutionMatching = resolutionMatching,
            maximumDimensions = maximumDimensions,
            autoplayNext = autoplayNext,
            stillWatchingEnabled = stillWatching,
            showStatusIndicators = showStatus,
            mpvOutput = mpvOutput,
            diagnostics = mapOf(
                "engine_requested" to requested.playback.engine.name,
                "engine_effective" to (engine.effective?.name ?: "UNAVAILABLE"),
                "engine_reason" to engine.primaryReason.name,
                "decoder_requested" to requested.playback.decoder.name,
                "decoder_effective" to (decoder.effective?.name ?: "UNAVAILABLE"),
                "audio_output_effective" to (audio.output.effective?.name ?: "UNAVAILABLE"),
                "hdr_effective" to (hdr.effective?.name ?: "UNAVAILABLE"),
                "mpv_output_effective" to (mpvOutput.effective?.name ?: "UNAVAILABLE"),
            ),
        )
    }

    private fun resolveEngine(
        requested: CleanPlaybackPreferences,
        context: PlaybackPreferenceResolutionContext,
        effectiveMpvOutput: MpvOutputPreference,
    ): PreferenceResolution<EnginePreference> {
        val saved = requested.playback.engine
        val selectedRequest = context.temporaryEngineOverride?.takeUnless { it == EnginePreference.AUTO } ?: saved
        val authority = if (context.temporaryEngineOverride != null && context.temporaryEngineOverride != EnginePreference.AUTO) {
            ResolutionAuthority.TEMPORARY_USER_OVERRIDE
        } else {
            ResolutionAuthority.SAVED_USER_OVERRIDE
        }
        val drmRequired = context.request.hasDrm || context.evidence.drmScheme != null
        if (drmRequired) {
            val media3Available = EngineType.MEDIA3 in context.eligibleEngines
            return PreferenceResolution(
                requested = selectedRequest,
                effective = if (media3Available) EnginePreference.MEDIA3 else null,
                authority = ResolutionAuthority.HARD_CONSTRAINT,
                availability = if (media3Available) {
                    PreferenceAvailability.SUPPORTED
                } else {
                    PreferenceAvailability.UNAVAILABLE
                },
                primaryReason = PreferenceReason.DRM_REQUIRES_MEDIADRM,
                impact = ChangeImpact.RESELECT_GRAPH,
            )
        }

        val explicitType = selectedRequest.toEngineType()
        if (explicitType != null) {
            if (explicitType !in context.eligibleEngines) {
                val alternate = alternateEngine(explicitType, context, requested, effectiveMpvOutput)
                return PreferenceResolution(
                    requested = selectedRequest,
                    effective = alternate?.toPreference(),
                    authority = ResolutionAuthority.STREAM_ELIGIBILITY,
                    availability = PreferenceAvailability.UNAVAILABLE,
                    primaryReason = PreferenceReason.UNSUPPORTED_BY_STREAM,
                    impact = ChangeImpact.RESELECT_GRAPH,
                )
            }
            if (isDeterministicFatal(explicitType, effectiveMpvOutput, context)) {
                val alternate = alternateEngine(explicitType, context, requested, effectiveMpvOutput)
                return PreferenceResolution(
                    requested = selectedRequest,
                    effective = alternate?.toPreference(),
                    authority = ResolutionAuthority.LEARNED_COMPATIBILITY,
                    availability = PreferenceAvailability.UNAVAILABLE,
                    primaryReason = PreferenceReason.KNOWN_FATAL_INCOMPATIBILITY,
                    impact = ChangeImpact.RESELECT_GRAPH,
                )
            }
            return PreferenceResolution(
                requested = selectedRequest,
                effective = explicitType.toPreference(),
                authority = authority,
                availability = PreferenceAvailability.SUPPORTED,
                primaryReason = PreferenceReason.EXPLICIT_USER_OVERRIDE,
                impact = ChangeImpact.RESELECT_GRAPH,
            )
        }

        val eligible = context.eligibleEngines.filterNot {
            isDeterministicFatal(it, effectiveMpvOutput, context)
        }
        val historyChoice = eligible
            .filter { hasCurrentSuccess(it, context) }
            .maxByOrNull { engine ->
                context.compatibilityRecords
                    .filter {
                        it.engine == engine &&
                            it.graph in context.eligibleGraphFingerprints &&
                            it.outcome == CompatibilityOutcome.SUCCESS &&
                            isCurrentCompatibilityRecord(it, context)
                    }
                    .maxOfOrNull(CompatibilityRecord::recordedAtEpochMs) ?: Long.MIN_VALUE
            }
        val chosen = historyChoice ?: when {
            EngineType.MEDIA3 in eligible -> EngineType.MEDIA3
            else -> eligible.firstOrNull()
        }
        return PreferenceResolution(
            requested = saved,
            effective = chosen?.toPreference(),
            authority = if (historyChoice != null) {
                ResolutionAuthority.LEARNED_COMPATIBILITY
            } else {
                ResolutionAuthority.DEFAULT_POLICY
            },
            availability = if (chosen == null) PreferenceAvailability.UNAVAILABLE else PreferenceAvailability.SUPPORTED,
            primaryReason = if (chosen == null) {
                PreferenceReason.KNOWN_FATAL_INCOMPATIBILITY
            } else {
                PreferenceReason.REQUEST_EFFECTIVE
            },
            impact = ChangeImpact.RESELECT_GRAPH,
        )
    }

    private fun resolveDecoder(
        requested: CleanPlaybackPreferences,
        context: PlaybackPreferenceResolutionContext,
    ): PreferenceResolution<DecoderPreference> {
        val value = requested.playback.decoder
        val dimensions = context.evidence.dimensions?.value
        val codec = context.evidence.videoCodec?.value
        val unsafeSoftware = value == DecoderPreference.SOFTWARE_ONLY &&
            codec == VideoCodec.AV1 &&
            dimensions != null && dimensions.width >= 3_840 && dimensions.height >= 2_160 &&
            (context.capabilities.resources.lowMemory ||
                context.capabilities.resources.availableMemoryBytes < MIN_SAFE_SOFTWARE_4K_MEMORY_BYTES)
        if (!unsafeSoftware) return resolved(value, DecoderPreference.AUTO, ChangeImpact.RESELECT_GRAPH)
        val hardwareAvailable = context.capabilities.videoDecoders.any { decoder ->
            val requiredFrameRate = context.evidence.frameRate?.value
            val maximumDimensions = decoder.maxDimensions
            val maximumFrameRate = decoder.maxFrameRate
            decoder.codec == VideoCodec.AV1 &&
                decoder.hardwareAccelerated &&
                maximumDimensions != null &&
                maximumDimensions.width >= dimensions!!.width &&
                maximumDimensions.height >= dimensions.height &&
                requiredFrameRate != null &&
                maximumFrameRate != null &&
                maximumFrameRate >= requiredFrameRate
        }
        return PreferenceResolution(
            requested = value,
            effective = if (hardwareAvailable) DecoderPreference.HARDWARE_ONLY else null,
            authority = ResolutionAuthority.HARD_CONSTRAINT,
            availability = PreferenceAvailability.UNAVAILABLE,
            primaryReason = PreferenceReason.SOFTWARE_DECODE_EXCEEDS_RESOURCE_BUDGET,
            conflicts = conflict(
                PlaybackPreferenceConflictCode.SOFTWARE_DECODE_EXCEEDS_BUDGET,
                "decoder",
            ),
            impact = ChangeImpact.RESELECT_GRAPH,
        )
    }

    private data class AudioResolutions(
        val output: PreferenceResolution<AudioOutputPreference>,
        val downmix: PreferenceResolution<Boolean>,
        val normalization: PreferenceResolution<Boolean>,
        val skipSilence: PreferenceResolution<Boolean>,
    )

    private fun resolveAudio(requested: CleanPlaybackPreferences): AudioResolutions {
        val audio = requested.playback.audio
        val processingRequested = audio.downmixToStereo || audio.normalization || audio.skipSilence
        if (audio.output == AudioOutputPreference.PASSTHROUGH && processingRequested) {
            val conflict = conflict(
                PlaybackPreferenceConflictCode.PASSTHROUGH_DISALLOWS_PCM_PROCESSING,
                "audio.processing",
            )
            fun disabled(request: Boolean) = if (!request) resolved(false, false, ChangeImpact.APPLY_IN_PLACE) else {
                PreferenceResolution(
                    requested = true,
                    effective = false,
                    authority = ResolutionAuthority.HARD_CONSTRAINT,
                    availability = PreferenceAvailability.UNAVAILABLE,
                    primaryReason = PreferenceReason.PCM_PROCESSING_REQUIRES_DECODED_AUDIO,
                    conflicts = conflict,
                    impact = ChangeImpact.APPLY_IN_PLACE,
                )
            }
            return AudioResolutions(
                output = resolved(audio.output, AudioOutputPreference.AUTO, ChangeImpact.REBUILD_CURRENT_GRAPH),
                downmix = disabled(audio.downmixToStereo),
                normalization = disabled(audio.normalization),
                skipSilence = disabled(audio.skipSilence),
            )
        }
        val output = if (audio.output == AudioOutputPreference.AUTO && processingRequested) {
            PreferenceResolution(
                requested = audio.output,
                effective = AudioOutputPreference.PCM,
                authority = ResolutionAuthority.STREAM_ELIGIBILITY,
                availability = PreferenceAvailability.SUPPORTED,
                primaryReason = PreferenceReason.PCM_PROCESSING_REQUIRES_DECODED_AUDIO,
                conflicts = conflict(
                    PlaybackPreferenceConflictCode.AUTO_AUDIO_SELECTED_PCM_PROCESSING,
                    "audio.output",
                ),
                impact = ChangeImpact.REBUILD_CURRENT_GRAPH,
            )
        } else {
            resolved(audio.output, AudioOutputPreference.AUTO, ChangeImpact.REBUILD_CURRENT_GRAPH)
        }
        return AudioResolutions(
            output,
            resolved(audio.downmixToStereo, false, ChangeImpact.APPLY_IN_PLACE),
            resolved(audio.normalization, false, ChangeImpact.APPLY_IN_PLACE),
            resolved(audio.skipSilence, false, ChangeImpact.APPLY_IN_PLACE),
        )
    }

    private fun resolveHdr(
        requested: CleanPlaybackPreferences,
        context: PlaybackPreferenceResolutionContext,
    ): PreferenceResolution<HdrPreference> {
        val value = requested.playback.video.hdr
        val supported = context.capabilities.display.hdrTypes
        if (value == HdrPreference.DOLBY_VISION && HdrType.DOLBY_VISION !in supported) {
            return PreferenceResolution(
                requested = value,
                // StreamEvidence has no HDR10-base-layer fact. AUTO preserves a conservative
                // downstream choice instead of inventing an HDR10 or SDR conversion path.
                effective = HdrPreference.AUTO,
                authority = ResolutionAuthority.HARD_CONSTRAINT,
                availability = PreferenceAvailability.UNAVAILABLE,
                primaryReason = PreferenceReason.DOLBY_VISION_OUTPUT_UNAVAILABLE,
                conflicts = conflict(
                    PlaybackPreferenceConflictCode.DOLBY_VISION_DISPLAY_UNAVAILABLE,
                    "video.hdr",
                ),
                impact = ChangeImpact.RESELECT_GRAPH,
            )
        }
        return resolved(value, HdrPreference.AUTO, ChangeImpact.RESELECT_GRAPH)
    }

    private fun resolveFrameRate(
        requested: CleanPlaybackPreferences,
        context: PlaybackPreferenceResolutionContext,
    ): PreferenceResolution<FrameRatePreference> {
        val value = requested.playback.display.frameRate
        if (
            value == FrameRatePreference.ALWAYS &&
            context.rapidLiveZapping &&
            context.request.contentType == ContentType.LIVE
        ) {
            return PreferenceResolution(
                requested = value,
                effective = FrameRatePreference.ON_COMMITTED_PLAYBACK,
                authority = ResolutionAuthority.HARD_CONSTRAINT,
                availability = PreferenceAvailability.SUPPORTED,
                primaryReason = PreferenceReason.AFR_DEFERRED_DURING_ZAP,
                conflicts = conflict(
                    PlaybackPreferenceConflictCode.AFR_DEFERRED_DURING_RAPID_ZAP,
                    "display.frameRate",
                ),
                impact = ChangeImpact.APPLY_IN_PLACE,
            )
        }
        return resolved(value, FrameRatePreference.ON_COMMITTED_PLAYBACK, ChangeImpact.APPLY_IN_PLACE)
    }

    private fun resolveMpvOutput(
        requested: CleanPlaybackPreferences,
        context: PlaybackPreferenceResolutionContext,
    ): PreferenceResolution<MpvOutputPreference> {
        val value = requested.expert.mpvOutput
        val complexAss = requested.playback.subtitles.fidelity == SubtitleFidelity.FULL &&
            context.evidence.subtitleFormat?.value == SubtitleFormat.ASS
        if (complexAss && value == MpvOutputPreference.DIRECT) {
            return PreferenceResolution(
                requested = value,
                effective = MpvOutputPreference.RENDER,
                authority = ResolutionAuthority.STREAM_ELIGIBILITY,
                availability = PreferenceAvailability.UNAVAILABLE,
                primaryReason = PreferenceReason.SUBTITLE_FIDELITY_REQUIRES_RENDER,
                conflicts = conflict(
                    PlaybackPreferenceConflictCode.ASS_REQUIRES_RENDER_PATH,
                    "expert.mpvOutput",
                ),
                impact = ChangeImpact.RESELECT_GRAPH,
            )
        }
        return resolved(value, MpvOutputPreference.AUTO, ChangeImpact.RESELECT_GRAPH)
    }

    private fun resolveCustomBuffer(
        requested: CleanPlaybackPreferences,
        context: PlaybackPreferenceResolutionContext,
    ): PreferenceResolution<CustomBufferPreference?> {
        val value = requested.playback.customBuffer
        if (value == null) {
            return resolved<CustomBufferPreference?>(null, null, ChangeImpact.REBUILD_CURRENT_GRAPH)
        }
        val cap = when {
            context.capabilities.resources.lowMemory -> VERY_LOW_MEMORY_BUFFER_CAP_MS
            context.capabilities.resources.availableMemoryBytes < MIN_SAFE_SOFTWARE_4K_MEMORY_BYTES ->
                LOW_MEMORY_BUFFER_CAP_MS
            else -> Int.MAX_VALUE
        }
        if (value.maximumBufferMs <= cap) {
            return resolved<CustomBufferPreference?>(value, null, ChangeImpact.REBUILD_CURRENT_GRAPH)
        }
        val max = cap.coerceAtLeast(1)
        val clamped = CustomBufferPreference(
            minimumBufferMs = value.minimumBufferMs.coerceAtMost(max),
            maximumBufferMs = max,
            playbackStartBufferMs = value.playbackStartBufferMs.coerceAtMost(max),
            rebufferStartBufferMs = value.rebufferStartBufferMs.coerceAtMost(max),
        )
        return PreferenceResolution(
            requested = value,
            effective = clamped,
            authority = ResolutionAuthority.HARD_CONSTRAINT,
            availability = PreferenceAvailability.SUPPORTED,
            primaryReason = PreferenceReason.BUFFER_CLAMPED_TO_MEMORY_BUDGET,
            conflicts = conflict(
                PlaybackPreferenceConflictCode.CUSTOM_BUFFER_CLAMPED,
                "buffering.custom",
            ),
            impact = ChangeImpact.REBUILD_CURRENT_GRAPH,
        )
    }

    private fun isDeterministicFatal(
        engine: EngineType,
        mpvOutput: MpvOutputPreference,
        context: PlaybackPreferenceResolutionContext,
    ): Boolean {
        val scope = context.compatibilityScopeKey ?: return false
        val candidateOutputs = when (engine) {
            EngineType.MEDIA3 -> setOf(GraphOutputProfile.MEDIA3_STANDARD)
            EngineType.LIBMPV -> when (mpvOutput) {
                MpvOutputPreference.AUTO -> setOf(GraphOutputProfile.MPV_DIRECT, GraphOutputProfile.MPV_RENDER)
                MpvOutputPreference.DIRECT -> setOf(GraphOutputProfile.MPV_DIRECT)
                MpvOutputPreference.RENDER -> setOf(GraphOutputProfile.MPV_RENDER)
            }
        }
        val candidates = context.eligibleGraphFingerprints.filter { graph ->
            graph.engine == engine && graph.outputProfile in candidateOutputs
        }
        if (candidates.isEmpty()) return false
        return candidates.all { graph ->
            context.compatibilityRecords.any { record ->
                record.scopeKey == scope &&
                    record.graph == graph &&
                    record.outcome == CompatibilityOutcome.DETERMINISTIC_FATAL &&
                    isCurrentCompatibilityRecord(record, context)
            }
        }
    }

    private fun hasCurrentSuccess(
        engine: EngineType,
        context: PlaybackPreferenceResolutionContext,
    ): Boolean {
        val scope = context.compatibilityScopeKey ?: return false
        return context.compatibilityRecords.any { record ->
            record.scopeKey == scope &&
                record.engine == engine &&
                record.graph in context.eligibleGraphFingerprints &&
                record.outcome == CompatibilityOutcome.SUCCESS &&
                isCurrentCompatibilityRecord(record, context)
        }
    }

    private fun isCurrentCompatibilityRecord(
        record: CompatibilityRecord,
        context: PlaybackPreferenceResolutionContext,
    ): Boolean = record.failureDomain !in nonCompatibilityFailureDomains &&
        !record.isExpired(context.nowEpochMs) &&
        record.appVersion == context.appVersion &&
        record.engineVersion == context.engineVersions[record.engine] &&
        context.compatibilityRuntime != null &&
        record.runtime == context.compatibilityRuntime

    private fun alternateEngine(
        failed: EngineType,
        context: PlaybackPreferenceResolutionContext,
        requested: CleanPlaybackPreferences,
        effectiveMpvOutput: MpvOutputPreference,
    ): EngineType? {
        if (!requested.playback.automaticFallback) return null
        return context.eligibleEngines.firstOrNull {
            it != failed && !isDeterministicFatal(it, effectiveMpvOutput, context)
        }
    }

    private fun EnginePreference.toEngineType(): EngineType? = when (this) {
        EnginePreference.AUTO -> null
        EnginePreference.MEDIA3 -> EngineType.MEDIA3
        EnginePreference.LIBMPV -> EngineType.LIBMPV
    }

    private fun EngineType.toPreference(): EnginePreference = when (this) {
        EngineType.MEDIA3 -> EnginePreference.MEDIA3
        EngineType.LIBMPV -> EnginePreference.LIBMPV
    }

    private fun conflict(
        code: PlaybackPreferenceConflictCode,
        affected: String,
    ): Set<PreferenceConflict> = setOf(PreferenceConflict(code.name, affected))

    private fun <T> resolved(
        value: T,
        defaultValue: T,
        impact: ChangeImpact,
    ): PreferenceResolution<T> = PreferenceResolution(
        requested = value,
        effective = value,
        authority = if (value == defaultValue) {
            ResolutionAuthority.DEFAULT_POLICY
        } else {
            ResolutionAuthority.SAVED_USER_OVERRIDE
        },
        availability = PreferenceAvailability.SUPPORTED,
        primaryReason = if (value == defaultValue) {
            PreferenceReason.REQUEST_EFFECTIVE
        } else {
            PreferenceReason.EXPLICIT_USER_OVERRIDE
        },
        impact = impact,
    )
}

package com.nuvio.tv.playback.core

/** The single deterministic policy consumes already-resolved requirements, never raw platform state. */
class PlaybackPolicy {
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

    private fun select(input: SelectionInput, handoff: Boolean): Selection {
        val eligible = input.candidates
            .asSequence()
            .filter(PlaybackGraph::isStructurallyValid)
            .filter { it.engine in input.requirements.eligibleEngines }
            .filterNot { it.engine in input.excludedEngines }
            .filter { !input.requirements.secureOutputRequired || it.secureOutput }
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

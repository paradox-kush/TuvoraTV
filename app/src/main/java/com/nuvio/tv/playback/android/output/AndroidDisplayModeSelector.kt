package com.nuvio.tv.playback.android.output

import com.nuvio.tv.playback.core.VideoDimensions
import kotlin.math.abs
import kotlin.math.max

internal data class AndroidDisplayMode(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

internal data class AndroidDisplayModeSnapshot(
    val currentModeId: Int,
    val supportedModes: List<AndroidDisplayMode>,
)

internal data class AndroidDisplayModeSelectionInput(
    val display: AndroidDisplayModeSnapshot,
    val factualFrameRate: Float?,
    val factualDimensions: VideoDimensions?,
    val resolutionMatchingEnabled: Boolean,
)

internal sealed interface AndroidDisplayModeSelection {
    data class Switch(val mode: AndroidDisplayMode) : AndroidDisplayModeSelection
    data object AlreadyEffective : AndroidDisplayModeSelection
    data object NoCompatibleMode : AndroidDisplayModeSelection
}

/** Pure, deterministic display-mode policy. It consumes engine facts and never probes a stream. */
internal object AndroidDisplayModeSelector {
    private const val MIN_FACTUAL_FRAME_RATE = 10f
    private const val MAX_FACTUAL_FRAME_RATE = 120f
    private const val MIN_REFRESH_TOLERANCE_HZ = 0.08f
    private val fallbackCadenceRatios = floatArrayOf(1f, 2f, 2.5f, 3f, 4f, 5f, 6f)

    fun select(input: AndroidDisplayModeSelectionInput): AndroidDisplayModeSelection {
        val validModes = input.display.supportedModes.filter { it.isValid() }
        val current = validModes.firstOrNull { it.modeId == input.display.currentModeId }
            ?: return AndroidDisplayModeSelection.NoCompatibleMode
        val candidates = selectResolutionCandidates(input, validModes, current)
        if (candidates.isEmpty()) return AndroidDisplayModeSelection.NoCompatibleMode

        val frameRate = input.factualFrameRate
        if (frameRate != null &&
            (!frameRate.isFinite() || frameRate !in MIN_FACTUAL_FRAME_RATE..MAX_FACTUAL_FRAME_RATE)
        ) {
            return AndroidDisplayModeSelection.NoCompatibleMode
        }
        val selected: AndroidDisplayMode = (if (frameRate != null) {
            listOf(1f, 2f, 2.5f)
                .firstNotNullOfOrNull { multiplier ->
                    bestTargetMatch(
                        modes = candidates,
                        target = frameRate * multiplier,
                        currentModeId = current.modeId,
                    )
                }
                ?: candidates.minWithOrNull(
                    compareBy<AndroidDisplayMode>(
                        { fallbackCadenceError(it.refreshRate, frameRate) },
                        { if (it.modeId == current.modeId) 0 else 1 },
                        { it.refreshRate },
                        { it.modeId },
                    ),
                )
        } else if (input.resolutionMatchingEnabled && input.factualDimensions != null) {
            candidates.minWithOrNull(
                compareBy<AndroidDisplayMode>(
                    { abs(it.refreshRate - current.refreshRate) },
                    { if (it.modeId == current.modeId) 0 else 1 },
                    { it.modeId },
                ),
            )
        } else {
            null
        }) ?: return AndroidDisplayModeSelection.NoCompatibleMode

        return if (selected.modeId == current.modeId) {
            AndroidDisplayModeSelection.AlreadyEffective
        } else {
            AndroidDisplayModeSelection.Switch(selected)
        }
    }

    private fun selectResolutionCandidates(
        input: AndroidDisplayModeSelectionInput,
        modes: List<AndroidDisplayMode>,
        current: AndroidDisplayMode,
    ): List<AndroidDisplayMode> {
        val dimensions = input.factualDimensions
        if (!input.resolutionMatchingEnabled || dimensions == null ||
            dimensions.width <= 0 || dimensions.height <= 0
        ) {
            return modes.filter { it.width == current.width && it.height == current.height }
        }

        val target = normalizedSize(dimensions.width, dimensions.height)
        val minimumDistance = modes.minOfOrNull { mode ->
            val size = normalizedSize(mode.width, mode.height)
            squaredDistance(size, target)
        } ?: return emptyList()
        return modes.filter { mode ->
            squaredDistance(normalizedSize(mode.width, mode.height), target) == minimumDistance
        }
    }

    private fun bestTargetMatch(
        modes: List<AndroidDisplayMode>,
        target: Float,
        currentModeId: Int,
    ): AndroidDisplayMode? {
        if (!target.isFinite() || target <= 0f) return null
        val tolerance = max(MIN_REFRESH_TOLERANCE_HZ, target * 0.003f)
        return modes.asSequence()
            .map { it to abs(it.refreshRate - target) }
            .filter { (_, distance) -> distance <= tolerance }
            .minWithOrNull(
                compareBy<Pair<AndroidDisplayMode, Float>>(
                    { it.second },
                    { if (it.first.modeId == currentModeId) 0 else 1 },
                    { it.first.modeId },
                ),
            )
            ?.first
    }

    private fun fallbackCadenceError(refreshRate: Float, frameRate: Float): Float {
        val ratio = refreshRate / frameRate
        if (!ratio.isFinite() || ratio <= 0f) return Float.MAX_VALUE
        return fallbackCadenceRatios.minOf { multiplier -> abs(ratio / multiplier - 1f) }
    }

    private fun AndroidDisplayMode.isValid(): Boolean =
        modeId > 0 && width > 0 && height > 0 && refreshRate.isFinite() && refreshRate > 0f

    private fun normalizedSize(width: Int, height: Int): Pair<Int, Int> =
        if (width >= height) width to height else height to width

    private fun squaredDistance(first: Pair<Int, Int>, second: Pair<Int, Int>): Long {
        val widthDelta = first.first.toLong() - second.first.toLong()
        val heightDelta = first.second.toLong() - second.second.toLong()
        return widthDelta * widthDelta + heightDelta * heightDelta
    }
}

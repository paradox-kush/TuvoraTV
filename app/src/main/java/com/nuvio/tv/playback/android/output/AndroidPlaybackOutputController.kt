package com.nuvio.tv.playback.android.output

import android.app.Activity
import com.nuvio.tv.playback.core.ActiveWorkReleaseReason
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.PlaybackOutputApplication
import com.nuvio.tv.playback.core.PlaybackOutputController
import com.nuvio.tv.playback.core.PlaybackOutputRequest
import com.nuvio.tv.playback.core.PlaybackOutputStatus
import com.nuvio.tv.playback.core.PlaybackResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface AndroidDisplayModeHost {
    fun snapshot(): AndroidDisplayModeSnapshot?
    fun requestMode(modeId: Int)
}

internal class ActivityDisplayModeHost(
    private val activity: Activity,
) : AndroidDisplayModeHost {
    override fun snapshot(): AndroidDisplayModeSnapshot? {
        val display = activity.window.decorView.display ?: return null
        return AndroidDisplayModeSnapshot(
            currentModeId = display.mode.modeId,
            supportedModes = display.supportedModes.map { mode ->
                AndroidDisplayMode(
                    modeId = mode.modeId,
                    width = mode.physicalWidth,
                    height = mode.physicalHeight,
                    refreshRate = mode.refreshRate,
                )
            },
        )
    }

    override fun requestMode(modeId: Int) {
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = modeId
        activity.window.attributes = attributes
    }
}

internal data class DisplayModeVerificationPolicy(
    val pollIntervalMs: Long = 60L,
    val maximumPolls: Int = 26,
    val stablePollsRequired: Int = 2,
) {
    init {
        require(pollIntervalMs >= 0)
        require(maximumPolls > 0)
        require(stablePollsRequired > 0 && stablePollsRequired <= maximumPolls)
    }
}

/**
 * Per-player-host Android display owner. All Window access is serialized on Main; ownership is
 * retained across internal graph work and restored only when the session relinquishes playback.
 */
class AndroidPlaybackOutputController internal constructor(
    private val host: AndroidDisplayModeHost,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val verification: DisplayModeVerificationPolicy = DisplayModeVerificationPolicy(),
) : PlaybackOutputController {
    constructor(activity: Activity) : this(ActivityDisplayModeHost(activity))

    private val mutex = Mutex()
    private var latestGeneration: Long? = null
    private var latestFactsRevision: Long = 0
    private var ownerGeneration: Long? = null
    private var originalModeId: Int? = null
    private var lastRequestedModeId: Int? = null
    private var lastRequestedGeneration: Long? = null
    private var lastRequestedFactsRevision: Long? = null
    private var onStartEffectiveGeneration: Long? = null

    override suspend fun apply(
        request: PlaybackOutputRequest,
    ): PlaybackResult<PlaybackOutputApplication> = withContext(mainDispatcher) {
        mutex.withLock {
            val latest = latestGeneration
            if (latest != null && request.generation < latest) {
                return@withLock success(PlaybackOutputStatus.NOT_REQUESTED)
            }
            if (latest == request.generation && request.facts.revision < latestFactsRevision) {
                return@withLock success(PlaybackOutputStatus.NOT_REQUESTED)
            }
            if (latest != request.generation) {
                latestFactsRevision = 0
                if (originalModeId != null) ownerGeneration = request.generation
            }
            latestGeneration = request.generation
            latestFactsRevision = maxOf(latestFactsRevision, request.facts.revision)

            if (!request.requirements.displayModeSwitchAllowed ||
                (request.requirements.frameRatePreference == FrameRatePreference.OFF &&
                    !request.requirements.resolutionMatchingEnabled)
            ) {
                return@withLock when (val restored = restoreOwnedMode(request.generation)) {
                    RestoreResult.Failed -> success(PlaybackOutputStatus.APPLY_FAILED)
                    RestoreResult.NotConfirmed -> success(PlaybackOutputStatus.APPLY_NOT_CONFIRMED)
                    RestoreResult.Done -> success(PlaybackOutputStatus.DISABLED)
                }
            }
            if (!request.committed) return@withLock success(PlaybackOutputStatus.WAITING_FOR_COMMIT)

            val frameRatePreference = request.requirements.frameRatePreference
            val dimensionsRequired = request.requirements.resolutionMatchingEnabled &&
                (frameRatePreference == FrameRatePreference.OFF ||
                    frameRatePreference == FrameRatePreference.ON_START)
            if (dimensionsRequired && request.facts.dimensions == null) {
                return@withLock success(PlaybackOutputStatus.WAITING_FOR_VIDEO_SIZE)
            }
            val frameRate = request.facts.frameRate
            if (frameRatePreference != FrameRatePreference.OFF &&
                (frameRate == null ||
                    com.nuvio.tv.playback.core.ContentFrameRatePolicy.validOrNull(frameRate) == null)
            ) {
                return@withLock success(PlaybackOutputStatus.WAITING_FOR_FRAME_RATE)
            }
            if (frameRatePreference == FrameRatePreference.ON_START &&
                onStartEffectiveGeneration == request.generation
            ) {
                return@withLock success(PlaybackOutputStatus.ALREADY_EFFECTIVE)
            }

            val snapshot = try {
                host.snapshot()
            } catch (_: Exception) {
                return@withLock success(PlaybackOutputStatus.APPLY_FAILED)
            } ?: return@withLock success(PlaybackOutputStatus.UNSUPPORTED)
            when (
                val selection = AndroidDisplayModeSelector.select(
                    AndroidDisplayModeSelectionInput(
                        display = snapshot,
                        factualFrameRate = frameRate.takeIf {
                            frameRatePreference != FrameRatePreference.OFF
                        },
                        factualDimensions = request.facts.dimensions,
                        resolutionMatchingEnabled = request.requirements.resolutionMatchingEnabled,
                    ),
                )
            ) {
                AndroidDisplayModeSelection.NoCompatibleMode ->
                    success(PlaybackOutputStatus.NO_COMPATIBLE_MODE)

                AndroidDisplayModeSelection.AlreadyEffective -> {
                    markEffective(request.generation)
                    success(PlaybackOutputStatus.ALREADY_EFFECTIVE)
                }

                is AndroidDisplayModeSelection.Switch -> {
                    if (originalModeId == null) originalModeId = snapshot.currentModeId
                    ownerGeneration = request.generation
                    val targetModeId = selection.mode.modeId
                    val requested = requestModeIfNeeded(
                        modeId = targetModeId,
                        generation = request.generation,
                        factsRevision = request.facts.revision,
                    )
                    if (!requested) return@withLock success(PlaybackOutputStatus.APPLY_FAILED)

                    when (verifyMode(targetModeId)) {
                        VerificationResult.Confirmed -> {
                            markEffective(request.generation)
                            success(PlaybackOutputStatus.APPLIED)
                        }
                        VerificationResult.NotConfirmed ->
                            success(PlaybackOutputStatus.APPLY_NOT_CONFIRMED)
                        VerificationResult.Failed -> success(PlaybackOutputStatus.APPLY_FAILED)
                    }
                }
            }
        }
    }

    override suspend fun reset(
        releasedGeneration: Long?,
        reason: ActiveWorkReleaseReason,
    ): PlaybackResult<Unit> = withContext(mainDispatcher) {
        mutex.withLock {
            if (reason in PRESERVE_REASONS) return@withLock PlaybackResult.Success(Unit)
            val owner = ownerGeneration
            if (releasedGeneration != null && owner != null && releasedGeneration != owner) {
                return@withLock PlaybackResult.Success(Unit)
            }
            when (val restored = restoreOwnedMode(releasedGeneration)) {
                RestoreResult.Done, RestoreResult.NotConfirmed, RestoreResult.Failed ->
                    PlaybackResult.Success(Unit)
            }
        }
    }

    private suspend fun restoreOwnedMode(releasedGeneration: Long?): RestoreResult {
        val targetModeId = originalModeId ?: run {
            clearGenerationState(releasedGeneration)
            return RestoreResult.Done
        }
        val snapshot = try {
            host.snapshot()
        } catch (_: Exception) {
            return RestoreResult.Failed
        } ?: return RestoreResult.NotConfirmed
        if (snapshot.supportedModes.none { it.modeId == targetModeId }) {
            clearOwnership()
            return RestoreResult.Done
        }
        if (snapshot.currentModeId == targetModeId) {
            clearOwnership()
            return RestoreResult.Done
        }
        val requested = requestModeIfNeeded(
            modeId = targetModeId,
            generation = releasedGeneration,
            factsRevision = null,
        )
        if (!requested) return RestoreResult.Failed
        return when (verifyMode(targetModeId)) {
            VerificationResult.Confirmed -> {
                clearOwnership()
                RestoreResult.Done
            }
            VerificationResult.NotConfirmed -> {
                clearLastRequest()
                RestoreResult.NotConfirmed
            }
            VerificationResult.Failed -> RestoreResult.Failed
        }
    }

    private fun requestModeIfNeeded(
        modeId: Int,
        generation: Long?,
        factsRevision: Long?,
    ): Boolean {
        if (lastRequestedModeId == modeId &&
            lastRequestedGeneration == generation &&
            lastRequestedFactsRevision == factsRevision
        ) {
            return true
        }
        return try {
            host.requestMode(modeId)
            lastRequestedModeId = modeId
            lastRequestedGeneration = generation
            lastRequestedFactsRevision = factsRevision
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun verifyMode(modeId: Int): VerificationResult {
        var stablePolls = 0
        repeat(verification.maximumPolls) { index ->
            val snapshot = try {
                host.snapshot()
            } catch (_: Exception) {
                return VerificationResult.Failed
            }
            if (snapshot?.currentModeId == modeId) {
                stablePolls += 1
                if (stablePolls >= verification.stablePollsRequired) {
                    return VerificationResult.Confirmed
                }
            } else {
                stablePolls = 0
            }
            if (index < verification.maximumPolls - 1) delay(verification.pollIntervalMs)
        }
        return VerificationResult.NotConfirmed
    }

    private fun markEffective(generation: Long) {
        ownerGeneration = if (originalModeId == null) ownerGeneration else generation
        onStartEffectiveGeneration = generation
    }

    private fun clearGenerationState(generation: Long?) {
        if (generation == null || onStartEffectiveGeneration == generation) {
            onStartEffectiveGeneration = null
        }
    }

    private fun clearOwnership() {
        originalModeId = null
        ownerGeneration = null
        clearLastRequest()
        onStartEffectiveGeneration = null
    }

    private fun clearLastRequest() {
        lastRequestedModeId = null
        lastRequestedGeneration = null
        lastRequestedFactsRevision = null
    }

    private fun success(status: PlaybackOutputStatus) =
        PlaybackResult.Success(PlaybackOutputApplication(status))

    private sealed interface RestoreResult {
        data object Done : RestoreResult
        data object NotConfirmed : RestoreResult
        data object Failed : RestoreResult
    }

    private sealed interface VerificationResult {
        data object Confirmed : VerificationResult
        data object NotConfirmed : VerificationResult
        data object Failed : VerificationResult
    }

    private companion object {
        val PRESERVE_REASONS = setOf(
            ActiveWorkReleaseReason.REBUILD,
            ActiveWorkReleaseReason.RESELECT,
            ActiveWorkReleaseReason.HANDOFF,
            ActiveWorkReleaseReason.SURFACE_LOST,
        )
    }
}

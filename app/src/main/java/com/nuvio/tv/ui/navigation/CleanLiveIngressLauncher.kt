package com.nuvio.tv.ui.navigation

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import com.nuvio.tv.playback.live.LiveChannelTarget
import com.nuvio.tv.playback.live.LiveInitialFailure
import com.nuvio.tv.playback.live.LiveInitialRequest
import com.nuvio.tv.playback.live.LiveInitialResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

enum class CleanLiveIngressFailure {
    INVALID_REQUEST,
    UNAVAILABLE,
    PROFILE_CHANGED,
}

enum class CleanLiveIngressFeedback { INVALID_REQUEST, UNAVAILABLE, PROFILE_CHANGED }

internal object CleanLiveIngressFallbackPolicy {
    fun feedback(reason: CleanLiveIngressFailure): CleanLiveIngressFeedback = when (reason) {
        CleanLiveIngressFailure.INVALID_REQUEST -> CleanLiveIngressFeedback.INVALID_REQUEST
        CleanLiveIngressFailure.UNAVAILABLE -> CleanLiveIngressFeedback.UNAVAILABLE
        CleanLiveIngressFailure.PROFILE_CHANGED -> CleanLiveIngressFeedback.PROFILE_CHANGED
    }
}

sealed interface CleanLiveIngressResult {
    class Ready(val token: CleanLiveLaunchToken) : CleanLiveIngressResult {
        override fun toString(): String = "CleanLiveIngressResult.Ready(token=[REDACTED])"
    }

    data class Rejected(val reason: CleanLiveIngressFailure) : CleanLiveIngressResult
}

internal fun interface CleanLiveIngressProfileSource {
    fun activeProfileId(): Int
}

internal fun interface CleanLiveIngressTargetStore {
    fun put(
        target: LiveChannelTarget,
        activeProfileId: Int,
        origin: CleanLiveLaunchOrigin,
    ): CleanLiveLaunchToken
}

/**
 * URL-free composition boundary shared by future Live TV ingress cutovers. It converts a stable
 * content identity into a one-shot navigation capability without exposing provider transport.
 */
@Singleton
class CleanLiveIngressLauncher private constructor(
    private val profileSource: CleanLiveIngressProfileSource,
    private val selectionPort: LiveChannelSelectionPort,
    private val targetStore: CleanLiveIngressTargetStore,
) {
    @Inject
    internal constructor(
        profileManager: ProfileManager,
        selectionPort: LiveChannelSelectionPort,
        launchStore: CleanLiveLaunchStore,
    ) : this(
        profileSource = CleanLiveIngressProfileSource { profileManager.activeProfileId.value },
        selectionPort = selectionPort,
        targetStore = CleanLiveIngressTargetStore { target, profileId, origin ->
            launchStore.put(
                target = target,
                activeProfileId = profileId,
                origin = origin,
            )
        },
    )

    internal constructor(
        profileSource: CleanLiveIngressProfileSource,
        selectionPort: LiveChannelSelectionPort,
        targetStore: CleanLiveIngressTargetStore,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(profileSource, selectionPort, targetStore)

    suspend fun launch(
        contentId: String,
        origin: CleanLiveLaunchOrigin,
    ): CleanLiveIngressResult {
        val capturedProfileId = profileSource.activeProfileId()
        if (capturedProfileId <= 0) return rejected(CleanLiveIngressFailure.INVALID_REQUEST)

        val request = try {
            LiveInitialRequest(
                contentId = ProviderSelectionId(contentId),
                boundProfileId = PlaybackProfileId(capturedProfileId.toString()),
            )
        } catch (_: IllegalArgumentException) {
            return rejected(CleanLiveIngressFailure.INVALID_REQUEST)
        }

        val selected = try {
            selectionPort.select(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return rejected(CleanLiveIngressFailure.UNAVAILABLE)
        }

        if (profileSource.activeProfileId() != capturedProfileId) {
            return rejected(CleanLiveIngressFailure.PROFILE_CHANGED)
        }

        return when (selected) {
            is LiveInitialResult.Target -> store(
                target = selected.target,
                activeProfileId = capturedProfileId,
                origin = origin,
            )
            is LiveInitialResult.Rejected -> rejected(
                when (selected.reason) {
                    LiveInitialFailure.PROFILE_CHANGED -> CleanLiveIngressFailure.PROFILE_CHANGED
                    LiveInitialFailure.UNAVAILABLE,
                    LiveInitialFailure.INVALID_TARGET,
                    -> CleanLiveIngressFailure.UNAVAILABLE
                },
            )
        }
    }

    private fun store(
        target: LiveChannelTarget,
        activeProfileId: Int,
        origin: CleanLiveLaunchOrigin,
    ): CleanLiveIngressResult = try {
        CleanLiveIngressResult.Ready(targetStore.put(target, activeProfileId, origin))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        rejected(CleanLiveIngressFailure.UNAVAILABLE)
    }

    private fun rejected(reason: CleanLiveIngressFailure) =
        CleanLiveIngressResult.Rejected(reason)
}

/** Thin navigation wrapper; it owns no resolver, provider client, probe, or transport state. */
@HiltViewModel
class CleanLiveIngressViewModel @Inject constructor(
    private val launcher: CleanLiveIngressLauncher,
) : ViewModel() {
    suspend fun launch(
        contentId: String,
        origin: CleanLiveLaunchOrigin,
    ): CleanLiveIngressResult = launcher.launch(contentId, origin)
}

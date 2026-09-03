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

/**
 * What a Live TV ingress does when the tapped content id is NOT classified as a live channel.
 *
 * Content-card ingresses (Search, Library, Folder, See-All) list mixed content, so a non-live id
 * there is a movie/series/collection item: the ingress hands the tap back to its own native-detail
 * route ([Handoff]). A Sports match card, by contrast, is ALWAYS a live channel — there is no
 * non-live detail destination for it, so a non-live id can only mean the channel could not be
 * opened and MUST surface feedback ([Feedback]) rather than a silent dead-click.
 *
 * Extracting the per-origin decision here (rather than leaving the Sports ingress an empty `{}`
 * no-op in the nav host) is what makes "a Live TV ingress can never dead-click" a testable
 * guarantee instead of a per-call-site convention that one caller already forgot.
 */
sealed interface CleanLiveNonLiveOutcome {
    /** Hand the tap to the ingress's own non-live route (native detail). */
    data object Handoff : CleanLiveNonLiveOutcome

    /** No non-live meaning at this ingress — surface this feedback instead of doing nothing. */
    data class Feedback(val feedback: CleanLiveIngressFeedback) : CleanLiveNonLiveOutcome
}

internal object CleanLiveNonLiveDispatchPolicy {
    fun outcome(origin: CleanLiveLaunchOrigin): CleanLiveNonLiveOutcome = when (origin) {
        // A Sports match card resolves to a live channel by construction; a non-live id here is an
        // unopenable request, not a movie/series — so tell the user, never fall silent.
        CleanLiveLaunchOrigin.SPORTS ->
            CleanLiveNonLiveOutcome.Feedback(CleanLiveIngressFeedback.INVALID_REQUEST)
        CleanLiveLaunchOrigin.SEARCH,
        CleanLiveLaunchOrigin.LIBRARY,
        CleanLiveLaunchOrigin.FOLDER,
        CleanLiveLaunchOrigin.CATALOG_SEE_ALL,
        -> CleanLiveNonLiveOutcome.Handoff
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

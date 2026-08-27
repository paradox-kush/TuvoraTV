package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.live.LiveChannelNavigationPort
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import com.nuvio.tv.playback.live.LiveChannelTarget
import com.nuvio.tv.playback.live.LiveInitialFailure
import com.nuvio.tv.playback.live.LiveInitialRequest
import com.nuvio.tv.playback.live.LiveInitialResult
import com.nuvio.tv.playback.live.LiveMediaFingerprint
import com.nuvio.tv.playback.live.LivePlayedHistoryPort
import com.nuvio.tv.playback.live.LivePlayedIdentity
import com.nuvio.tv.playback.live.LiveRelativeFailure
import com.nuvio.tv.playback.live.LiveRelativeRequest
import com.nuvio.tv.playback.live.LiveRelativeResult
import com.nuvio.tv.playback.live.LiveZapDirection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal fun interface ActivePlaybackProfileSource {
    fun currentProfileId(): Int
}

internal fun interface InitialLiveSelectionSource {
    suspend fun select(contentId: String, profileId: Int): IptvIngressSelectionResult
}

internal fun interface RelativeLiveSelectionSource {
    suspend fun relative(
        contentId: String,
        delta: Int,
        profileId: Int,
    ): IptvIngressSelectionResult
}

internal fun interface ExplicitProfileLiveHistorySink {
    suspend fun record(
        profileId: Int,
        contentId: String,
        title: String,
        logo: String?,
    )
}

/**
 * URL-blind TV bridge from provider storage into the clean live-player ports.
 *
 * The profile is fenced before and after initial and relative lookups. Account reads and history
 * writes use the captured numeric profile directly; neither operation follows the mutable
 * active-profile DataStore. Channel lookup only selects stable identity and therefore performs no
 * link minting, media probe, or provider connection.
 */
@Singleton
class IptvLiveChannelBridge internal constructor(
    private val activeProfile: ActivePlaybackProfileSource,
    private val initialSource: InitialLiveSelectionSource,
    private val initialPresentation: IptvInitialLivePresentationReader,
    private val relativeSource: RelativeLiveSelectionSource,
    private val history: ExplicitProfileLiveHistorySink,
) : LiveChannelSelectionPort, LiveChannelNavigationPort, LivePlayedHistoryPort {

    @Inject
    constructor(
        profileManager: ProfileManager,
        ingress: IptvIngressSelectionFactory,
        initialPresentation: IptvInitialLivePresentationReader,
        liveStore: XtreamLiveStore,
    ) : this(
        activeProfile = ActivePlaybackProfileSource { profileManager.activeProfileId.value },
        initialSource = InitialLiveSelectionSource { contentId, profileId ->
            ingress.createForProfile(
                input = IptvIngressSelectionInput(
                    contentId = contentId,
                    contentType = ContentType.LIVE,
                ),
                profileId = profileId,
            )
        },
        initialPresentation = initialPresentation,
        relativeSource = RelativeLiveSelectionSource(ingress::relativeLiveForProfile),
        history = ExplicitProfileLiveHistorySink(liveStore::recordPlayedIdentityForProfile),
    )

    override suspend fun select(request: LiveInitialRequest): LiveInitialResult = try {
        selectChecked(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        initialRejected(LiveInitialFailure.UNAVAILABLE)
    }

    private suspend fun selectChecked(request: LiveInitialRequest): LiveInitialResult {
        val profileId = request.boundProfileId.value.toIntOrNull()?.takeIf { it > 0 }
            ?: return initialRejected(LiveInitialFailure.INVALID_TARGET)
        if (activeProfile.currentProfileId() != profileId) {
            return initialRejected(LiveInitialFailure.PROFILE_CHANGED)
        }

        val providerResult = initialSource.select(request.contentId.value, profileId)
        val presentation = initialPresentation.read(profileId, request.contentId.value)

        if (activeProfile.currentProfileId() != profileId) {
            return initialRejected(LiveInitialFailure.PROFILE_CHANGED)
        }
        val selected = providerResult as? IptvIngressSelectionResult.Selected
            ?: return initialRejected(LiveInitialFailure.UNAVAILABLE)
        if (
            selected.selection.contentType != ContentType.LIVE ||
            selected.selection.contentKey != request.contentId
        ) {
            return initialRejected(LiveInitialFailure.INVALID_TARGET)
        }
        presentation ?: return initialRejected(LiveInitialFailure.UNAVAILABLE)

        return LiveInitialResult.Target(
            LiveChannelTarget.sanitized(
                selection = selected.selection,
                contentId = request.contentId,
                title = presentation.title,
                logo = presentation.logo,
                playlistVersion = null,
                boundProfileId = request.boundProfileId,
            ),
        )
    }

    override suspend fun relative(request: LiveRelativeRequest): LiveRelativeResult = try {
        relativeChecked(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        rejected(LiveRelativeFailure.UNAVAILABLE)
    }

    private suspend fun relativeChecked(request: LiveRelativeRequest): LiveRelativeResult {
        val profileId = request.boundProfileId.value.toIntOrNull()?.takeIf { it > 0 }
            ?: return rejected(LiveRelativeFailure.INVALID_TARGET)
        if (activeProfile.currentProfileId() != profileId) {
            return rejected(LiveRelativeFailure.PROFILE_CHANGED)
        }

        val providerResult = relativeSource.relative(
            contentId = request.currentContentId.value,
            delta = when (request.direction) {
                LiveZapDirection.PREVIOUS -> -1
                LiveZapDirection.NEXT -> 1
            },
            profileId = profileId,
        )

        if (activeProfile.currentProfileId() != profileId) {
            return rejected(LiveRelativeFailure.PROFILE_CHANGED)
        }
        val selected = providerResult as? IptvIngressSelectionResult.Selected
            ?: return rejected(LiveRelativeFailure.UNAVAILABLE)
        val presentation = selected.presentation
            ?: return rejected(LiveRelativeFailure.INVALID_TARGET)
        if (
            selected.selection.contentType != ContentType.LIVE ||
            selected.selection.contentKey != presentation.contentId
        ) {
            return rejected(LiveRelativeFailure.INVALID_TARGET)
        }

        return LiveRelativeResult.Target(
            LiveChannelTarget.sanitized(
                selection = selected.selection,
                contentId = presentation.contentId,
                title = presentation.title,
                logo = presentation.logo,
                playlistVersion = presentation.playlistVersion,
                boundProfileId = request.boundProfileId,
            ),
        )
    }

    override suspend fun record(identity: LivePlayedIdentity) {
        try {
            recordChecked(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // History is best-effort evidence and can never fail otherwise healthy playback.
        }
    }

    private suspend fun recordChecked(identity: LivePlayedIdentity) {
        val profileId = identity.boundProfileId.value.toIntOrNull()?.takeIf { it > 0 } ?: return
        if (activeProfile.currentProfileId() != profileId) return
        val target = identity.target
        if (
            target.selection.contentType != ContentType.LIVE ||
            target.selection.contentKey != target.contentId ||
            target.mediaFingerprint != LiveMediaFingerprint.create(
                target.selection,
                identity.boundProfileId,
            )
        ) {
            return
        }

        history.record(
            profileId = profileId,
            contentId = target.contentId.value,
            title = target.title,
            logo = target.logo,
        )
        // The write is intentionally profile-explicit, so a switch during DataStore I/O cannot
        // leak the row into the new profile. The second read remains a race-observation fence.
        if (activeProfile.currentProfileId() != profileId) return
    }

    private fun rejected(reason: LiveRelativeFailure): LiveRelativeResult =
        LiveRelativeResult.Rejected(reason)

    private fun initialRejected(reason: LiveInitialFailure): LiveInitialResult =
        LiveInitialResult.Rejected(reason)
}

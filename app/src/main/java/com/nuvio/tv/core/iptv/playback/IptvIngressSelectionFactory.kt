package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.LiveChannelPresentation
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.EvidenceFact
import com.nuvio.tv.playback.core.EvidenceProvenance
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderCatchUpWindow
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.StreamEvidence
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal fun interface IngressAccountSource {
    suspend fun currentProfileAccounts(): List<XtreamAccount>
}

internal fun interface IngressProfileAccountSource {
    suspend fun accountsForProfile(profileId: Int): List<XtreamAccount>
}

internal fun interface RelativeLivePresentationSource {
    fun relativePresentation(
        contentId: String,
        delta: Int,
        profileId: Int,
    ): LiveChannelPresentation?
}

/** Secret-bearing input whose string form deliberately exposes only request shape. */
class IptvIngressSelectionInput(
    val contentId: String,
    val contentType: ContentType? = null,
    val catchUpStartEpochMs: Long? = null,
    val catchUpEndEpochMs: Long? = null,
) {
    override fun toString(): String =
        "IptvIngressSelectionInput(contentType=$contentType, hasCatchUpBounds=" +
            "${catchUpStartEpochMs != null || catchUpEndEpochMs != null})"
}

sealed interface IptvIngressSelectionResult {
    class Selected(
        val selection: ProviderPlaybackSelection,
        val presentation: LiveChannelPresentation? = null,
    ) : IptvIngressSelectionResult {
        override fun toString(): String =
            "IptvIngressSelectionResult.Selected($selection, hasPresentation=${presentation != null})"
    }

    data class Rejected(val reason: IptvIngressSelectionFailure) : IptvIngressSelectionResult
}

enum class IptvIngressSelectionFailure {
    MALFORMED_CONTENT_ID,
    ACCOUNT_MISMATCH,
    ACCOUNT_MISSING,
    ACCOUNT_DISABLED,
    SOURCE_UNSUPPORTED,
    CONTENT_TYPE_UNSUPPORTED,
    CONTENT_TYPE_MISMATCH,
    CONTENT_TYPE_DISABLED,
    CATCH_UP_BOUNDS_INVALID,
    CATCH_UP_SOURCE_UNSUPPORTED,
    RELATIVE_CHANNEL_UNAVAILABLE,
    ACCOUNT_STORE_UNAVAILABLE,
}

/**
 * URL-blind ingress shared by Search, Library, Sports, and guide callers. It validates stable
 * catalog identity and returns only an opaque provider selection. Link creation remains behind
 * PlaybackSession's release barrier in [IptvProviderPlaybackResolver].
 */
@Singleton
class IptvIngressSelectionFactory internal constructor(
    private val registry: XtreamItemRegistry,
    private val accounts: IngressAccountSource,
    private val relativeLive: RelativeLivePresentationSource,
    private val profileAccounts: IngressProfileAccountSource,
) {
    @Inject
    constructor(
        registry: XtreamItemRegistry,
        accountStore: XtreamAccountStore,
        livePlaylist: XtreamLivePlaylist,
    ) : this(
        registry = registry,
        accounts = IngressAccountSource { accountStore.accounts.first() },
        relativeLive = RelativeLivePresentationSource { contentId, delta, profileId ->
            livePlaylist.relativePresentation(
                profileId = PlaybackProfileId(profileId.toString()),
                contentId = contentId,
                delta = delta,
            )
        },
        profileAccounts = IngressProfileAccountSource(accountStore::accountsForProfile),
    )

    suspend fun create(input: IptvIngressSelectionInput): IptvIngressSelectionResult = try {
        createChecked(input) { accounts.currentProfileAccounts() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        rejected(IptvIngressSelectionFailure.ACCOUNT_STORE_UNAVAILABLE)
    }

    /** Profile-explicit ingress for a playback owner already bound to one profile. */
    internal suspend fun createForProfile(
        input: IptvIngressSelectionInput,
        profileId: Int,
    ): IptvIngressSelectionResult = try {
        require(profileId > 0) { "Profile id must be positive" }
        createChecked(input) { profileAccounts.accountsForProfile(profileId) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        rejected(IptvIngressSelectionFailure.ACCOUNT_STORE_UNAVAILABLE)
    }

    suspend fun relativeLive(
        contentId: String,
        delta: Int,
        profileId: Int,
    ): IptvIngressSelectionResult = relativeLiveChecked(contentId, delta, profileId) { input ->
        createForProfile(input, profileId)
    }

    /** Profile-explicit relative lookup for the clean live playback owner. */
    internal suspend fun relativeLiveForProfile(
        contentId: String,
        delta: Int,
        profileId: Int,
    ): IptvIngressSelectionResult = relativeLiveChecked(contentId, delta, profileId) { input ->
        createForProfile(input, profileId)
    }

    private suspend fun relativeLiveChecked(
        contentId: String,
        delta: Int,
        profileId: Int,
        createSelection: suspend (IptvIngressSelectionInput) -> IptvIngressSelectionResult,
    ): IptvIngressSelectionResult {
        if (delta == 0 || profileId <= 0) {
            return rejected(IptvIngressSelectionFailure.RELATIVE_CHANNEL_UNAVAILABLE)
        }
        val presentation = relativeLive.relativePresentation(contentId, delta, profileId)
            ?: return rejected(IptvIngressSelectionFailure.RELATIVE_CHANNEL_UNAVAILABLE)
        val result = createSelection(
            IptvIngressSelectionInput(
                presentation.contentId.value,
                contentType = ContentType.LIVE,
            ),
        )
        return if (result is IptvIngressSelectionResult.Selected) {
            if (result.selection.contentKey == presentation.contentId) {
                IptvIngressSelectionResult.Selected(result.selection, presentation)
            } else {
                rejected(IptvIngressSelectionFailure.RELATIVE_CHANNEL_UNAVAILABLE)
            }
        } else {
            result
        }
    }

    private suspend fun createChecked(
        input: IptvIngressSelectionInput,
        availableAccounts: suspend () -> List<XtreamAccount>,
    ): IptvIngressSelectionResult {
        val contentId = input.contentId
        if (contentId.isBlank() || contentId.length > MAX_CONTENT_ID_LENGTH) {
            return rejected(IptvIngressSelectionFailure.MALFORMED_CONTENT_ID)
        }
        val parsed = XtreamItemRegistry.parseId(contentId)
            ?: return rejected(IptvIngressSelectionFailure.MALFORMED_CONTENT_ID)
        val bounds = validatedBounds(input) ?: if (
            input.catchUpStartEpochMs != null ||
            input.catchUpEndEpochMs != null ||
            input.contentType == ContentType.CATCH_UP
        ) {
            return rejected(IptvIngressSelectionFailure.CATCH_UP_BOUNDS_INVALID)
        } else {
            null
        }
        val inferredType = when (parsed.kind) {
            "live" -> if (bounds == null) ContentType.LIVE else ContentType.CATCH_UP
            "vod" -> ContentType.VOD
            else -> return rejected(IptvIngressSelectionFailure.CONTENT_TYPE_UNSUPPORTED)
        }
        val streamId = validatedStreamId(parsed, bounds)
            ?: return rejected(IptvIngressSelectionFailure.MALFORMED_CONTENT_ID)
        val requestedType = input.contentType ?: inferredType
        if (requestedType != inferredType) {
            return rejected(IptvIngressSelectionFailure.CONTENT_TYPE_MISMATCH)
        }

        val registered = registry.get(contentId)
        if (registered != null && !registered.matches(parsed.accountId, parsed.kind, streamId)) {
            return rejected(IptvIngressSelectionFailure.ACCOUNT_MISMATCH)
        }
        val account = availableAccounts().firstOrNull { it.id == parsed.accountId }
            ?: return rejected(IptvIngressSelectionFailure.ACCOUNT_MISSING)
        if (!account.enabled) return rejected(IptvIngressSelectionFailure.ACCOUNT_DISABLED)
        val sourceType = account.providerSourceType()
            ?: return rejected(IptvIngressSelectionFailure.SOURCE_UNSUPPORTED)
        if (requestedType == ContentType.CATCH_UP && sourceType != ProviderSourceType.XTREAM) {
            return rejected(IptvIngressSelectionFailure.CATCH_UP_SOURCE_UNSUPPORTED)
        }
        val accountType = if (requestedType == ContentType.VOD) {
            XtreamAccount.TYPE_MOVIES
        } else {
            XtreamAccount.TYPE_LIVE
        }
        if (!account.typeEnabled(accountType)) {
            return rejected(IptvIngressSelectionFailure.CONTENT_TYPE_DISABLED)
        }

        return IptvIngressSelectionResult.Selected(
            ProviderPlaybackSelection(
                sourceType = sourceType,
                accountId = ProviderSelectionId(account.id),
                itemId = ProviderSelectionId(streamId.toString()),
                contentKey = ProviderSelectionId(contentId),
                contentType = requestedType,
                catchUpWindow = bounds,
                providerConnectionLimit = DEFAULT_PROVIDER_CONNECTION_LIMIT,
                declaredEvidence = declaredEvidence(sourceType, requestedType),
            ),
        )
    }

    private fun validatedBounds(input: IptvIngressSelectionInput): ProviderCatchUpWindow? {
        val start = input.catchUpStartEpochMs ?: return null
        val end = input.catchUpEndEpochMs ?: return null
        return if (start > 0 && end > start) ProviderCatchUpWindow(start, end) else null
    }

    private fun validatedStreamId(
        parsed: XtreamItemRegistry.ParsedId,
        bounds: ProviderCatchUpWindow?,
    ): Int? {
        val match = when (parsed.kind) {
            "live" -> LIVE_STREAM_ID.matchEntire(parsed.streamId)
            "vod" -> VOD_STREAM_ID.matchEntire(parsed.streamId)
            else -> null
        } ?: return null
        val streamId = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val catchUpMinute = match.groupValues.getOrNull(2)?.takeIf(String::isNotEmpty)?.toLongOrNull()
        if (catchUpMinute != null && catchUpMinute != bounds?.startEpochMs?.div(60_000L)) return null
        if (catchUpMinute != null && bounds == null) return null
        return streamId
    }

    private fun XtreamResolvedItem.matches(
        accountId: String,
        kind: String,
        streamId: Int,
    ): Boolean {
        val expectedKind = when (kind) {
            "live" -> XtreamKind.LIVE
            "vod" -> XtreamKind.VOD
            else -> return false
        }
        return (this.accountId.isBlank() || this.accountId == accountId) &&
            (this.streamId <= 0 || this.streamId == streamId) &&
            this.kind == expectedKind
    }

    private fun XtreamAccount.providerSourceType(): ProviderSourceType? = when (sourceType) {
        XtreamAccount.SOURCE_XTREAM -> ProviderSourceType.XTREAM
        XtreamAccount.SOURCE_URL,
        XtreamAccount.SOURCE_FILE,
        -> ProviderSourceType.M3U
        XtreamAccount.SOURCE_STALKER -> ProviderSourceType.STALKER
        else -> null
    }

    private fun declaredEvidence(
        sourceType: ProviderSourceType,
        contentType: ContentType,
    ): StreamEvidence = when {
        sourceType == ProviderSourceType.XTREAM && contentType == ContentType.LIVE -> StreamEvidence(
            delivery = EvidenceFact(
                DeliveryType.RAW_TRANSPORT_STREAM,
                EvidenceProvenance.PROVIDER_DECLARED,
            ),
            container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.PROVIDER_DECLARED),
        )
        sourceType == ProviderSourceType.XTREAM && contentType == ContentType.VOD -> StreamEvidence(
            delivery = EvidenceFact(DeliveryType.PROGRESSIVE, EvidenceProvenance.PROVIDER_DECLARED),
            container = EvidenceFact(ContainerType.MP4, EvidenceProvenance.PROVIDER_DECLARED),
        )
        else -> StreamEvidence()
    }

    private fun rejected(reason: IptvIngressSelectionFailure) =
        IptvIngressSelectionResult.Rejected(reason)

    private companion object {
        const val DEFAULT_PROVIDER_CONNECTION_LIMIT = 1
        const val MAX_CONTENT_ID_LENGTH = 4_096
        val LIVE_STREAM_ID = Regex("([1-9][0-9]*)(?:r([0-9]+))?")
        val VOD_STREAM_ID = Regex("([1-9][0-9]*)")
    }
}

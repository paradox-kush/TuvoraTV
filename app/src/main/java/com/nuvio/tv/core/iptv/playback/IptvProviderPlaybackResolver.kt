package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.iptv.CatchUpDialectWalk
import com.nuvio.tv.core.iptv.CatchUpWinnerStore
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.dns.DnsProviderEndpoint
import com.nuvio.tv.core.iptv.stalker.StalkerClient
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.ApplicationDnsKey
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.ProviderDialectAdvanceEligibility
import com.nuvio.tv.playback.core.ProviderPlaybackResolver
import com.nuvio.tv.playback.core.ProviderPlaybackResolverFactory
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderResolutionContext
import com.nuvio.tv.playback.core.ProviderResolutionTrigger
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.RedirectPolicy
import com.nuvio.tv.playback.core.ResolvedPlaybackRequest
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.SecretValue
import com.nuvio.tv.playback.core.TlsPolicy
import com.nuvio.tv.playback.wiring.CompatibilityScopeInput
import com.nuvio.tv.playback.wiring.CompatibilityScopeKeyFactory
import com.nuvio.tv.playback.wiring.NavigationPlaybackInput
import com.nuvio.tv.playback.wiring.PlaybackRequestMapper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface ProviderAccountLookup {
    suspend fun find(accountId: String): XtreamAccount?
}

internal fun interface ProviderAccountLookupFactory {
    fun create(profileId: PlaybackProfileId): ProviderAccountLookup
}

internal fun interface ProviderLinkSource {
    suspend fun resolve(
        account: XtreamAccount,
        kind: String,
        streamId: Int,
        forceFresh: Boolean,
    ): ProviderLinkResult
}

internal sealed interface ProviderLinkResult {
    class Resolved(val url: String) : ProviderLinkResult {
        override fun toString(): String = "ProviderLinkResult.Resolved(hasUrl=true)"
    }

    data class Unavailable(val reason: ProviderLinkFailureReason) : ProviderLinkResult
}

internal enum class ProviderLinkFailureReason { SESSION_LIMIT, LINK_FAULT, UNKNOWN }

/** Production resolver factory whose returned owner can read only its captured profile's accounts. */
@Singleton
class IptvProviderPlaybackResolverFactory internal constructor(
    private val accountLookups: ProviderAccountLookupFactory,
    private val links: ProviderLinkSource,
    private val winnerMemory: CatchUpDialectWalk.WinnerMemory,
) : ProviderPlaybackResolverFactory {

    @Inject
    constructor(
        accountStore: XtreamAccountStore,
        clientFactory: IptvClientFactory,
        winnerStore: CatchUpWinnerStore,
    ) : this(
        accountLookups = ProviderAccountLookupFactory { profileId ->
            val persistedProfileId = profileId.value.toIntOrNull()?.takeIf { it > 0 }
            ProviderAccountLookup { accountId ->
                persistedProfileId?.let { accountStore.findForProfile(it, accountId) }
            }
        },
        links = ProviderLinkSource { account, kind, streamId, forceFresh ->
            val client = clientFactory.clientFor(account)
            val url = client.resolveStreamUrl(account, kind, streamId, forceFresh)
            if (!url.isNullOrBlank()) {
                ProviderLinkResult.Resolved(url)
            } else {
                val reason = when (client.lastResolveError) {
                    StalkerClient.SESSION_LIMIT_MESSAGE -> ProviderLinkFailureReason.SESSION_LIMIT
                    StalkerClient.LINK_FAULT_MESSAGE -> ProviderLinkFailureReason.LINK_FAULT
                    else -> ProviderLinkFailureReason.UNKNOWN
                }
                ProviderLinkResult.Unavailable(reason)
            }
        },
        winnerMemory = winnerStore,
    )

    override fun create(profileId: PlaybackProfileId): ProviderPlaybackResolver =
        IptvProviderPlaybackResolver(
            accounts = accountLookups.create(profileId),
            links = links,
            winnerMemory = winnerMemory,
        )
}

/**
 * Production bridge from an opaque clean-player selection to the existing IPTV source APIs.
 *
 * It performs no media probe. The owning [com.nuvio.tv.playback.core.PlaybackSession] invokes this
 * port only after its release barrier, so Stalker create_link and catch-up dialect URLs cannot be
 * minted while the previous channel still owns the provider connection.
 */
class IptvProviderPlaybackResolver internal constructor(
    private val accounts: ProviderAccountLookup,
    private val links: ProviderLinkSource,
    winnerMemory: CatchUpDialectWalk.WinnerMemory,
) : ProviderPlaybackResolver {

    private val mapper = PlaybackRequestMapper()
    private val catchUpWalk = CatchUpDialectWalk(winnerMemory)
    private val resolutionMutex = Mutex()
    private val activeCatchUpByAccount = mutableMapOf<String, ActiveCatchUp>()

    override suspend fun resolve(
        selection: ProviderPlaybackSelection,
        context: ProviderResolutionContext,
    ): PlaybackResult<ResolvedPlaybackRequest> = try {
        resolutionMutex.withLock { resolveSerialized(selection, context) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        unavailable(deterministic = false)
    }

    private suspend fun resolveSerialized(
        selection: ProviderPlaybackSelection,
        context: ProviderResolutionContext,
    ): PlaybackResult<ResolvedPlaybackRequest> {
        val account = accounts.find(selection.accountId.value)
            ?: return unavailable(deterministic = true)
        if (!account.enabled || !account.matches(selection.sourceType) || !account.enables(selection.contentType)) {
            return unavailable(deterministic = true)
        }
        val streamId = validatedStreamId(selection) ?: return unavailable(deterministic = true)

        return if (selection.contentType == ContentType.CATCH_UP) {
            resolveCatchUp(account, selection, streamId, context)
        } else {
            resolveLiveOrVod(account, selection, streamId, context)
        }
    }

    private suspend fun resolveLiveOrVod(
        account: XtreamAccount,
        selection: ProviderPlaybackSelection,
        streamId: Int,
        context: ProviderResolutionContext,
    ): PlaybackResult<ResolvedPlaybackRequest> {
        val kind = if (selection.contentType == ContentType.LIVE) "live" else "movie"
        return when (
            val link = links.resolve(
                account = account,
                kind = kind,
                streamId = streamId,
                forceFresh = context.trigger != ProviderResolutionTrigger.INITIAL,
            )
        ) {
            is ProviderLinkResult.Resolved -> mapped(account, selection, link.url, dialect = null)
            is ProviderLinkResult.Unavailable -> unavailable(link.reason)
        }
    }

    private fun resolveCatchUp(
        account: XtreamAccount,
        selection: ProviderPlaybackSelection,
        streamId: Int,
        context: ProviderResolutionContext,
    ): PlaybackResult<ResolvedPlaybackRequest> {
        if (selection.sourceType != ProviderSourceType.XTREAM || account.sourceType != XtreamAccount.SOURCE_XTREAM) {
            return unavailable(deterministic = true)
        }
        val window = selection.catchUpWindow ?: return unavailable(deterministic = true)
        val request = CatchUpDialectWalk.Request(
            accountId = account.id,
            baseUrl = account.baseUrl,
            username = account.username,
            password = account.password,
            streamId = streamId,
            startMs = window.startEpochMs,
            endMs = window.endEpochMs,
            preferM3u8 = account.preferM3u8CatchUp,
            serverOffsetMs = account.catchUpOffsetMs,
        )
        val key = CatchUpSelectionKey(selection)
        val previous = activeCatchUpByAccount[account.id]
        val step = if (
            context.previousFailure?.dialectAdvanceEligibility ==
            ProviderDialectAdvanceEligibility.TRANSPORT_OR_DEMUX_FAILURE
        ) {
            if (previous == null || previous.key != key || previous.request != request) {
                return unavailable(deterministic = false)
            }
            catchUpWalk.onFailure(previous.attempt.token, CatchUpDialectWalk.FailureKind.TRANSPORT)
        } else {
            // Decoder/render/audio/DRM handoff may remint, but it must keep the same dialect.
            catchUpWalk.begin(request)
        }
        return when (step) {
            is CatchUpDialectWalk.Step.Next -> {
                activeCatchUpByAccount[account.id] = ActiveCatchUp(key, request, step.attempt)
                mapped(account, selection, step.attempt.url, step.attempt.dialect)
            }
            CatchUpDialectWalk.Step.Done,
            CatchUpDialectWalk.Step.Stale,
            CatchUpDialectWalk.Step.Unavailable,
            -> {
                activeCatchUpByAccount.remove(account.id)
                unavailable(deterministic = false)
            }
        }
    }

    private fun mapped(
        account: XtreamAccount,
        selection: ProviderPlaybackSelection,
        url: String,
        dialect: CatchUpDialectWalk.Dialect?,
    ): PlaybackResult<ResolvedPlaybackRequest> {
        if (url.isBlank()) return unavailable(deterministic = true)
        val declared = declaredTransport(selection, dialect)
        val dnsPolicy = account.cleanDnsPolicy()
        val mapped = mapper.map(
            NavigationPlaybackInput(
                url = url,
                userAgent = com.nuvio.tv.core.iptv.StreamUserAgentPolicy.resolve(account),
                redirectPolicy = RedirectPolicy.FOLLOW,
                crossHostAuthorization = CrossHostAuthorization.STRIP,
                tlsPolicy = TlsPolicy.PLATFORM_DEFAULT,
                dnsPolicy = dnsPolicy,
                applicationDnsKey = account.dnsProvider
                    .takeIf { dnsPolicy == DnsPolicy.SHARED_APPLICATION_RESOLVER }
                    ?.let(::ApplicationDnsKey),
                contentType = selection.contentType,
                contentKey = SecretValue(selection.contentKey.value),
                providerConnectionLimit = selection.providerConnectionLimit,
                providerDeclaredDelivery = declared.first,
                providerDeclaredContainer = declared.second,
                existingEvidence = selection.declaredEvidence,
            ),
        )
        val compatibilityScopeKey = CompatibilityScopeKeyFactory.create(
            CompatibilityScopeInput(
                providerScope = account.connectionIdentityScope(),
                streamScope = selection.streamIdentityScope(),
                contentType = selection.contentType,
                delivery = mapped.evidence.delivery?.value,
                container = mapped.evidence.container?.value,
                videoCodec = mapped.evidence.videoCodec?.value,
                audioCodec = mapped.evidence.audioCodec?.value,
            ),
        )
        return PlaybackResult.Success(
            ResolvedPlaybackRequest(
                request = mapped.request,
                summary = mapped.request.summary(),
                evidence = mapped.evidence,
                compatibilityScopeKey = compatibilityScopeKey,
            ),
        )
    }

    private fun declaredTransport(
        selection: ProviderPlaybackSelection,
        dialect: CatchUpDialectWalk.Dialect?,
    ): Pair<DeliveryType?, ContainerType?> = when {
        dialect?.container == CatchUpDialectWalk.Container.TS ->
            DeliveryType.RAW_TRANSPORT_STREAM to ContainerType.MPEG_TS
        dialect?.container == CatchUpDialectWalk.Container.M3U8 -> DeliveryType.HLS to null
        dialect != null -> null to null
        selection.sourceType == ProviderSourceType.XTREAM && selection.contentType == ContentType.LIVE ->
            DeliveryType.RAW_TRANSPORT_STREAM to ContainerType.MPEG_TS
        selection.sourceType == ProviderSourceType.XTREAM && selection.contentType == ContentType.VOD ->
            DeliveryType.PROGRESSIVE to ContainerType.MP4
        else -> null to null
    }

    private fun validatedStreamId(selection: ProviderPlaybackSelection): Int? {
        val streamId = selection.itemId.value.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val parsed = XtreamItemRegistry.parseId(selection.contentKey.value) ?: return null
        if (parsed.accountId != selection.accountId.value) return null
        val expectedKind = if (selection.contentType == ContentType.VOD) "vod" else "live"
        if (parsed.kind != expectedKind) return null
        val exact = parsed.streamId == streamId.toString()
        val catchUp = selection.catchUpWindow?.let { window ->
            parsed.streamId == "${streamId}r${window.startEpochMs / 60_000L}"
        } == true
        return streamId.takeIf { exact || catchUp }
    }

    private fun XtreamAccount.matches(source: ProviderSourceType): Boolean = when (source) {
        ProviderSourceType.XTREAM -> sourceType == XtreamAccount.SOURCE_XTREAM
        ProviderSourceType.M3U -> isM3UBacked()
        ProviderSourceType.STALKER -> sourceType == XtreamAccount.SOURCE_STALKER
    }

    private fun XtreamAccount.enables(contentType: ContentType): Boolean = when (contentType) {
        ContentType.LIVE, ContentType.CATCH_UP -> typeEnabled(XtreamAccount.TYPE_LIVE)
        ContentType.VOD -> typeEnabled(XtreamAccount.TYPE_MOVIES)
    }

    private fun XtreamAccount.isM3UBacked(): Boolean =
        sourceType == XtreamAccount.SOURCE_URL || sourceType == XtreamAccount.SOURCE_FILE

    private fun XtreamAccount.cleanDnsPolicy(): DnsPolicy =
        if (DnsProviderEndpoint.forProvider(dnsProvider) != null) {
            DnsPolicy.SHARED_APPLICATION_RESOLVER
        } else {
            DnsPolicy.SYSTEM
        }

    /** Mirrors [XtreamAccount.sameConnectionAs] without retaining or exposing its raw components. */
    private fun XtreamAccount.connectionIdentityScope(): String = exactScope(
        sourceType,
        baseUrl,
        username,
        password,
        portalUrl,
        macAddress,
        stalkerUsername,
        stalkerPassword,
        serialNumber,
        deviceId,
        sendDeviceId.toString(),
    )

    private fun ProviderPlaybackSelection.streamIdentityScope(): String = exactScope(
        itemId.value,
        contentKey.value,
    )

    /** Length framing prevents distinct provider values from colliding before the SHA-256 boundary. */
    private fun exactScope(vararg components: String): String = buildString {
        components.forEach { component ->
            append(component.length)
            append(':')
            append(component)
        }
    }

    private fun unavailable(reason: ProviderLinkFailureReason): PlaybackResult.Failure = when (reason) {
        ProviderLinkFailureReason.SESSION_LIMIT -> PlaybackResult.Failure(
            PlaybackFailure(
                code = FailureCode.PROVIDER_CONNECTION_LIMIT,
                domain = FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
                phase = FailurePhase.REQUEST_RESOLUTION,
                retryability = Retryability.FATAL,
                deterministic = true,
            ),
        )
        ProviderLinkFailureReason.LINK_FAULT -> unavailable(deterministic = true)
        ProviderLinkFailureReason.UNKNOWN -> unavailable(deterministic = false)
    }

    private fun unavailable(deterministic: Boolean): PlaybackResult.Failure = PlaybackResult.Failure(
        PlaybackFailure(
            code = FailureCode.UNKNOWN,
            domain = FailureDomain.UNKNOWN,
            phase = FailurePhase.REQUEST_RESOLUTION,
            retryability = Retryability.FATAL,
            deterministic = deterministic,
        ),
    )

    private class CatchUpSelectionKey(selection: ProviderPlaybackSelection) {
        private val accountId = selection.accountId.value
        private val itemId = selection.itemId.value
        private val contentKey = selection.contentKey.value
        private val start = selection.catchUpWindow?.startEpochMs
        private val end = selection.catchUpWindow?.endEpochMs

        override fun equals(other: Any?): Boolean = other is CatchUpSelectionKey &&
            accountId == other.accountId && itemId == other.itemId && contentKey == other.contentKey &&
            start == other.start && end == other.end

        override fun hashCode(): Int {
            var result = accountId.hashCode()
            result = 31 * result + itemId.hashCode()
            result = 31 * result + contentKey.hashCode()
            result = 31 * result + (start?.hashCode() ?: 0)
            result = 31 * result + (end?.hashCode() ?: 0)
            return result
        }
        override fun toString(): String = "CatchUpSelectionKey([REDACTED])"
    }

    private data class ActiveCatchUp(
        val key: CatchUpSelectionKey,
        val request: CatchUpDialectWalk.Request,
        val attempt: CatchUpDialectWalk.Attempt,
    ) {
        override fun toString(): String = "ActiveCatchUp(key=$key, hasRequest=true, hasAttempt=true)"
    }
}

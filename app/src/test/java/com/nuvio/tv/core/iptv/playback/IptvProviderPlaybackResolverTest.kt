package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.iptv.CatchUpDialectWalk
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderCatchUpWindow
import com.nuvio.tv.playback.core.ProviderDialectAdvanceEligibility
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderResolutionContext
import com.nuvio.tv.playback.core.ProviderResolutionFeedback
import com.nuvio.tv.playback.core.ProviderResolutionTrigger
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.ResolvedPlaybackRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvProviderPlaybackResolverTest {

    @Test
    fun `factory captures the exact playback profile before any provider resolution`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_XTREAM)
        val capturedProfiles = mutableListOf<PlaybackProfileId>()
        val factory = IptvProviderPlaybackResolverFactory(
            accountLookups = ProviderAccountLookupFactory { profileId ->
                capturedProfiles += profileId
                ProviderAccountLookup { id -> account.takeIf { it.id == id } }
            },
            links = ProviderLinkSource { _, _, streamId, _ ->
                ProviderLinkResult.Resolved("https://media.invalid/live/$streamId.ts")
            },
            winnerMemory = Memory(),
        )
        val captured = PlaybackProfileId("17")
        val resolver = factory.create(captured)

        val resolved = resolver.resolve(
            selection(account, ProviderSourceType.XTREAM, ContentType.LIVE),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        )

        assertTrue(resolved is PlaybackResult.Success)
        assertEquals(listOf(captured), capturedProfiles)
        assertFalse(captured.toString().contains(captured.value))
    }

    @Test
    fun `Xtream live maps stable TS transport identity DNS and fresh trigger`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_XTREAM, dns = XtreamAccount.DNS_CLOUDFLARE)
        val calls = mutableListOf<LinkCall>()
        val secretUrl = "https://provider.invalid/live/user/password/42.ts"
        val resolver = resolver(account) { acc, kind, streamId, forceFresh ->
            calls += LinkCall(acc.id, kind, streamId, forceFresh)
            ProviderLinkResult.Resolved(secretUrl)
        }
        val selection = selection(account, ProviderSourceType.XTREAM, ContentType.LIVE)

        val initial = resolver.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL)).success()
        val recovered = resolver.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.RECOVERY)).success()

        assertEquals(listOf(false, true), calls.map(LinkCall::forceFresh))
        assertEquals("live", calls.single { !it.forceFresh }.kind)
        assertEquals(42, calls.first().streamId)
        assertEquals(DnsPolicy.SHARED_APPLICATION_RESOLVER, initial.request.dnsPolicy)
        assertEquals(account.dnsProvider, initial.request.applicationDnsKey?.value)
        assertFalse(initial.request.applicationDnsKey.toString().contains(account.dnsProvider))
        assertEquals(selection.contentKey.value, initial.request.contentKey?.value)
        assertEquals(1, initial.request.providerConnectionLimit)
        assertEquals(DeliveryType.RAW_TRANSPORT_STREAM, initial.evidence.delivery?.value)
        assertEquals(ContainerType.MPEG_TS, initial.evidence.container?.value)
        assertEquals(secretUrl, recovered.request.url)
        assertFalse(initial.toString().contains(secretUrl))
    }

    @Test
    fun `M3U live preserves arbitrary media URL and playlist user agent without inventing headers`() = runTest {
        val account = account(
            id = "m3u:https://playlist.invalid/list.m3u",
            sourceType = XtreamAccount.SOURCE_URL,
            username = "Playlist UA/1.0",
        )
        val rawUrl = "udp://239.10.10.10:1234"
        val resolver = resolver(account) { _, _, _, _ -> ProviderLinkResult.Resolved(rawUrl) }

        val resolved = resolver.resolve(
            selection(account, ProviderSourceType.M3U, ContentType.LIVE, streamId = 7),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ).success()

        assertEquals(rawUrl, resolved.request.url)
        assertEquals("Playlist UA/1.0", resolved.request.userAgent)
        assertEquals(DnsPolicy.SYSTEM, resolved.request.dnsPolicy)
        assertNull(resolved.request.applicationDnsKey)
        assertTrue(resolved.request.headers.isEmpty())
        assertTrue(resolved.request.cookies.isEmpty())
        assertNull(resolved.request.referer)
        assertEquals(DeliveryType.UDP, resolved.evidence.delivery?.value)
    }

    @Test
    fun `Stalker session limit remains typed and no provider text crosses the port`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val resolver = resolver(account) { _, _, _, _ ->
            ProviderLinkResult.Unavailable(ProviderLinkFailureReason.SESSION_LIMIT)
        }

        val result = resolver.resolve(
            selection(account, ProviderSourceType.STALKER, ContentType.LIVE),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ) as PlaybackResult.Failure

        assertEquals(FailureCode.PROVIDER_CONNECTION_LIMIT, result.failure.code)
        assertEquals(FailureDomain.AUTHORIZATION_PROVIDER_LIMIT, result.failure.domain)
        assertEquals(FailurePhase.REQUEST_RESOLUTION, result.failure.phase)
        assertFalse(result.toString().contains(account.portalUrl))
    }

    @Test
    fun `catch-up advances only on eligible transport feedback and handoff keeps the dialect`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_XTREAM)
        var liveResolveCalls = 0
        val resolver = resolver(account) { _, _, _, _ ->
            liveResolveCalls++
            ProviderLinkResult.Unavailable(ProviderLinkFailureReason.UNKNOWN)
        }
        val window = ProviderCatchUpWindow(1_700_000_000_000L, 1_700_003_600_000L)
        val selection = selection(
            account = account,
            source = ProviderSourceType.XTREAM,
            contentType = ContentType.CATCH_UP,
            window = window,
        )

        val first = resolver.resolve(
            selection,
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ).success()
        val second = resolver.resolve(
            selection,
            ProviderResolutionContext(
                trigger = ProviderResolutionTrigger.RECOVERY,
                previousFailure = feedback(ProviderDialectAdvanceEligibility.TRANSPORT_OR_DEMUX_FAILURE),
            ),
        ).success()
        val handoff = resolver.resolve(
            selection,
            ProviderResolutionContext(
                trigger = ProviderResolutionTrigger.HANDOFF,
                previousFailure = feedback(ProviderDialectAdvanceEligibility.INELIGIBLE_PLAYBACK_FAILURE),
            ),
        ).success()

        assertEquals(0, liveResolveCalls)
        assertNotEquals(first.request.url, second.request.url)
        assertEquals(second.request.url, handoff.request.url)
        assertEquals(DeliveryType.RAW_TRANSPORT_STREAM, second.evidence.delivery?.value)
        assertEquals(ContainerType.MPEG_TS, second.evidence.container?.value)
    }

    @Test
    fun `catch-up honors M3U8 account preference and rejects unsupported M3U archives`() = runTest {
        val xtream = account(sourceType = XtreamAccount.SOURCE_XTREAM, preferM3u8 = true)
        val xtreamResolver = resolver(xtream) { _, _, _, _ -> error("catch-up must not use live resolver") }
        val window = ProviderCatchUpWindow(1_700_000_000_000L, 1_700_003_600_000L)

        val hls = xtreamResolver.resolve(
            selection(xtream, ProviderSourceType.XTREAM, ContentType.CATCH_UP, window = window),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ).success()

        assertTrue(hls.request.url.contains("m3u8"))
        assertEquals(DeliveryType.HLS, hls.evidence.delivery?.value)

        val m3u = account(id = "m3u:https://playlist.invalid/list", sourceType = XtreamAccount.SOURCE_URL)
        val unsupported = resolver(m3u) { _, _, _, _ -> error("unsupported catch-up must fail first") }.resolve(
            selection(m3u, ProviderSourceType.M3U, ContentType.CATCH_UP, window = window),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        )
        assertTrue(unsupported is PlaybackResult.Failure)
    }

    @Test
    fun `source and content identity mismatches fail before any provider call`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_XTREAM)
        var calls = 0
        val resolver = resolver(account) { _, _, _, _ ->
            calls++
            ProviderLinkResult.Resolved("https://provider.invalid/live.ts")
        }
        val wrongSource = selection(account, ProviderSourceType.STALKER, ContentType.LIVE)
        val wrongContent = ProviderPlaybackSelection(
            sourceType = ProviderSourceType.XTREAM,
            accountId = ProviderSelectionId(account.id),
            itemId = ProviderSelectionId("42"),
            contentKey = ProviderSelectionId(XtreamItemRegistry.liveId(account.id, 99)),
            contentType = ContentType.LIVE,
        )

        assertTrue(
            resolver.resolve(wrongSource, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL))
                is PlaybackResult.Failure,
        )
        assertTrue(
            resolver.resolve(wrongContent, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL))
                is PlaybackResult.Failure,
        )
        assertEquals(0, calls)
    }

    @Test
    fun `compatibility scope is stable for equivalent selections and differs by stream`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_XTREAM)
        val resolver = resolver(account) { _, _, streamId, _ ->
            ProviderLinkResult.Resolved("https://media.invalid/live/$streamId.ts")
        }
        val context = ProviderResolutionContext(ProviderResolutionTrigger.INITIAL)

        val first = resolver.resolve(
            selection(account, ProviderSourceType.XTREAM, ContentType.LIVE, streamId = 42),
            context,
        ).success()
        val equivalent = resolver.resolve(
            selection(
                account.copy(name = "Renamed"),
                ProviderSourceType.XTREAM,
                ContentType.LIVE,
                streamId = 42,
            ),
            context,
        ).success()
        val distinct = resolver.resolve(
            selection(account, ProviderSourceType.XTREAM, ContentType.LIVE, streamId = 43),
            context,
        ).success()

        assertEquals(first.compatibilityScopeKey, equivalent.compatibilityScopeKey)
        assertNotEquals(first.compatibilityScopeKey, distinct.compatibilityScopeKey)
        assertFalse(first.toString().contains(account.password))
    }

    private fun resolver(
        account: XtreamAccount,
        link: suspend (XtreamAccount, String, Int, Boolean) -> ProviderLinkResult,
    ) = IptvProviderPlaybackResolver(
        accounts = ProviderAccountLookup { id -> account.takeIf { it.id == id } },
        links = ProviderLinkSource(link),
        winnerMemory = Memory(),
    )

    private fun selection(
        account: XtreamAccount,
        source: ProviderSourceType,
        contentType: ContentType,
        streamId: Int = 42,
        window: ProviderCatchUpWindow? = null,
    ): ProviderPlaybackSelection {
        val baseContentKey = XtreamItemRegistry.liveId(account.id, streamId)
        val contentKey = if (contentType == ContentType.CATCH_UP) {
            "${baseContentKey}r${requireNotNull(window).startEpochMs / 60_000L}"
        } else {
            baseContentKey
        }
        return ProviderPlaybackSelection(
            sourceType = source,
            accountId = ProviderSelectionId(account.id),
            itemId = ProviderSelectionId(streamId.toString()),
            contentKey = ProviderSelectionId(contentKey),
            contentType = contentType,
            catchUpWindow = window,
            providerConnectionLimit = 1,
        )
    }

    private fun account(
        id: String = "http://provider.invalid|user",
        sourceType: String,
        username: String = "user",
        dns: String = XtreamAccount.DNS_SYSTEM,
        preferM3u8: Boolean = false,
    ) = XtreamAccount(
        id = id,
        name = "Provider",
        baseUrl = "http://provider.invalid",
        username = username,
        password = "password",
        sourceType = sourceType,
        dnsProvider = dns,
        preferM3u8CatchUp = preferM3u8,
        portalUrl = "http://portal.invalid/c/",
        macAddress = "00:1A:79:00:00:01",
    )

    private fun feedback(eligibility: ProviderDialectAdvanceEligibility) = ProviderResolutionFeedback(
        code = FailureCode.MANIFEST_INVALID,
        domain = FailureDomain.MANIFEST,
        phase = FailurePhase.PLAYBACK,
        dialectAdvanceEligibility = eligibility,
    )

    private fun PlaybackResult<ResolvedPlaybackRequest>.success(): ResolvedPlaybackRequest =
        (this as PlaybackResult.Success).value

    private data class LinkCall(
        val accountId: String,
        val kind: String,
        val streamId: Int,
        val forceFresh: Boolean,
    )

    private class Memory : CatchUpDialectWalk.WinnerMemory {
        private val entries = mutableMapOf<String, CatchUpDialectWalk.StoredWinner>()
        override fun recall(accountId: String): CatchUpDialectWalk.StoredWinner? = entries[accountId]
        override fun remember(accountId: String, winner: CatchUpDialectWalk.StoredWinner) {
            entries[accountId] = winner
        }
    }
}

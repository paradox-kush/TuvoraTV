package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.iptv.CatchUpDialectWalk
import com.nuvio.tv.core.iptv.guardedPanelRequest
import com.nuvio.tv.core.iptv.M3uCatchUpInfo
import com.nuvio.tv.core.iptv.M3uCatchUpType
import com.nuvio.tv.core.iptv.PanelClockSource
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
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    /**
     * Regression (UX345, TV emulator 2026-11-12, mock Stalker restarted): while the portal was down the
     * reconnect's create_link failures opened the panel breaker for 30 s. The provider came back and the
     * viewer's FIRST Retry was refused by the breaker without contacting the portal, failing as the
     * generic "Live TV playback failed."; a second press after the window worked. A viewer's Retry must
     * reach the portal: it clears the breaker of the playlist it mints from (production default reset).
     */
    @Test
    fun `the viewer's Retry clears the panel breaker so the first re-mint reaches the portal`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val guard = com.nuvio.tv.core.iptv.IptvPanelGuard.guard
        val origin = requireNotNull(com.nuvio.tv.core.iptv.IptvPanelGuard.panelOriginUrlOf(account))
        val portalCalls = mutableListOf<Int>()
        try {
            // The provider died: two counted connection failures open the breaker.
            repeat(2) {
                val admitted = guard.admit(origin) as com.nuvio.tv.core.iptv.PanelAdmission.Allowed
                guard.report(admitted, com.nuvio.tv.core.iptv.PanelRequestOutcome.CONNECTION_FAILURE)
            }
            assertTrue(
                "precondition: the breaker is open",
                guard.admit(origin) is com.nuvio.tv.core.iptv.PanelAdmission.FastFail,
            )
            val factory = IptvProviderPlaybackResolverFactory(
                accountLookups = ProviderAccountLookupFactory { ProviderAccountLookup { id -> account.takeIf { it.id == id } } },
                // The transport the real Stalker session uses: admitted by the breaker, then the portal.
                links = ProviderLinkSource { _, _, streamId, _ ->
                    guard.guardedPanelRequest(origin) {
                        portalCalls += streamId
                        ProviderLinkResult.Resolved("http://media.invalid/live/$streamId.ts")
                    }
                },
                winnerMemory = Memory(),
            )
            val profile = PlaybackProfileId("17")
            val selection = selection(account, ProviderSourceType.STALKER, ContentType.LIVE)

            // The provider is back; the viewer presses Retry once.
            factory.beforeUserRetry(profile, selection)
            val result = factory.create(profile).resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL))

            assertEquals("the first Retry reached the portal", listOf(42), portalCalls)
            assertTrue("and played: $result", result is PlaybackResult.Success)
        } finally {
            com.nuvio.tv.core.iptv.IptvPanelGuard.resetForAccount(account)
        }
    }

    /** UX345: an automatic path refused by the open breaker reads as "provider unreachable", not unknown. */
    @Test
    fun `a breaker fast-fail is the provider being unreachable, not an unknown failure`() {
        val fastFail = com.nuvio.tv.core.iptv.PanelHostFastFailException("http://portal.invalid", 1L)
        assertEquals("fast-fail", ProviderLinkFailureReason.UNREACHABLE, ProviderLinkFailurePolicy.reasonOfError(fastFail))
        assertEquals(
            "fast-fail in IOException clothing",
            ProviderLinkFailureReason.UNREACHABLE,
            ProviderLinkFailurePolicy.reasonOfError(com.nuvio.tv.core.iptv.PanelHostFastFailIOException(fastFail)),
        )
        assertTrue("Stalker tags it too", com.nuvio.tv.core.iptv.isProviderUnreachable(fastFail))
        assertTrue("and a refused connection", com.nuvio.tv.core.iptv.isProviderUnreachable(java.net.ConnectException("x")))
        assertFalse("a reset is not unreachable", com.nuvio.tv.core.iptv.isProviderUnreachable(java.net.SocketException("reset")))
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

    /**
     * Regression (UX340, 2026-11-12): the portal went away mid-play; the reconnect's create_link threw
     * ConnectException and the resolver reported it as UNKNOWN, so the viewer read the generic "Live TV
     * playback failed." The failure must say the provider could not be reached, with no host in it.
     */
    @Test
    fun `a provider that cannot be reached fails as network unreachable, not unknown`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val resolver = resolver(account) { _, _, _, _ ->
            throw java.net.ConnectException("failed to connect to portal.invalid/10.0.2.2:8911")
        }

        val result = resolver.resolve(
            selection(account, ProviderSourceType.STALKER, ContentType.LIVE),
            ProviderResolutionContext(ProviderResolutionTrigger.RECOVERY),
        ) as PlaybackResult.Failure

        assertEquals("code", FailureCode.NETWORK_UNREACHABLE, result.failure.code)
        assertEquals("domain", FailureDomain.NETWORK, result.failure.domain)
        assertEquals("still terminal for the reconnect loop", com.nuvio.tv.playback.core.Retryability.FATAL, result.failure.retryability)
        assertFalse("no host crosses the port", result.toString().contains("portal.invalid"))
    }

    @Test
    fun `a portal that never answered create_link or timed out is a network failure`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val unreachable = resolver(account) { _, _, _, _ ->
            ProviderLinkResult.Unavailable(ProviderLinkFailureReason.UNREACHABLE)
        }.resolve(
            selection(account, ProviderSourceType.STALKER, ContentType.LIVE),
            ProviderResolutionContext(ProviderResolutionTrigger.RECOVERY),
        ) as PlaybackResult.Failure
        assertEquals("tagged by the client", FailureCode.NETWORK_UNREACHABLE, unreachable.failure.code)
        assertEquals("timeout", ProviderLinkFailureReason.TIMED_OUT,
            ProviderLinkFailurePolicy.reasonOfError(java.io.IOException("wrapped", java.net.SocketTimeoutException("read timed out"))))
        assertEquals("anything else stays unknown", ProviderLinkFailureReason.UNKNOWN,
            ProviderLinkFailurePolicy.reasonOfError(IllegalStateException("parse")))
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

    /**
     * Regression (live pipeline audit 2026-11-04, §3b): a Stalker create_link URL is spent by its first
     * open, yet no production request was ever marked SESSION_ONLY. So after a drop libmpv re-opened the
     * same dead URL with `reconnect=1,reconnect_streamed=1` and Media3/OkHttp retried it, burning
     * requests and connection slots before the session re-minted. A Stalker live link must reach both
     * engines as single-use: no connection retry, no in-stack load retry — only the session re-mints.
     */
    @Test
    fun `Stalker live link is single-use so neither engine re-opens it after a drop`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val resolver = resolver(account) { _, _, _, _ ->
            ProviderLinkResult.Resolved("http://portal.invalid/ch/42?play_token=abc")
        }
        val selection = selection(account, ProviderSourceType.STALKER, ContentType.LIVE)

        for (trigger in listOf(ProviderResolutionTrigger.INITIAL, ProviderResolutionTrigger.RECOVERY)) {
            val resolved = resolver.resolve(selection, ProviderResolutionContext(trigger)).success()
            assertEquals(
                "$trigger: only the session may retry a spent link",
                TransientLoadRetryPolicy.SESSION_ONLY,
                resolved.request.network.transientLoadRetryPolicy,
            )
            assertFalse("$trigger: no connection-level replay", resolved.request.network.retryConnectionFailures)
            assertEquals(TransientLoadRetryPolicy.SESSION_ONLY, resolved.summary.transientLoadRetryPolicy)
        }
    }

    /**
     * B259: the Stalker stream request carried only the engine default (a spoofed Chrome UA) - no MAG
     * identity even when the stream lives on the portal's own host. Portal host: identity, with the
     * credentials kept origin-bound; any other host: a credential-free player profile.
     */
    @Test
    fun `Stalker stream on the portal host presents the portal identity as origin bound headers`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val resolver = resolver(account, appVersion = "9.9") { _, _, _, _ ->
            ProviderLinkResult.Resolved("http://portal.invalid:8000/play/live.php?stream=42&play_token=abc", stalkerIdentity)
        }

        val request = resolver.resolve(
            selection(account, ProviderSourceType.STALKER, ContentType.LIVE),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ).success().request

        assertEquals("MAG preset UA", "MAG-UA", request.userAgent)
        assertEquals("box model on every hop", "Model: MAG250; Link: WiFi", request.headers["X-User-Agent"])
        assertNull("no cookie in the every-hop headers", request.headers["Cookie"])
        assertTrue("no cookie map either", request.cookies.isEmpty())
        val bound = requireNotNull(request.originBoundHeaders) { "portal identity expected" }
        assertEquals("portal host", "portal.invalid", bound.host)
        assertEquals("mac cookie", stalkerIdentity.cookie, bound.headers["Cookie"])
        assertEquals("token", "Bearer tok", bound.headers["Authorization"])
        assertFalse("never printable", request.toString().contains("tok"))
    }

    @Test
    fun `Stalker stream on a foreign host carries no credential and an honest user agent`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val resolver = resolver(account, appVersion = "9.9") { _, _, _, _ ->
            ProviderLinkResult.Resolved("http://edge.cdn.invalid/live/42.ts?token=abc", stalkerIdentity)
        }

        val request = resolver.resolve(
            selection(account, ProviderSourceType.STALKER, ContentType.LIVE),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ).success().request

        assertNull("no origin-bound credentials", request.originBoundHeaders)
        assertEquals("neutral player UA", "Tuvora/9.9 (Linux; Android TV)", request.userAgent)
        assertNull("no box model to a foreign host", request.headers["X-User-Agent"])
        assertFalse("no Authorization", request.summary().hasAuthorization)
    }

    /**
     * A provider that answers its own create_link stream with 401 to the MAG box UA: the re-mint after
     * that refusal is immediate, presents the neutral profile, and a neutral that plays is remembered.
     */
    @Test
    fun `Stalker portal-host stream refused at open re-mints with the neutral profile and remembers it`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val identities = com.nuvio.tv.core.iptv.stalker.StalkerStreamIdentityPolicy()
        val resolver = resolver(account, appVersion = "9.9", identities = identities) { _, _, _, _ ->
            ProviderLinkResult.Resolved("http://portal.invalid:8000/play/live.php?stream=42&play_token=abc", stalkerIdentity)
        }
        val selection = selection(account, ProviderSourceType.STALKER, ContentType.LIVE)
        val refused = ProviderResolutionContext(
            ProviderResolutionTrigger.RECOVERY,
            ProviderResolutionFeedback(
                FailureCode.AUTHORIZATION_REJECTED,
                FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
                FailurePhase.PLAYBACK,
                ProviderDialectAdvanceEligibility.NONE,
            ),
        )

        val first = resolver.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL)).success().request
        assertEquals("tune presents the MAG identity", "MAG-UA", first.userAgent)
        assertTrue("re-mint after the refusal skips the spacing", resolver.remintSkipsSpacing(selection, refused))

        val second = resolver.resolve(selection, refused).success().request
        assertEquals("neutral UA", "Tuvora/9.9 (Linux; Android TV)", second.userAgent)
        assertNull("no box model", second.headers["X-User-Agent"])
        assertNull("no origin-bound Cookie/Authorization/Referer/Origin", second.originBoundHeaders)
        assertFalse("no Authorization", second.summary().hasAuthorization)
        assertFalse("no Cookie", second.summary().hasCookies)
        assertFalse("no Referer", second.summary().hasReferer)
        assertFalse("no Origin", second.summary().hasOrigin)

        resolver.onPlaybackStarted(selection)
        val retune = resolver.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL)).success().request
        assertEquals("the playlist remembers neutral", "Tuvora/9.9 (Linux; Android TV)", retune.userAgent)
    }

    /** Review LOW: only live streams switch and teach; VOD keeps the identity and never learns. */
    @Test
    fun `Stalker VOD neither switches the stream identity nor teaches the playlist`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val identities = com.nuvio.tv.core.iptv.stalker.StalkerStreamIdentityPolicy()
        val resolver = resolver(account, appVersion = "9.9", identities = identities) { _, _, _, _ ->
            ProviderLinkResult.Resolved("http://portal.invalid:8000/play/movie.php?stream=42&play_token=abc", stalkerIdentity)
        }
        val vod = ProviderPlaybackSelection(
            sourceType = ProviderSourceType.STALKER,
            accountId = ProviderSelectionId(account.id),
            itemId = ProviderSelectionId("42"),
            contentKey = ProviderSelectionId(XtreamItemRegistry.vodId(account.id, 42)),
            contentType = ContentType.VOD,
            providerConnectionLimit = 1,
        )

        resolver.resolve(vod, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL)).success()
        assertFalse("VOD re-mint keeps the spacing", resolver.remintSkipsSpacing(vod, refusedAtOpen()))
        val again = resolver.resolve(vod, refusedAtOpen()).success().request
        assertEquals("VOD keeps the MAG identity", "MAG-UA", again.userAgent)
        resolver.onPlaybackStarted(vod)
        assertEquals(
            "VOD never teaches the playlist",
            com.nuvio.tv.core.iptv.stalker.StalkerStreamIdentityPolicy.StreamIdentity.IDENTITY,
            identities.remembered(account.id),
        )
    }

    /** Review LOW: each resolver is one playback session; a guide preview of the same channel is another. */
    @Test
    fun `a second session tuning the same channel does not give the first a second immediate switch`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        val identities = com.nuvio.tv.core.iptv.stalker.StalkerStreamIdentityPolicy()
        val link: suspend (XtreamAccount, String, Int, Boolean) -> ProviderLinkResult = { _, _, _, _ ->
            ProviderLinkResult.Resolved("http://portal.invalid:8000/play/live.php?stream=42&play_token=abc", stalkerIdentity)
        }
        val fullscreen = resolver(account, identities = identities, link = link)
        val preview = resolver(account, identities = identities, link = link)
        val selection = selection(account, ProviderSourceType.STALKER, ContentType.LIVE)

        fullscreen.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL)).success()
        fullscreen.resolve(selection, refusedAtOpen()).success()
        preview.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL)).success()

        assertFalse("the fullscreen trial is spent", fullscreen.remintSkipsSpacing(selection, refusedAtOpen()))
        assertTrue("the preview has its own trial", preview.remintSkipsSpacing(selection, refusedAtOpen()))
    }

    private fun refusedAtOpen() = ProviderResolutionContext(
        ProviderResolutionTrigger.RECOVERY,
        ProviderResolutionFeedback(
            FailureCode.AUTHORIZATION_REJECTED,
            FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
            FailurePhase.PLAYBACK,
            ProviderDialectAdvanceEligibility.NONE,
        ),
    )

    @Test
    fun `Stalker playlist user agent override wins over the MAG preset`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER).copy(userAgent = "VLC/3.0.20")
        val resolver = resolver(account) { _, _, _, _ ->
            ProviderLinkResult.Resolved("http://portal.invalid/play/live.php?stream=42", stalkerIdentity)
        }

        val request = resolver.resolve(
            selection(account, ProviderSourceType.STALKER, ContentType.LIVE),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ).success().request

        assertEquals("override", "VLC/3.0.20", request.userAgent)
        assertNotNull("identity still bound to the portal", request.originBoundHeaders)
    }

    @Test
    fun `Xtream live keeps its headers and gets no Stalker identity`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_XTREAM)
        val resolver = resolver(account) { _, _, _, _ ->
            ProviderLinkResult.Resolved("http://provider.invalid/live/user/password/42.ts", stalkerIdentity)
        }

        val request = resolver.resolve(
            selection(account, ProviderSourceType.XTREAM, ContentType.LIVE),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ).success().request

        assertNull("Xtream: no origin-bound identity", request.originBoundHeaders)
        assertNull("Xtream: engine default UA as before", request.userAgent)
    }

    /** A reusable Xtream live URL keeps the engines' bounded in-stack recovery (frozen-frame reconnect). */
    @Test
    fun `Xtream live link stays re-openable by the engines`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_XTREAM)
        val resolver = resolver(account) { _, _, _, _ ->
            ProviderLinkResult.Resolved("https://provider.invalid/live/user/password/42.ts")
        }

        val resolved = resolver.resolve(
            selection(account, ProviderSourceType.XTREAM, ContentType.LIVE),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ).success()

        assertEquals(TransientLoadRetryPolicy.ENGINE_DEFAULT, resolved.request.network.transientLoadRetryPolicy)
        assertTrue(resolved.request.network.retryConnectionFailures)
    }

    /**
     * Regression ("Live TV spins forever", resolve lane): a Stalker portal that never answers create_link —
     * or trickles it — held the clean player in REQUEST_RESOLUTION, a spinner over black, for as long as the
     * viewer waited. The resolve is now bounded (BoundedLoad LIVE_RESOLVE, 20 s): it ends as the same
     * retryable unavailable failure a null link gives, and the resolver is usable again straight after.
     */
    @Test
    fun `a create_link that never answers ends as unavailable at the live-resolve deadline`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_STALKER)
        var calls = 0
        val resolver = resolver(account) { _, _, streamId, _ ->
            calls++
            if (calls == 1) kotlinx.coroutines.awaitCancellation()
            ProviderLinkResult.Resolved("http://portal.invalid/live/$streamId.ts")
        }
        val selection = selection(account, ProviderSourceType.STALKER, ContentType.LIVE)

        val stalled = resolver.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL))

        assertTrue("a hung create_link must end as a failure, was $stalled", stalled is PlaybackResult.Failure)
        assertEquals(FailurePhase.REQUEST_RESOLUTION, (stalled as PlaybackResult.Failure).failure.phase)
        assertFalse("a stall is not a deterministic dead channel", stalled.failure.deterministic)
        assertEquals(20_000L, testScheduler.currentTime)
        // The serialization mutex was released: the viewer's Retry resolves.
        val retried = resolver.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.RECOVERY))
        assertTrue("the next resolve after a stall works, was $retried", retried is PlaybackResult.Success)
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
    fun `M3U catch-up is built from the channel's own settings and walks on a transport failure`() = runTest {
        val m3u = account(id = "url:https://playlist.invalid/list", sourceType = XtreamAccount.SOURCE_URL)
        val channel = M3uChannelCatchUp(
            "http://panel.invalid/live/user/pass/7.ts",
            M3uCatchUpInfo(M3uCatchUpType.XTREAM, null, 3),
        )
        val resolver = resolver(m3u, m3uChannel = channel) { _, _, _, _ -> error("catch-up must not use live resolver") }
        // 2026-10-03 19:00-20:00 UTC, long past: the window extends, the link names its start minute.
        val window = ProviderCatchUpWindow(1_791_054_000_000L, 1_791_057_600_000L)
        val selection = selection(m3u, ProviderSourceType.M3U, ContentType.CATCH_UP, streamId = 7, window = window)

        val first = resolver.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.INITIAL)).success()
        assertTrue("timeshift link at the programme start: ${first.request.url}", first.request.url.contains("/timeshift/user/pass/") && first.request.url.contains("2026-10-03:19-00"))

        val second = resolver.resolve(
            selection,
            ProviderResolutionContext(
                trigger = ProviderResolutionTrigger.RECOVERY,
                previousFailure = feedback(ProviderDialectAdvanceEligibility.TRANSPORT_OR_DEMUX_FAILURE),
            ),
        ).success()
        assertNotEquals("a transport failure moves to another candidate", first.request.url, second.request.url)
    }

    @Test
    fun `M3U catch-up for a channel without catch-up settings is unavailable`() = runTest {
        val m3u = account(id = "url:https://playlist.invalid/list", sourceType = XtreamAccount.SOURCE_URL)
        val window = ProviderCatchUpWindow(1_791_054_000_000L, 1_791_057_600_000L)
        val result = resolver(m3u, m3uChannel = null) { _, _, _, _ -> error("unused") }.resolve(
            selection(m3u, ProviderSourceType.M3U, ContentType.CATCH_UP, streamId = 7, window = window),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        )
        assertTrue(result is PlaybackResult.Failure)
    }

    /** B117: the clean pipeline mints the replay too, so it must ask for the panel-local start. */
    @Test
    fun `catch-up start is panel-local when the panel clock is measured`() = runTest {
        val account = account(sourceType = XtreamAccount.SOURCE_XTREAM)
        val resolver = resolver(account, panelOffsetMs = 60 * 60_000L) { _, _, _, _ -> error("catch-up must not use live resolver") }
        // 2026-10-03 19:00 UTC; a +1h (BST) panel reads its wall clock: 20:00.
        val window = ProviderCatchUpWindow(1_791_054_000_000L, 1_791_057_600_000L)

        val replay = resolver.resolve(
            selection(account, ProviderSourceType.XTREAM, ContentType.CATCH_UP, window = window),
            ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
        ).success()

        assertTrue("panel-local start expected: ${replay.request.url}", replay.request.url.contains("2026-10-03:20-00"))
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

    private val stalkerIdentity = com.nuvio.tv.core.iptv.stalker.StalkerStreamIdentity(
        portalBase = "http://portal.invalid",
        referer = "http://portal.invalid/c/",
        cookie = "mac=00%3A1A%3A79%3A00%3A00%3A01; stb_lang=en; timezone=Europe/London",
        magUserAgent = "MAG-UA",
        magXUserAgent = "Model: MAG250; Link: WiFi",
        token = "tok",
    )

    private fun resolver(
        account: XtreamAccount,
        appVersion: String = "1.0",
        panelOffsetMs: Long? = null,
        m3uChannel: M3uChannelCatchUp? = null,
        identities: com.nuvio.tv.core.iptv.stalker.StalkerStreamIdentityPolicy =
            com.nuvio.tv.core.iptv.stalker.StalkerStreamIdentityPolicy(),
        link: suspend (XtreamAccount, String, Int, Boolean) -> ProviderLinkResult,
    ) = IptvProviderPlaybackResolver(
        accounts = ProviderAccountLookup { id -> account.takeIf { it.id == id } },
        links = ProviderLinkSource(link),
        winnerMemory = Memory(),
        panelClock = PanelClockSource { panelOffsetMs },
        m3uChannels = ProviderM3uCatchUpLookup { _, _ -> m3uChannel },
        appVersion = appVersion,
        streamIdentities = identities,
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

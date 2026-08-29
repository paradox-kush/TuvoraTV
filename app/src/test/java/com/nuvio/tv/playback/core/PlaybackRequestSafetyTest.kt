package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestSafetyTest {
    @Test
    fun `request and DRM string forms never reveal secrets`() {
        val secrets = listOf(
            "provider.example",
            "alice",
            "password",
            "account-token",
            "cookie-secret",
            "private-agent",
            "secret-referrer",
            "secret-origin",
            "license.example",
            "drm-token",
            "playlist-account-key",
            "private-proxy.example",
            "proxy-user",
            "proxy-password",
        )
        val drm = DrmRequest(
            scheme = DrmScheme.WIDEVINE,
            licenseUrl = "https://license.example/widevine?token=drm-token",
            requestHeaders = mapOf("Authorization" to "Bearer drm-token"),
        )
        val request = PlaybackRequest(
            url = "https://alice:password@provider.example/live/channel.ts?token=account-token",
            headers = mapOf("Authorization" to "Bearer account-token", "X-Provider" to "secret"),
            cookies = mapOf("session" to "cookie-secret"),
            userAgent = "private-agent",
            referer = "https://secret-referrer/path",
            origin = "https://secret-origin",
            network = PlaybackNetworkRequest(
                proxyMode = ProxyMode.HTTP,
                httpProxy = HttpProxyRequest(
                    host = "private-proxy.example",
                    port = 8_080,
                    username = SecretValue("proxy-user"),
                    password = SecretValue("proxy-password"),
                ),
                connectTimeoutMs = 12_000,
                readTimeoutMs = 45_000,
                transientLoadRetryPolicy = TransientLoadRetryPolicy.SESSION_ONLY,
            ),
            drm = drm,
            contentType = ContentType.LIVE,
            contentKey = SecretValue("playlist-account-key"),
            providerConnectionLimit = 1,
        )

        val printable = listOf(
            request.toString(),
            drm.toString(),
            request.contentKey.toString(),
            request.network.toString(),
            request.network.httpProxy.toString(),
        )
            .joinToString(" ")

        secrets.forEach { secret -> assertFalse("Leaked $secret", printable.contains(secret)) }
        assertTrue(printable.contains("hasDrm=true"))
        assertTrue(printable.contains("scheme=WIDEVINE"))
        assertEquals(ProxyMode.HTTP, request.summary().proxyMode)
        assertTrue(request.summary().hasCustomNetworkPolicy)
    }

    @Test
    fun `request is deliberately not a data class`() {
        val methodNames = PlaybackRequest::class.java.declaredMethods.map { it.name }

        assertFalse(methodNames.contains("copy"))
        assertFalse(methodNames.any { it.startsWith("component") })
    }

    @Test
    fun `DRM presence and explicit secure output remain distinct facts`() {
        val ordinaryWidevine = PlaybackRequest(
            url = "https://media.invalid/manifest.mpd",
            drm = DrmRequest(DrmScheme.WIDEVINE, "https://license.invalid"),
            contentType = ContentType.LIVE,
        )
        val secureWidevine = PlaybackRequest(
            url = "https://media.invalid/secure.mpd",
            drm = DrmRequest(
                DrmScheme.WIDEVINE,
                "https://license.invalid",
                secureOutputRequired = true,
            ),
            contentType = ContentType.LIVE,
        )

        assertTrue(ordinaryWidevine.summary().hasDrm)
        assertFalse(ordinaryWidevine.summary().secureOutputRequired)
        assertTrue(secureWidevine.summary().hasDrm)
        assertTrue(secureWidevine.summary().secureOutputRequired)
    }

    @Test
    fun `summary contains flags and scheme but no endpoint identity`() {
        val request = PlaybackRequest(
            url = "https://user:pass@provider.example/live/42?token=secret",
            cookies = mapOf("session" to "secret"),
            userAgent = "agent",
            referer = "https://private.example",
            contentType = ContentType.LIVE,
            providerConnectionLimit = 1,
        )

        val summary = request.summary()

        assertEquals("https", summary.scheme)
        assertEquals(ContentType.LIVE, summary.contentType)
        assertTrue(summary.hasAuthorization)
        assertTrue(summary.hasCookies)
        assertTrue(summary.hasUserAgent)
        assertTrue(summary.hasReferer)
        assertEquals(CrossHostAuthorization.STRIP, summary.crossHostAuthorization)
        assertTrue(summary.providerConnectionConstrained)
        assertFalse(summary.toString().contains("provider.example"))
        assertFalse(summary.toString().contains("secret"))
    }

    @Test
    fun `request bearing session wrappers remain redacted when composed`() {
        val secret = "never-print-provider-token"
        val request = PlaybackRequest(
            url = "https://provider.invalid/live?token=$secret",
            headers = mapOf("Authorization" to secret),
            contentType = ContentType.LIVE,
        )
        val action = PlaybackAction.ResolveRequest(7, PlaybackLaunch.ConcreteRequest(request))
        val machine = PlaybackMachineState(
            request = request,
            snapshot = PlaybackSnapshot(generation = 7, state = PlaybackState.RESOLVING),
        )
        val transition = PlaybackTransition(machine, listOf(action))
        val resolved = ResolvedPlaybackRequest(request, request.summary(), StreamEvidence())
        val requirementsInput = PlaybackRequirementsInput(
            requestSummary = request.summary(),
            evidence = StreamEvidence(),
            profile = SessionProfile.FULLSCREEN,
            effectivePreferences = PlaybackPreferences.recommended(),
            environment = PlaybackEnvironmentSnapshot(
                runtimeCapabilities = RuntimeCapabilities(
                    snapshotVersion = 1,
                    capturedAtEpochMs = 1,
                    apiLevel = 36,
                    display = DisplayCapabilities(VideoDimensions(1920, 1080)),
                    audioRoute = AudioRouteCapabilities(AudioRoute.TV_SPEAKERS),
                    resources = ResourceCapabilities(1_000_000, lowMemory = false),
                    surfaces = SurfaceCapabilities(),
                ),
                secureOutputRequired = false,
            ),
        )

        listOf(action, machine, transition, resolved, requirementsInput).forEach { wrapper ->
            assertFalse("Leaked through ${wrapper::class.simpleName}", wrapper.toString().contains(secret))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `provider connection limit must be positive`() {
        PlaybackRequest(
            url = "https://example.invalid/live",
            contentType = ContentType.LIVE,
            providerConnectionLimit = 0,
        )
    }
}

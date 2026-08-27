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
            drm = drm,
            contentType = ContentType.LIVE,
            contentKey = SecretValue("playlist-account-key"),
            providerConnectionLimit = 1,
        )

        val printable = listOf(request.toString(), drm.toString(), request.contentKey.toString())
            .joinToString(" ")

        secrets.forEach { secret -> assertFalse("Leaked $secret", printable.contains(secret)) }
        assertTrue(printable.contains("hasDrm=true"))
        assertTrue(printable.contains("scheme=WIDEVINE"))
    }

    @Test
    fun `request is deliberately not a data class`() {
        val methodNames = PlaybackRequest::class.java.declaredMethods.map { it.name }

        assertFalse(methodNames.contains("copy"))
        assertFalse(methodNames.any { it.startsWith("component") })
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
        val action = PlaybackAction.ResolveRequest(7, request)
        val machine = PlaybackMachineState(
            request = request,
            snapshot = PlaybackSnapshot(generation = 7, state = PlaybackState.RESOLVING),
        )
        val transition = PlaybackTransition(machine, listOf(action))
        val resolved = ResolvedPlaybackRequest(request, request.summary(), StreamEvidence())
        val requirementsInput = PlaybackRequirementsInput(
            request,
            StreamEvidence(),
            SessionProfile.FULLSCREEN,
            PlaybackPreferences.recommended(),
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

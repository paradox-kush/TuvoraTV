package com.nuvio.tv.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostHogPrivacyTest {

    @Test
    fun `deep link events are dropped regardless of casing`() {
        assertTrue(PostHogPrivacy.shouldDropEvent("Deep Link Opened"))
        assertTrue(PostHogPrivacy.shouldDropEvent("deep link opened"))
        assertFalse(PostHogPrivacy.shouldDropEvent("app_exit"))
    }

    @Test
    fun `sensitive keys and nested auth values are removed without losing diagnostics`() {
        val sanitized = PostHogPrivacy.sanitize(
            mapOf(
                "reason" to "anr",
                "reason_code" to 6,
                "url" to "nuvio://auth/trakt?code=secret&state=nonce",
                "detail" to "GET https://panel.example/live?token=secret failed",
                "nested" to mapOf("authorization" to "Bearer secret", "phase" to "matching"),
            ),
        )

        assertEquals("anr", sanitized["reason"])
        assertEquals(6, sanitized["reason_code"])
        assertFalse("url" in sanitized)
        assertEquals("GET [redacted-url] failed", sanitized["detail"])
        assertEquals(mapOf("phase" to "matching"), sanitized["nested"])
        assertEquals(true, sanitized[PostHogPrivacy.GEOIP_DISABLE_PROPERTY])
    }

    @Test
    fun `auth fragments without a full URL are redacted`() {
        val sanitized = PostHogPrivacy.sanitize(
            mapOf(
                "message" to "callback failed: code=abc123&state=xyz789",
                "network" to "rtsp://user:pass@provider.example/live failed with Bearer abc.def",
            ),
        )

        assertEquals(
            "callback failed: code=[redacted]&state=[redacted]",
            sanitized["message"],
        )
        assertEquals("[redacted-url] failed with [redacted-auth]", sanitized["network"])
    }

    // Wave 3 / P0: media-server clients carry credentials under their own names (Jellyfin/Emby
    // ApiKey / api_key query values, Plex X-Plex-Token, the MediaBrowser Authorization header's
    // Token="..." and the X-Emby-Token header). None of them may reach an analytics event.
    @Test
    fun `media server credential shapes are redacted`() {
        val sanitized = PostHogPrivacy.sanitize(
            mapOf(
                "apiKeyValue" to "request failed ApiKey=jf-secret-1&Limit=10",
                "queryValue" to "request failed api_key=emby-secret-2&x=1",
                "plexValue" to "request failed X-Plex-Token=plex-secret-3&x=1",
                "header" to "MediaBrowser Client=\"Tuvora\", Device=\"Pixel\", DeviceId=\"d1\", Version=\"1\", Token=\"mb-secret-4\"",
                "embyHeader" to "X-Emby-Token: emby-secret-5 sent",
                "plexHeader" to "X-Plex-Token: plex-secret-6 sent",
            ),
        )

        assertEquals("request failed ApiKey=[redacted]&Limit=10", sanitized["apiKeyValue"])
        assertEquals("request failed api_key=[redacted]&x=1", sanitized["queryValue"])
        assertEquals("request failed X-Plex-Token=[redacted]&x=1", sanitized["plexValue"])
        assertEquals(
            "MediaBrowser Client=\"Tuvora\", Device=\"Pixel\", DeviceId=\"d1\", Version=\"1\", Token=\"[redacted]\"",
            sanitized["header"],
        )
        assertEquals("X-Emby-Token: [redacted] sent", sanitized["embyHeader"])
        assertEquals("X-Plex-Token: [redacted] sent", sanitized["plexHeader"])
    }

    @Test
    fun `ordinary text that mentions keys and tokens is kept`() {
        val sanitized = PostHogPrivacy.sanitize(
            mapOf("note" to "api key rotation token refresh ok monkey=banana", "phase" to "apikey length 32"),
        )
        assertEquals("api key rotation token refresh ok monkey=banana", sanitized["note"])
        assertEquals("apikey length 32", sanitized["phase"])
    }
}

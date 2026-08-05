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
}

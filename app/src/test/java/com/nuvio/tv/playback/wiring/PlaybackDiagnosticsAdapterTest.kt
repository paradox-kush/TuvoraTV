package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackDiagnosticCode
import com.nuvio.tv.playback.core.PlaybackDiagnosticEvent
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.core.analytics.PostHogPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsAdapterTest {
    @Test
    fun `formatter emits only stable allowlisted playback properties`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 42,
                code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                engine = EngineType.LIBMPV,
                failure = PlaybackFailure(
                    code = FailureCode.VIDEO_DECODER_FAILED,
                    domain = FailureDomain.VIDEO_DECODER,
                    phase = FailurePhase.ENGINE_START,
                    retryability = Retryability.HANDOFF_ELIGIBLE,
                    deterministic = true,
                ),
                attempt = 3,
            ),
        )

        assertEquals("clean_playback_diagnostic", formatted.eventName)
        assertEquals(
            setOf(
                "schema_version", "generation", "diagnostic_code", "engine", "attempt", "failure_code",
                "failure_domain", "failure_phase", "retryability", "deterministic",
                PostHogPrivacy.GEOIP_DISABLE_PROPERTY,
            ),
            formatted.properties.keys,
        )
        assertEquals("VIDEO_DECODER", formatted.properties["failure_domain"])
        assertEquals("HANDOFF_ELIGIBLE", formatted.properties["retryability"])
        assertEquals(true, formatted.properties[PostHogPrivacy.GEOIP_DISABLE_PROPERTY])
        assertTrue(formatted.properties.values.all { it is String || it is Number || it is Boolean })
    }

    @Test
    fun `formatted event cannot carry endpoint or request identity fields`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 1,
                code = PlaybackDiagnosticCode.REQUEST_RESOLVED,
            ),
        )
        val forbiddenKeys = listOf(
            "url", "host", "path", "query", "account", "provider", "playlist", "channel",
            "header", "cookie", "license", "drm_secret",
        )

        forbiddenKeys.forEach { forbidden ->
            assertFalse(formatted.properties.keys.any { it.contains(forbidden, ignoreCase = true) })
        }
        assertFalse(formatted.toString().contains("https://"))
    }

    @Test
    fun `diagnostics adapter formats before invoking sink`() {
        var captured: FormattedPlaybackDiagnostic? = null
        val diagnostics = FormattingPlaybackDiagnostics { captured = it }

        diagnostics.record(
            PlaybackDiagnosticEvent(
                generation = 9,
                code = PlaybackDiagnosticCode.LIVE_RECONNECT_ATTEMPT,
                engine = EngineType.MEDIA3,
                attempt = 2,
            ),
        )

        assertNotNull(captured)
        assertEquals(9L, captured?.properties?.get("generation"))
        assertEquals("LIVE_RECONNECT_ATTEMPT", captured?.properties?.get("diagnostic_code"))
    }
}

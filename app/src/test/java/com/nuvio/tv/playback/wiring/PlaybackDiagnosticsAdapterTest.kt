package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.core.telemetry.DiagTrailPolicy
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.LiveFreezePolicy
import com.nuvio.tv.playback.core.LiveFreezeTelemetry
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackDiagnosticCode
import com.nuvio.tv.playback.core.PlaybackDiagnosticEvent
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackOutputStatus
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
    fun `a live freeze reports kind rung decoder firmware and session instance as identifiers`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 3,
                code = PlaybackDiagnosticCode.WATCHDOG_EXPIRED,
                engine = EngineType.MEDIA3,
                failure = PlaybackFailure(
                    FailureCode.NO_PROGRESS,
                    FailureDomain.VIDEO_RENDERER_SURFACE,
                    FailurePhase.PLAYBACK,
                    Retryability.HANDOFF_ELIGIBLE,
                ),
                freeze = LiveFreezeTelemetry(
                    kind = LiveFreezePolicy.FreezeKind.VIDEO_ONLY,
                    rung = LiveFreezePolicy.Action.HANDOFF,
                    decoder = "c2.mtk.avc.decoder",
                    sessionInstance = "1a2b3c",
                ),
            ),
            buildId = "UTT3.240625.001",
        )

        assertEquals("VIDEO_ONLY", formatted.properties["freeze_kind"])
        assertEquals("HANDOFF", formatted.properties["ladder_rung"])
        assertEquals("c2_mtk_avc_decoder", formatted.properties["decoder"])
        assertEquals("UTT3_240625_001", formatted.properties["build_id"])
        assertEquals("1a2b3c", formatted.properties["session_instance"])
        val forbidden = setOf("code", "state", "url", "token")
        assertTrue(formatted.properties.keys.none { it in forbidden })
        // The same fields survive the on-device trail allow-list.
        val trail = DiagTrailPolicy.sanitize(formatted.properties)
        listOf("freeze_kind", "ladder_rung", "decoder", "build_id", "session_instance").forEach {
            assertTrue("$it is allow-listed", it in trail)
        }
    }

    @Test
    fun `a freeze with no decoder or firmware leaves those fields out`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 3,
                code = PlaybackDiagnosticCode.LIVE_PICTURE_STUCK,
                freeze = LiveFreezeTelemetry(
                    kind = LiveFreezePolicy.FreezeKind.VIDEO_ONLY,
                    rung = LiveFreezePolicy.Action.KEEP_PLAYING,
                    sessionInstance = "ff",
                ),
            ),
            buildId = null,
        )

        assertFalse("decoder" in formatted.properties)
        assertFalse("build_id" in formatted.properties)
        assertEquals("KEEP_PLAYING", formatted.properties["ladder_rung"])
    }

    @Test
    fun `requirements change and barrier reason cross the boundary as enums only`() {
        val change = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 7,
                code = PlaybackDiagnosticCode.REQUIREMENTS_CHANGE_RESOLVED,
                changeImpact = com.nuvio.tv.playback.core.ChangeImpact.REBUILD_CURRENT_GRAPH,
                changedFields = setOf(
                    com.nuvio.tv.playback.core.RequirementsField.PROFILE,
                    com.nuvio.tv.playback.core.RequirementsField.BUFFERING,
                ),
            ),
        )
        assertEquals("REBUILD_CURRENT_GRAPH", change.properties["change_impact"])
        assertEquals("BUFFERING,PROFILE", change.properties["changed_fields"])

        val barrier = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 7,
                code = PlaybackDiagnosticCode.RELEASE_BARRIER_STARTED,
                releaseReason = com.nuvio.tv.playback.core.ActiveWorkReleaseReason.REBUILD,
            ),
        )
        assertEquals("REBUILD", barrier.properties["release_reason"])
        assertTrue(barrier.properties.values.all { it is String || it is Number || it is Boolean })

        val watchdog = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 7,
                code = PlaybackDiagnosticCode.WATCHDOG_EXPIRED,
                highResolution = true,
            ),
        )
        assertEquals(true, watchdog.properties["high_resolution"])
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

    @Test
    fun `nonfatal output diagnostic exposes only its stable status code`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 10,
                code = PlaybackDiagnosticCode.PLAYBACK_OUTPUT_NONFATAL,
                outputStatus = PlaybackOutputStatus.APPLY_NOT_CONFIRMED,
            ),
        )

        assertEquals("APPLY_NOT_CONFIRMED", formatted.properties["output_status"])
        assertFalse(formatted.properties.keys.any { key ->
            listOf("mode_id", "display", "device", "model", "rate", "width", "height")
                .any { forbidden -> key.contains(forbidden, ignoreCase = true) }
        })
    }

    @Test
    fun `routine success diagnostics stay off the network while failures and recovery upload`() {
        // ~98% of clean_playback_diagnostic volume (~114k events/week) was success-path logging,
        // about five events per zap. The per-session quality summary replaces it.
        val routine = listOf(
            PlaybackDiagnosticCode.REQUEST_RESOLUTION_STARTED,
            PlaybackDiagnosticCode.REQUEST_RESOLVED,
            PlaybackDiagnosticCode.RELEASE_BARRIER_COMPLETED,
            PlaybackDiagnosticCode.REQUIREMENTS_CHANGE_RESOLVED,
        )
        routine.forEach { code ->
            val formatted = PlaybackDiagnosticFormatter.format(PlaybackDiagnosticEvent(generation = 1, code = code))
            assertFalse("$code must not upload", PlaybackDiagnosticUploadPolicy.shouldUpload(formatted))
        }
        // Engine choice and release reasons are what per-engine fleet health is measured from
        // (handoff, failure and channel-change analysis): they must keep uploading.
        val kept = listOf(
            PlaybackDiagnosticCode.GRAPH_SELECTED,
            PlaybackDiagnosticCode.RELEASE_BARRIER_STARTED,
            PlaybackDiagnosticCode.ENGINE_REPORTED_FAILURE,
            PlaybackDiagnosticCode.WATCHDOG_EXPIRED,
            PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
            PlaybackDiagnosticCode.LIVE_RECONNECT_ATTEMPT,
            PlaybackDiagnosticCode.PLAYBACK_OUTPUT_NONFATAL,
        )
        kept.forEach { code ->
            val formatted = PlaybackDiagnosticFormatter.format(PlaybackDiagnosticEvent(generation = 1, code = code))
            assertTrue("$code must upload", PlaybackDiagnosticUploadPolicy.shouldUpload(formatted))
        }
    }

    @Test
    fun `no eligible graph failure sends why and the stream facts`() {
        val formatted = PlaybackDiagnosticFormatter.format(noEligibleGraphEvent())

        assertEquals("DECODER_PREFERENCE", formatted.properties["rejected_by"])
        assertEquals(3, formatted.properties["candidate_graphs"])
        assertEquals(1, formatted.properties["rejected_engine_or_surface"])
        assertEquals(2, formatted.properties["rejected_decoder_preference"])
        assertEquals("hevc", formatted.properties["stream_codec"])
        assertEquals("ts", formatted.properties["stream_container"])
        assertEquals("uhd", formatted.properties["stream_height"])
        assertEquals("dv", formatted.properties["stream_hdr"])
    }

    @Test
    fun `graph rejection properties carry only allow-listed enum codes and numbers`() {
        val formatted = PlaybackDiagnosticFormatter.format(noEligibleGraphEvent())
        val allowedStrings = com.nuvio.tv.playback.core.GraphRejectionReason.entries.map { it.name }.toSet() +
            com.nuvio.tv.playback.core.StreamCodecCode.entries.map { it.wire } +
            com.nuvio.tv.playback.core.StreamContainerCode.entries.map { it.wire } +
            com.nuvio.tv.playback.core.StreamHeightCode.entries.map { it.wire } +
            com.nuvio.tv.playback.core.StreamHdrCode.entries.map { it.wire }
        val added = formatted.properties.filterKeys {
            it == "rejected_by" || it == "candidate_graphs" || it.startsWith("rejected_") || it.startsWith("stream_")
        }
        assertEquals(
            setOf(
                "rejected_by", "candidate_graphs", "rejected_engine_or_surface", "rejected_decoder_preference",
                "stream_codec", "stream_container", "stream_height", "stream_hdr",
            ),
            added.keys,
        )
        added.forEach { (key, value) ->
            when (value) {
                is Int -> assertTrue("$key must be a non-negative count", value >= 0)
                is String -> assertTrue("$key=$value must be an allow-listed code", value in allowedStrings)
                else -> throw AssertionError("$key has a non enum/number value: $value")
            }
        }
        // The privacy guard must not strip them (it drops keys named code/state/url/token).
        assertEquals(formatted.properties, PostHogPrivacy.sanitize(formatted.properties))
    }

    @Test
    fun `failures without a graph rejection add no rejection properties`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 1,
                code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                failure = PlaybackFailure(
                    FailureCode.NO_ELIGIBLE_GRAPH,
                    FailureDomain.DEVICE_RESOURCE,
                    FailurePhase.PLAYBACK,
                    Retryability.FATAL,
                ),
            ),
        )
        assertFalse(formatted.properties.keys.any { it.startsWith("rejected_") || it.startsWith("stream_") })
        assertFalse("candidate_graphs" in formatted.properties)
    }

    private fun noEligibleGraphEvent() = PlaybackDiagnosticEvent(
        generation = 9,
        code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
        failure = PlaybackFailure(
            code = FailureCode.NO_ELIGIBLE_GRAPH,
            domain = FailureDomain.UNKNOWN,
            phase = FailurePhase.GRAPH_SELECTION,
            retryability = Retryability.FATAL,
            deterministic = true,
            graphRejection = com.nuvio.tv.playback.core.GraphRejection(
                rejectedBy = com.nuvio.tv.playback.core.GraphRejectionReason.DECODER_PREFERENCE,
                candidateGraphs = 3,
                removedBy = mapOf(
                    com.nuvio.tv.playback.core.GraphRejectionReason.ENGINE_OR_SURFACE to 1,
                    com.nuvio.tv.playback.core.GraphRejectionReason.DECODER_PREFERENCE to 2,
                ),
                stream = com.nuvio.tv.playback.core.StreamFactCodes(
                    codec = com.nuvio.tv.playback.core.StreamCodecCode.HEVC,
                    container = com.nuvio.tv.playback.core.StreamContainerCode.TS,
                    height = com.nuvio.tv.playback.core.StreamHeightCode.UHD,
                    hdr = com.nuvio.tv.playback.core.StreamHdrCode.DV,
                ),
            ),
        ),
    )
}

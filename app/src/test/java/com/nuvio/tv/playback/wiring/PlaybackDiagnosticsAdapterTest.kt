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
                "failure_domain", "failure_phase", "retryability", "deterministic", "http_class",
                PostHogPrivacy.GEOIP_DISABLE_PROPERTY,
            ),
            formatted.properties.keys,
        )
        assertEquals("a failure with no HTTP answer says so", "none", formatted.properties["http_class"])
        assertEquals("VIDEO_DECODER", formatted.properties["failure_domain"])
        assertEquals("HANDOFF_ELIGIBLE", formatted.properties["retryability"])
        assertEquals(true, formatted.properties[PostHogPrivacy.GEOIP_DISABLE_PROPERTY])
        assertTrue(formatted.properties.values.all { it is String || it is Number || it is Boolean })
    }

    /** Fleet 11-17: 1,885 NETWORK_UNREACHABLE reports in 3 days and not one said which status refused us. */
    @Test
    fun `an http rejection reports the status number and its provenance, nothing more`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 7,
                code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                engine = EngineType.MEDIA3,
                failure = com.nuvio.tv.playback.core.HttpStatusFailures.forStatus(456, FailurePhase.PLAYBACK),
            ),
        )

        assertEquals("the rejection is still a network failure", "NETWORK_UNREACHABLE", formatted.properties["failure_code"])
        assertEquals("the status number is reported", 456, formatted.properties["http_status"])
        assertEquals("CONFIRMED", formatted.properties["status_provenance"])
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
    fun `a busy freeze event keeps its retryability in the trail`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 3,
                code = PlaybackDiagnosticCode.WATCHDOG_EXPIRED,
                engine = EngineType.MEDIA3,
                outputProfile = com.nuvio.tv.playback.core.GraphOutputProfile.MEDIA3_STANDARD,
                attempt = 2,
                releaseReason = com.nuvio.tv.playback.core.ActiveWorkReleaseReason.HANDOFF,
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

        val trail = DiagTrailPolicy.sanitize(formatted.properties)
        listOf("failure_code", "failure_domain", "failure_phase", "retryability", "freeze_kind", "session_instance")
            .forEach { assertTrue("$it survives the field cap", it in trail) }
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

    private fun decodeHealthEvent(report: com.nuvio.tv.playback.core.DecodeHealthReport) = PlaybackDiagnosticEvent(
        generation = 5,
        code = PlaybackDiagnosticCode.DECODE_HEALTH_ISSUE,
        engine = EngineType.MEDIA3,
        decodeHealth = report,
    )

    private fun fullReport() = com.nuvio.tv.playback.core.DecodeHealthReport(
        issue = com.nuvio.tv.playback.core.DecodeIssue.AUDIO_TIMESTAMP_FAULT,
        fault = com.nuvio.tv.playback.core.DecodeFault.AV_OFFSET,
        shape = com.nuvio.tv.playback.core.DecodeStreamShape(
            container = com.nuvio.tv.playback.core.StreamContainerCode.TS,
            videoCodec = com.nuvio.tv.playback.core.StreamCodecCode.HEVC,
            videoHeight = com.nuvio.tv.playback.core.StreamHeightCode.UHD,
            videoFps = 50f,
            hdr = com.nuvio.tv.playback.core.StreamHdrCode.HDR10,
            audioCodec = "eac3",
            audioChannels = 6,
            audioSampleRate = 48_000,
            decoder = "c2.mtk.hevc.decoder",
            decoderMode = com.nuvio.tv.playback.core.DecoderMode.HARDWARE,
        ),
        avOffsetBucket = "1-10s",
        secondsInBucket = 5,
        framesDropped = 12,
        underruns = 1,
        sinkErrors = 3,
        surface = com.nuvio.tv.playback.core.DecodeSurface.MULTIVIEW_TILE,
        tileCount = 4,
    )

    @Test
    fun `a decode health issue emits exactly the documented properties`() {
        val formatted = PlaybackDiagnosticFormatter.format(decodeHealthEvent(fullReport()))

        assertEquals("clean_playback_diagnostic", formatted.eventName)
        assertEquals(
            setOf(
                "schema_version", "generation", "diagnostic_code", "engine",
                "issue", "fault", "container", "audio_codec", "audio_channels", "audio_sample_rate",
                "video_codec", "video_height", "video_fps", "hdr", "decoder", "decode_mode",
                "av_offset_bucket", "seconds_in_bucket", "frames_dropped", "underruns", "sink_errors",
                "surface", "tile_count",
                PostHogPrivacy.GEOIP_DISABLE_PROPERTY,
            ),
            formatted.properties.keys,
        )
        assertEquals("DECODE_HEALTH_ISSUE", formatted.properties["diagnostic_code"])
        assertEquals("AUDIO_TIMESTAMP_FAULT", formatted.properties["issue"])
        assertEquals("AV_OFFSET", formatted.properties["fault"])
        assertEquals("hevc", formatted.properties["video_codec"])
        assertEquals("c2_mtk_hevc_decoder", formatted.properties["decoder"])
        assertEquals("hw", formatted.properties["decode_mode"])
        assertEquals("50", formatted.properties["video_fps"])
        assertEquals("multiview_tile", formatted.properties["surface"])
        assertEquals(4, formatted.properties["tile_count"])
    }

    @Test
    fun `a video stream reports b frames and reorder depth and an unknown one only the word`() {
        val known = PlaybackDiagnosticFormatter.format(
            decodeHealthEvent(fullReport().copy(shape = fullReport().shape.copy(hasVideo = true, reorderDepth = 2))),
        )
        assertEquals("yes", known.properties["b_frames"])
        assertEquals(2, known.properties["reorder_depth"])
        val unknown = PlaybackDiagnosticFormatter.format(
            decodeHealthEvent(fullReport().copy(shape = fullReport().shape.copy(hasVideo = true))),
        )
        assertEquals("unknown", unknown.properties["b_frames"])
        assertFalse("reorder_depth" in unknown.properties)
        val audioOnly = PlaybackDiagnosticFormatter.format(decodeHealthEvent(fullReport()))
        assertFalse("b_frames" in audioOnly.properties)
    }

    @Test
    fun `decode health properties survive the privacy filter and carry no forbidden name or url`() {
        val formatted = PlaybackDiagnosticFormatter.format(decodeHealthEvent(fullReport()))
        val forbidden = setOf("code", "state", "url", "token")

        assertTrue("no forbidden name", formatted.properties.keys.none { it in forbidden })
        assertFalse(
            "no name ending in a url-like suffix or holding a secret word",
            formatted.properties.keys.any { key ->
                key.endsWith("_url") || key.endsWith("_uri") ||
                    listOf("token", "secret", "password", "cookie", "authorization").any { key.contains(it) }
            },
        )
        assertTrue(
            "no url-like value",
            formatted.properties.values.none { it is String && (it.contains("://") || it.contains("redacted") || it.contains(".")) },
        )
        assertTrue(formatted.properties.values.all { it is String || it is Number || it is Boolean })
        // The privacy filter drops nothing the formatter produced.
        assertEquals(formatted.properties, PostHogPrivacy.sanitize(formatted.properties))
    }

    @Test
    fun `a hostile decoder name is reduced to an identifier`() {
        val report = fullReport().copy(
            shape = fullReport().shape.copy(
                decoder = "https://panel.example/live/u/p/1.ts?token=abc",
                audioCodec = "audio/x-weird codec",
            ),
        )
        val formatted = PlaybackDiagnosticFormatter.format(decodeHealthEvent(report))

        val decoder = formatted.properties["decoder"] as String
        assertTrue("identifier characters only: $decoder", decoder.matches(Regex("[A-Za-z0-9_:-]+")))
        assertFalse(decoder.contains("://"))
        assertTrue((formatted.properties["audio_codec"] as String).matches(Regex("[A-Za-z0-9_:-]+")))
    }

    @Test
    fun `a decode health issue with nothing known still reports only what is known`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            decodeHealthEvent(com.nuvio.tv.playback.core.DecodeHealthReport(issue = com.nuvio.tv.playback.core.DecodeIssue.VIDEO_DROP_BURST)),
        )
        assertEquals("VIDEO_DROP_BURST", formatted.properties["issue"])
        assertFalse("fault" in formatted.properties)
        assertFalse("surface" in formatted.properties)
        assertFalse("tile_count" in formatted.properties)
        assertEquals(0L, formatted.properties["frames_dropped"])
    }

    @Test
    fun `decode health uploads and the gate sends it with the on device trail`() {
        val formatted = PlaybackDiagnosticFormatter.format(decodeHealthEvent(fullReport()))
        assertTrue("non routine, so it uploads", PlaybackDiagnosticUploadPolicy.shouldUpload(formatted))

        val decision = com.nuvio.tv.core.telemetry.TelemetrySendPolicy.decide(formatted.eventName, formatted.properties)
        assertEquals(com.nuvio.tv.core.telemetry.TelemetrySendPolicy.Action.SEND, decision.action)
        assertTrue("with diag_trail", decision.withTrail)

        val kept = DiagTrailPolicy.sanitize(formatted.properties)
        listOf("issue", "fault", "surface", "decode_mode", "video_codec", "sink_errors", "tile_count").forEach {
            assertTrue("the trail keeps $it", it in kept)
        }
    }

    @Test
    fun `a live failure carries the stream shape and the start engine as bucketed codes`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(
                generation = 2,
                code = PlaybackDiagnosticCode.ENGINE_REPORTED_FAILURE,
                engine = EngineType.LIBMPV,
                engineAtStart = EngineType.MEDIA3,
                stream = fullReport().shape,
                failure = com.nuvio.tv.playback.core.HttpStatusFailures.forStatus(456, FailurePhase.PLAYBACK),
            ),
        )
        assertEquals("MEDIA3", formatted.properties["engine_at_start"])
        assertEquals("hevc", formatted.properties["stream_codec"])
        assertEquals("ts", formatted.properties["stream_container"])
        assertEquals("uhd", formatted.properties["stream_height"])
        assertEquals("hdr10", formatted.properties["stream_hdr"])
        assertEquals("50", formatted.properties["stream_fps"])
        assertEquals("eac3", formatted.properties["stream_audio_codec"])
        assertTrue(formatted.properties.values.none { it is String && it.contains("://") })
        assertTrue(formatted.properties.keys.none { it == "code" || it == "state" || it == "url" || it == "token" })
    }

    @Test
    fun `a failure without a known shape adds no stream properties`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(generation = 2, code = PlaybackDiagnosticCode.ENGINE_REPORTED_FAILURE, engine = EngineType.MEDIA3),
        )
        assertFalse(formatted.properties.keys.any { it.startsWith("stream_") || it == "engine_at_start" })
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

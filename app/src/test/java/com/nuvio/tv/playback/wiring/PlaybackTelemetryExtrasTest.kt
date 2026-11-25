package com.nuvio.tv.playback.wiring

import com.nuvio.tv.core.telemetry.playback.PlaybackTelemetryPrivacy
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.HttpStatusFailures
import com.nuvio.tv.playback.core.PlaybackDiagnosticCode
import com.nuvio.tv.playback.core.PlaybackDiagnosticContext
import com.nuvio.tv.playback.core.PlaybackDiagnosticEvent
import com.nuvio.tv.playback.core.PlaybackDiagnostics
import com.nuvio.tv.playback.core.PlaybackSignalKind
import com.nuvio.tv.playback.core.ProviderSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The failure extras of the playback telemetry contract on `clean_playback_diagnostic`. */
class PlaybackTelemetryExtrasTest {

    private fun failureEvent(status: Int?, source: ProviderSourceType? = null) = PlaybackDiagnosticEvent(
        generation = 1,
        code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
        engine = EngineType.MEDIA3,
        failure = status?.let { HttpStatusFailures.forStatus(it, FailurePhase.PLAYBACK) }
            ?: HttpStatusFailures.forStatus(200, FailurePhase.PLAYBACK).copy(httpStatus = null),
        sourceType = source,
    )

    @Test
    fun `a failure carries the http class and the provider kind as closed enums`() {
        val formatted = PlaybackDiagnosticFormatter.format(failureEvent(456, ProviderSourceType.STALKER))
        assertEquals("456", formatted.properties["http_class"])
        assertEquals("stalker", formatted.properties["source_type"])
        val extras = formatted.properties.filterKeys { it == "http_class" || it == "source_type" }
        assertEquals("extras pass the privacy validator", emptyList<String>(), PlaybackTelemetryPrivacy.violations(extras))
    }

    @Test
    fun `every status family maps to a validator approved class and every source kind is an enum`() {
        for (status in listOf(null, 301, 401, 403, 404, 410, 429, 456, 458, 500, 503)) for (source in ProviderSourceType.entries + listOf(null)) {
            val props = PlaybackDiagnosticFormatter.format(failureEvent(status, source)).properties
            val extras = props.filterKeys { it == "http_class" || it == "source_type" }
            assertEquals("status=$status source=$source", emptyList<String>(), PlaybackTelemetryPrivacy.violations(extras))
        }
    }

    @Test
    fun `no source type is sent when the host did not say`() {
        assertNull(PlaybackDiagnosticFormatter.format(failureEvent(404)).properties["source_type"])
    }

    @Test
    fun `an event with no failure gets neither extra`() {
        val formatted = PlaybackDiagnosticFormatter.format(
            PlaybackDiagnosticEvent(generation = 1, code = PlaybackDiagnosticCode.GRAPH_SELECTED, engine = EngineType.MEDIA3),
        )
        assertFalse("http_class" in formatted.properties)
        assertFalse("source_type" in formatted.properties)
    }

    @Test
    fun `the diagnostics wrapper adds the host's provider kind and never overwrites one already set`() {
        val seen = ArrayList<PlaybackDiagnosticEvent>()
        val context = PlaybackDiagnosticContext()
        val wrapper = ContextualPlaybackDiagnostics(PlaybackDiagnostics { seen += it }, context)
        val base = failureEvent(404)

        wrapper.record(base)
        context.sourceType = ProviderSourceType.XTREAM
        wrapper.record(base)
        wrapper.record(base.copy(sourceType = ProviderSourceType.M3U))

        assertNull("host had not said", seen[0].sourceType)
        assertEquals(ProviderSourceType.XTREAM, seen[1].sourceType)
        assertEquals("an explicit value wins", ProviderSourceType.M3U, seen[2].sourceType)
    }

    @Test
    fun `signal kinds map to the contract kinds and engines`() {
        assertEquals("multiview_tile", PlaybackSignalKind.MULTIVIEW_TILE.toPlayKind().wire)
        assertEquals("preview", PlaybackSignalKind.PREVIEW.toPlayKind().wire)
        assertEquals("libmpv", EngineType.LIBMPV.toPlayEngine().wire)
        assertEquals("other", (null as EngineType?).toPlayEngine().wire)
        assertEquals("decoder_failed", PlaybackTelemetrySignals.triggerOf(FailureCode.VIDEO_DECODER_FAILED).wire)
        assertEquals("other", PlaybackTelemetrySignals.triggerOf(null).wire)
    }
}

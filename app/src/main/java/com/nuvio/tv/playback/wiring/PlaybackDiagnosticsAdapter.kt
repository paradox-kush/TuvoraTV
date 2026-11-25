package com.nuvio.tv.playback.wiring

import android.os.Build
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.playback.core.DecodeStreamShape
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.PlaybackDiagnosticEvent
import com.nuvio.tv.playback.core.PlaybackDiagnostics
import com.nuvio.tv.playback.core.PlaybackDiagnosticCode
import com.nuvio.tv.playback.core.PlaybackQualitySummary
import com.nuvio.tv.core.analytics.PostHogPrivacy
import com.nuvio.tv.core.telemetry.playback.PlaybackTelemetry
import com.nuvio.tv.core.telemetry.playback.PlaybackTelemetryBuckets
import com.nuvio.tv.core.telemetry.playback.PlaybackTelemetryKeys
import com.posthog.PostHog

data class FormattedPlaybackDiagnostic(
    val eventName: String,
    val properties: Map<String, Any>,
)

/** Last-mile formatter whose inputs expose no endpoint, provider, account, or request secret. */
object PlaybackDiagnosticFormatter {
    fun format(
        event: PlaybackDiagnosticEvent,
        buildId: String? = null,
    ): FormattedPlaybackDiagnostic {
        val properties = linkedMapOf<String, Any>(
            "schema_version" to 1,
            "generation" to event.generation,
            "diagnostic_code" to event.code.name,
        )
        event.engine?.let { properties["engine"] = it.name }
        event.outputProfile?.let { properties["output_profile"] = it.name }
        event.attempt?.let { properties["attempt"] = it }
        event.outputStatus?.let { properties["output_status"] = it.name }
        event.releaseReason?.let { properties["release_reason"] = it.name }
        event.releaseStep?.let(::identifier)?.let { properties["release_step"] = it }
        event.formatStep?.let(::identifier)?.let { properties["format_step"] = it }
        event.formatPlayed?.let { properties["format_played"] = it }
        event.highResolution?.let { properties["high_resolution"] = it }
        event.changeImpact?.let { properties["change_impact"] = it.name }
        event.changedFields?.let { fields ->
            properties["changed_fields"] = fields.map { it.name }.sorted().joinToString(",")
        }
        event.freeze?.let { freeze ->
            // Closed enums plus identifier-safe strings (dots become `_`): never a URL, host or message.
            properties["freeze_kind"] = freeze.kind.name
            properties["ladder_rung"] = freeze.rung.name
            freeze.decoder?.let(::identifier)?.let { properties["decoder"] = it }
            buildId?.let(::identifier)?.let { properties["build_id"] = it }
            identifier(freeze.sessionInstance)?.let { properties["session_instance"] = it }
        }
        event.engineAtStart?.let { properties["engine_at_start"] = it.name }
        event.stream?.let { shape ->
            // B265: short codes only, so a failure says what kind of stream failed. Never a URL or host.
            shape.videoCodec?.let { properties["stream_codec"] = it.wire }
            shape.container?.let { properties["stream_container"] = it.wire }
            shape.videoHeight?.let { properties["stream_height"] = it.wire }
            shape.hdr?.let { properties["stream_hdr"] = it.wire }
            shape.fpsBucket()?.let { properties["stream_fps"] = it }
            shape.audioCodec?.let(::identifier)?.let { properties["stream_audio_codec"] = it }
        }
        event.decodeHealth?.let { report ->
            // Closed enums, identifier-safe codes and counts only. Never `code`, `state`, `url` or `token`
            // as a name (PostHogPrivacy redacts them), never a host, channel name or message.
            properties["issue"] = report.issue.name
            report.fault?.let { properties["fault"] = it.name }
            val shape = report.shape
            shape.container?.let { properties["container"] = it.wire }
            shape.audioCodec?.let(::identifier)?.let { properties["audio_codec"] = it }
            shape.audioChannels?.let { properties["audio_channels"] = it }
            shape.audioSampleRate?.let { properties["audio_sample_rate"] = it }
            shape.videoCodec?.let { properties["video_codec"] = it.wire }
            shape.videoHeight?.let { properties["video_height"] = it.wire }
            shape.fpsBucket()?.let { properties["video_fps"] = it }
            shape.hdr?.let { properties["hdr"] = it.wire }
            shape.decoder?.let(::identifier)?.let { properties["decoder"] = it }
            shape.decoderMode?.let { properties["decode_mode"] = if (it == DecoderMode.HARDWARE) "hw" else "sw" }
            shape.bFrames()?.let { properties["b_frames"] = it }
            if (shape.hasVideo) shape.reorderDepth?.takeIf { it in 0..DecodeStreamShape.MAX_REORDER_DEPTH }
                ?.let { properties["reorder_depth"] = it }
            report.avOffsetBucket?.let { properties["av_offset_bucket"] = it }
            report.secondsInBucket?.let { properties["seconds_in_bucket"] = it }
            properties["frames_dropped"] = report.framesDropped
            properties["underruns"] = report.underruns
            properties["sink_errors"] = report.sinkErrors
            report.surface?.let { properties["surface"] = it.wire }
            report.tileCount?.takeIf { it > 0 }?.let { properties["tile_count"] = it }
        }
        event.failure?.let { failure ->
            properties["failure_code"] = failure.code.name
            properties["failure_domain"] = failure.domain.name
            properties["failure_phase"] = failure.phase.name
            properties["retryability"] = failure.retryability.name
            properties["deterministic"] = failure.deterministic
            // The status number only (never the response line, URL or host).
            failure.httpStatus?.let { properties["http_status"] = it }
            // Playback telemetry contract: the status as a class (also `none` when there was no HTTP answer).
            properties["http_class"] = PlaybackTelemetryBuckets.httpClass(failure.httpStatus)
            event.sourceType?.let { PlaybackTelemetryKeys.sourceType(it.name) }?.let { properties["source_type"] = it }
            failure.statusProvenance?.let { properties["status_provenance"] = it.name }
            failure.graphRejection?.let { rejection ->
                // Closed enums, wire codes and counts only (never a mime type, URL or host).
                properties["rejected_by"] = rejection.rejectedBy.name
                rejection.candidateGraphs?.let { properties["candidate_graphs"] = it }
                rejection.stream.codec?.let { properties["stream_codec"] = it.wire }
                rejection.stream.container?.let { properties["stream_container"] = it.wire }
                rejection.stream.height?.let { properties["stream_height"] = it.wire }
                rejection.stream.hdr?.let { properties["stream_hdr"] = it.wire }
                rejection.removedBy.forEach { (reason, count) ->
                    if (count > 0) properties["rejected_${reason.name.lowercase()}"] = count
                }
            }
        }
        return FormattedPlaybackDiagnostic(
            "clean_playback_diagnostic",
            PostHogPrivacy.sanitize(properties),
        )
    }

    private val unsafeIdentifierChars = Regex("[^A-Za-z0-9_:-]+")

    /** Component and firmware names carry dots; the trail allow-list takes identifiers only. */
    private fun identifier(raw: String): String? =
        raw.replace(unsafeIdentifierChars, "_").trim('_').take(40).takeIf { it.isNotEmpty() }
}

fun interface PlaybackDiagnosticSink {
    fun capture(event: FormattedPlaybackDiagnostic)
}

class FormattingPlaybackDiagnostics(
    private val sink: PlaybackDiagnosticSink,
) : PlaybackDiagnostics {
    override fun record(event: PlaybackDiagnosticEvent) {
        val formatted = PlaybackDiagnosticFormatter.format(event, Build.ID)
        // TV's debug flavor is intentionally non-debuggable, so BuildConfig.DEBUG is false.
        // IS_DEBUG_BUILD tracks the build flavor and keeps secret-safe playback diagnostics
        // available in simulator/device validation builds only.
        if (BuildConfig.IS_DEBUG_BUILD) {
            // The formatter has a closed, secret-free schema: no URL, host, account, provider,
            // channel, request header, cookie, or DRM value can cross this debug boundary.
            Log.d(DEBUG_LOG_TAG, "${formatted.eventName} ${formatted.properties}")
        }
        // QA mirror (local logcat, only when debug.tuvora.qa=1): the contract's failure extras, exact values.
        if (event.failure != null) {
            PlaybackTelemetry.diagnosticExtras(
                formatted.properties.filterKeys {
                    it in QA_DIAGNOSTIC_KEYS
                },
            )
        }
        sink.capture(formatted)
    }

    private companion object {
        const val DEBUG_LOG_TAG = "CleanPlaybackDiag"
        val QA_DIAGNOSTIC_KEYS = setOf(
            "failure_code", "http_class", "source_type", "attempt", "stream_container", "stream_height", "engine",
        )
    }
}

/**
 * Adds what only the host knows (the provider kind) to every event its session records. One per session;
 * the shared formatter stays stateless.
 */
class ContextualPlaybackDiagnostics(
    private val delegate: PlaybackDiagnostics,
    private val context: com.nuvio.tv.playback.core.PlaybackDiagnosticContext,
) : PlaybackDiagnostics {
    override fun record(event: PlaybackDiagnosticEvent) {
        val source = context.sourceType
        delegate.record(if (event.sourceType == null && source != null) event.copy(sourceType = source) else event)
    }
}

object PostHogPlaybackDiagnosticSink : PlaybackDiagnosticSink {
    override fun capture(event: FormattedPlaybackDiagnostic) {
        if (PostHogPrivacy.shouldDropEvent(event.eventName)) return
        if (!PlaybackDiagnosticUploadPolicy.shouldUpload(event)) return
        PostHog.capture(event.eventName, properties = PostHogPrivacy.sanitize(event.properties))
    }
}

/**
 * Routine success-path diagnostics with no analytic use stay in the debug log. GRAPH_SELECTED and
 * RELEASE_BARRIER_STARTED keep uploading: per-engine fleet health (handoffs, failures, channel
 * changes) is measured from them.
 */
object PlaybackDiagnosticUploadPolicy {
    private val ROUTINE_CODES = setOf(
        PlaybackDiagnosticCode.REQUEST_RESOLUTION_STARTED.name,
        PlaybackDiagnosticCode.REQUEST_RESOLVED.name,
        PlaybackDiagnosticCode.RELEASE_BARRIER_COMPLETED.name,
        PlaybackDiagnosticCode.REQUIREMENTS_CHANGE_RESOLVED.name,
    )

    fun shouldUpload(event: FormattedPlaybackDiagnostic): Boolean =
        event.properties["diagnostic_code"] !in ROUTINE_CODES
}

/** Receives one playback-quality summary per host session, at release. */
fun interface PlaybackQualityReporter {
    fun report(summary: PlaybackQualitySummary)
}

object PostHogPlaybackQualityReporter : PlaybackQualityReporter {
    const val EVENT = "clean_playback_session"

    override fun report(summary: PlaybackQualitySummary) {
        if (PostHogPrivacy.shouldDropEvent(EVENT)) return
        PostHog.capture(EVENT, properties = PostHogPrivacy.sanitize(summary.toProperties()))
    }
}

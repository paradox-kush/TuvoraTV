package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.core.PlaybackDiagnosticEvent
import com.nuvio.tv.playback.core.PlaybackDiagnostics
import com.nuvio.tv.core.analytics.PostHogPrivacy
import com.posthog.PostHog

data class FormattedPlaybackDiagnostic(
    val eventName: String,
    val properties: Map<String, Any>,
)

/** Last-mile formatter whose inputs expose no endpoint, provider, account, or request secret. */
object PlaybackDiagnosticFormatter {
    fun format(event: PlaybackDiagnosticEvent): FormattedPlaybackDiagnostic {
        val properties = linkedMapOf<String, Any>(
            "schema_version" to 1,
            "generation" to event.generation,
            "diagnostic_code" to event.code.name,
        )
        event.engine?.let { properties["engine"] = it.name }
        event.attempt?.let { properties["attempt"] = it }
        event.failure?.let { failure ->
            properties["failure_code"] = failure.code.name
            properties["failure_domain"] = failure.domain.name
            properties["failure_phase"] = failure.phase.name
            properties["retryability"] = failure.retryability.name
            properties["deterministic"] = failure.deterministic
        }
        return FormattedPlaybackDiagnostic(
            "clean_playback_diagnostic",
            PostHogPrivacy.sanitize(properties),
        )
    }
}

fun interface PlaybackDiagnosticSink {
    fun capture(event: FormattedPlaybackDiagnostic)
}

class FormattingPlaybackDiagnostics(
    private val sink: PlaybackDiagnosticSink,
) : PlaybackDiagnostics {
    override fun record(event: PlaybackDiagnosticEvent) {
        sink.capture(PlaybackDiagnosticFormatter.format(event))
    }
}

object PostHogPlaybackDiagnosticSink : PlaybackDiagnosticSink {
    override fun capture(event: FormattedPlaybackDiagnostic) {
        if (PostHogPrivacy.shouldDropEvent(event.eventName)) return
        PostHog.capture(event.eventName, properties = PostHogPrivacy.sanitize(event.properties))
    }
}

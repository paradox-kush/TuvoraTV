package com.nuvio.tv.playback.wiring

import android.util.Log
import com.nuvio.tv.BuildConfig
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
        event.outputProfile?.let { properties["output_profile"] = it.name }
        event.attempt?.let { properties["attempt"] = it }
        event.outputStatus?.let { properties["output_status"] = it.name }
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
        val formatted = PlaybackDiagnosticFormatter.format(event)
        // TV's debug flavor is intentionally non-debuggable, so BuildConfig.DEBUG is false.
        // IS_DEBUG_BUILD tracks the build flavor and keeps secret-safe playback diagnostics
        // available in simulator/device validation builds only.
        if (BuildConfig.IS_DEBUG_BUILD) {
            // The formatter has a closed, secret-free schema: no URL, host, account, provider,
            // channel, request header, cookie, or DRM value can cross this debug boundary.
            Log.d(DEBUG_LOG_TAG, "${formatted.eventName} ${formatted.properties}")
        }
        sink.capture(formatted)
    }

    private companion object {
        const val DEBUG_LOG_TAG = "CleanPlaybackDiag"
    }
}

object PostHogPlaybackDiagnosticSink : PlaybackDiagnosticSink {
    override fun capture(event: FormattedPlaybackDiagnostic) {
        if (PostHogPrivacy.shouldDropEvent(event.eventName)) return
        PostHog.capture(event.eventName, properties = PostHogPrivacy.sanitize(event.properties))
    }
}

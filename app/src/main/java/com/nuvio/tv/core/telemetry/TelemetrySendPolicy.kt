package com.nuvio.tv.core.telemetry

/**
 * What happens to an analytics event: sent now, kept only in the on-device log, counted into the
 * per-session health summary, or sent once per app version. Errors are always sent; routine
 * success steps never are. Pure, so it is unit tested without PostHog.
 */
object TelemetrySendPolicy {
    enum class Action { SEND, RECORD, COUNT, SEND_ONCE_PER_VERSION }

    data class Decision(
        val action: Action,
        /** Attach the recent on-device trail to the event. */
        val withTrail: Boolean = false,
        val area: DiagArea = DiagArea.LIFECYCLE,
        val code: String = "event",
    )

    /** Outcomes that mean "worked" for the load-style events. */
    // Empty is counted by surface and source type in `session_health` (an M3U that loads "nothing" is a
    // signal worth a rate, not an event each); failed and timeout are always sent.
    private val loadSuccess = setOf("loaded", "empty")
    private val epgSuccess = setOf("ok", "empty", "skipped")
    private val indexSuccess = setOf("ok", "success", "built", "category_fallback")

    private val alwaysSendWithTrail = setOf(
        "live_playback_freeze", "live_playback_error", "live_preview_stall", "mpv_lifecycle_warning",
        "app_crash_report", "user_issue_report", "simkl_match_failure",
        "app_uncaught_exception_ios",
    )

    private fun number(v: Any?): Int = (v as? Number)?.toInt() ?: 0

    fun decide(event: String, properties: Map<String, Any?>): Decision {
        val outcome = (properties["outcome"] as? String)?.lowercase()
        return when (event) {
            "\$screen", "screen_view" ->
                Decision(Action.RECORD, area = DiagArea.SCREEN, code = (properties["\$screen_name"] as? String) ?: "screen")

            "playback_started" -> Decision(Action.COUNT, area = DiagArea.PLAYBACK, code = "started")

            "clean_playback_diagnostic" -> {
                val failure = properties["failure_code"] as? String
                if (!failure.isNullOrBlank()) {
                    Decision(Action.SEND, withTrail = true, area = DiagArea.PLAYBACK, code = failure)
                } else {
                    Decision(
                        Action.RECORD,
                        area = DiagArea.PLAYBACK,
                        code = (properties["diagnostic_code"] as? String) ?: "diagnostic",
                    )
                }
            }

            "iptv_load" ->
                if (outcome in loadSuccess) Decision(Action.COUNT, area = DiagArea.IPTV_LOAD, code = outcome ?: "loaded")
                else Decision(Action.SEND, withTrail = true, area = DiagArea.IPTV_LOAD, code = outcome ?: "failed")

            "epg_ingest" ->
                if (outcome in epgSuccess) Decision(Action.COUNT, area = DiagArea.EPG, code = outcome ?: "ok")
                else Decision(Action.SEND, withTrail = true, area = DiagArea.EPG, code = outcome ?: "failed")

            // `epg_resolve` is a tally of counts with no outcome: it only matters when a panel ask FAILED.
            "epg_resolve" ->
                if (number(properties["unavailable"]) > 0) Decision(Action.SEND, withTrail = true, area = DiagArea.EPG, code = "resolve_unavailable")
                else Decision(Action.COUNT, area = DiagArea.EPG, code = "resolve")

            // `epg_mapping` has no outcome either: a collapse to zero matches is the one event worth sending.
            "epg_mapping" ->
                if (number(properties["matched"]) == 0 && number(properties["channels"]) > 0)
                    Decision(Action.SEND, withTrail = true, area = DiagArea.EPG, code = "mapping_empty")
                else Decision(Action.COUNT, area = DiagArea.EPG, code = "mapping")

            "iptv_index_build" ->
                if (outcome in indexSuccess) Decision(Action.COUNT, area = DiagArea.IPTV_LOAD, code = "index_$outcome")
                else Decision(Action.SEND, withTrail = true, area = DiagArea.IPTV_LOAD, code = "index_${outcome ?: "failed"}")

            "audio_output_profile" -> Decision(Action.SEND_ONCE_PER_VERSION, area = DiagArea.PLAYBACK, code = "audio_profile")

            // The exit record stays: it is the only signal when our crash handler could not run
            // (out of memory, a crash before it was installed) and it carries the previous run's trail.
            "app_exit" -> Decision(Action.SEND, withTrail = true, area = DiagArea.LIFECYCLE, code = "exit")

            // The `$exception` promoted from a crash exit repeats what `app_crash_report` and the exit
            // record already say.
            // posthog-android 3.x / core 6.x send `$exception_list`; older SDKs sent `$exception_values`.
            "\$exception" ->
                if (properties["\$exception_list"].toString().contains("exited abnormally: crash") ||
                    properties["\$exception_values"].toString().contains("exited abnormally: crash"))
                    Decision(Action.COUNT, area = DiagArea.LIFECYCLE, code = "exit_crash")
                else Decision(Action.SEND, withTrail = true, area = DiagArea.LIFECYCLE, code = "exception")

            in alwaysSendWithTrail -> Decision(Action.SEND, withTrail = true, area = DiagArea.LIFECYCLE, code = event)

            // Unknown events are sent unchanged: losing a signal silently is worse than one extra event.
            else -> Decision(Action.SEND, area = DiagArea.LIFECYCLE, code = event)
        }
    }
}

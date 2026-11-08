package com.nuvio.tv.core.telemetry

/**
 * Applies [TelemetrySendPolicy] to every event PostHog is about to send. Plugged into PostHog's
 * `beforeSend`, so the ~20 existing `PostHog.capture` call sites keep working unchanged and none
 * can bypass the policy. Pure apart from the injected stores, so it is unit tested without PostHog.
 */
class TelemetryGate(
    private val recorder: FlightRecorder,
    private val health: SessionHealth,
    private val onceStore: OncePerVersionStore,
    private val appVersion: String,
    /** Trail from the PREVIOUS run, for next-launch exit reports; null when there is none. */
    private val previousRun: () -> List<DiagEntry>,
    private val currentTrailMax: Int = TRAIL_ON_ERROR,
) {
    sealed interface Result {
        /** Send, with these properties (the original ones plus `diag_trail` when due). */
        data class Pass(val properties: Map<String, Any?>, val isError: Boolean = false) : Result
        data object Drop : Result
    }

    fun process(event: String, properties: Map<String, Any?>): Result {
        val decision = TelemetrySendPolicy.decide(event, properties)
        val fields = properties.filterKeys { it in DiagTrailPolicy.ALLOWED_FIELDS }
        return when (decision.action) {
            TelemetrySendPolicy.Action.RECORD -> {
                recorder.record(decision.area, decision.code, fields)
                Result.Drop
            }
            TelemetrySendPolicy.Action.COUNT -> {
                health.increment(health.key(event, properties))
                Result.Drop
            }
            TelemetrySendPolicy.Action.SEND_ONCE_PER_VERSION ->
                if (onceStore.firstTime("$event@$appVersion")) Result.Pass(properties) else Result.Drop
            TelemetrySendPolicy.Action.SEND -> {
                // An error is also a step in the log, recorded before the trail is cut so it is the last line.
                if (decision.withTrail) recorder.record(decision.area, decision.code, fields)
                if (!decision.withTrail) return Result.Pass(properties)
                if (properties.containsKey("diag_trail")) return Result.Pass(properties, isError = true)
                val entries = if (event == "app_exit") previousRun() else recorder.snapshot(currentTrailMax)
                val trail = DiagTrailPolicy.render(entries, currentTrailMax)
                if (trail.isEmpty()) Result.Pass(properties, isError = true)
                else Result.Pass(properties + ("diag_trail" to trail), isError = true)
            }
        }
    }

    companion object {
        const val TRAIL_ON_ERROR = 40
        const val TRAIL_ON_REPORT = 80
    }
}

/** Remembers which `event@version` pairs were already sent. */
interface OncePerVersionStore {
    /** True the first time [key] is seen, false afterwards (persisted). */
    fun firstTime(key: String): Boolean
}

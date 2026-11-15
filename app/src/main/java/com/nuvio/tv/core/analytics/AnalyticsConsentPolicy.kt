package com.nuvio.tv.core.analytics

/**
 * Turns each stored diagnostics-consent value into what the analytics SDK must do, without
 * Application.onCreate blocking on the DataStore read (that runBlocking was a cold-start ANR).
 *
 * The SDK is set up opted out ([SDK_STARTS_OPTED_OUT]), so nothing is sent until the first stored
 * value arrives. First value: opt in and run the once-per-process start-up reports (pending exits,
 * pending crashes), or stay opted out. Later values only toggle — a stored opt-out is never overridden
 * and the start-up reports never run twice. Pure: no Android, no SDK.
 */
class AnalyticsConsentPolicy {
    enum class Action { OPT_IN, OPT_OUT, REPORT_PENDING_EXITS }

    /** False until the first stored value: until then the SDK stays opted out. */
    @Volatile
    var consentKnown: Boolean = false
        private set

    @Synchronized
    fun onStoredConsent(enabled: Boolean): List<Action> {
        val first = !consentKnown
        consentKnown = true
        return when {
            !enabled -> listOf(Action.OPT_OUT)
            first -> listOf(Action.OPT_IN, Action.REPORT_PENDING_EXITS)
            else -> listOf(Action.OPT_IN)
        }
    }

    companion object {
        const val SDK_STARTS_OPTED_OUT = true
    }
}

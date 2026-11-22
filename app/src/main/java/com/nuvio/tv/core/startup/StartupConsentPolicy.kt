package com.nuvio.tv.core.startup

/**
 * What `Application.onCreate` does with the "Crash & usage reports" consent, given how the startup read
 * went. Pure, so the consent semantics are tested without Android, PostHog or DataStore.
 *
 * The read happens off the main thread (overlapping Hilt injection) and the main thread waits for it for
 * at most [CONSENT_WAIT_MS]. A headless start (JobService, receiver, bind) runs `Application.onCreate`
 * inside the system's bindApplication timeout, so an unbounded wait there is an ANR on a starved box.
 *
 * Hard rule: until consent is known to be ON, nothing is sent. So when the read has not answered in
 * time the process starts opted OUT, but does NOT apply "off" to telemetry (that wipes the on-device
 * trail of a consenting user) and does NOT release the held first-run funnel events (they would be
 * dropped at delivery). The consent collector settles all of it when DataStore answers ([onAnswer]).
 */
object StartupConsentPolicy {

    /** Longest the main thread waits for the consent read before starting opted out. */
    const val CONSENT_WAIT_MS: Long = 2_000L

    /**
     * @property optOut PostHog's opt state at setup.
     * @property known the read answered; false = still pending, the collector decides later.
     * @property applyConsentNow call `telemetry.applyConsent(value)` now (false = leave the default "off,
     *   nothing written", never the wiping `applyConsent(false)`).
     * @property runConsentedWorkNow consent is ON: report pending exits, send pending crashes now.
     * @property releaseFunnelNow the opt state is final: held first-run funnel events may go out now.
     */
    data class InitialConsent(
        val optOut: Boolean,
        val known: Boolean,
        val applyConsentNow: Boolean,
        val runConsentedWorkNow: Boolean,
        val releaseFunnelNow: Boolean,
    )

    /** What a consent value from the collector must additionally trigger. */
    data class LateAnswer(
        val runConsentedWork: Boolean,
        val releaseFunnel: Boolean,
    )

    /** @param read the startup read: true/false, or null when it did not answer within [CONSENT_WAIT_MS] (or failed). */
    fun initial(read: Boolean?): InitialConsent = when (read) {
        true -> InitialConsent(optOut = false, known = true, applyConsentNow = true, runConsentedWorkNow = true, releaseFunnelNow = true)
        false -> InitialConsent(optOut = true, known = true, applyConsentNow = true, runConsentedWorkNow = false, releaseFunnelNow = true)
        null -> InitialConsent(optOut = true, known = false, applyConsentNow = false, runConsentedWorkNow = false, releaseFunnelNow = false)
    }

    /**
     * A value from the consent collector. Only the FIRST answer after an unresolved start does the
     * deferred startup work; later toggles are ordinary opt-in/opt-out and never repeat it.
     *
     * @param deferredSettled the deferred startup work was already settled by an earlier answer.
     */
    fun onAnswer(initial: InitialConsent, enabled: Boolean, deferredSettled: Boolean): LateAnswer {
        if (initial.known || deferredSettled) return LateAnswer(runConsentedWork = false, releaseFunnel = false)
        return LateAnswer(runConsentedWork = enabled, releaseFunnel = true)
    }
}

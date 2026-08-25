package com.nuvio.tv.core.radar

/**
 * Decides whether a provider VOD title is genuinely a recording of a sports fixture.
 *
 * A title is accepted when it names BOTH teams (a home token AND an away token). The event-token
 * fallback — "at least two words of the event string appear" — is honoured ONLY for event-only
 * fixtures that have no home/away (motorsport, golf, a named tournament final). For a team fixture
 * the backend fills `event` with "Home vs Away", so two words of a SINGLE team ("Dallas" +
 * "Cowboys") would satisfy the loose fallback and accept an unrelated title — this is exactly how
 * "Arizona Cardinals v Dallas Cowboys" pulled in the documentary "…the Untold Story of the Dallas
 * Cowboys Cheerleaders". Gating the fallback on "no home/away" is the fix, kept here in one place so
 * `findRecordings` and the channel matcher can't drift on the rule (they share this gating).
 *
 * Pure and clock-free: the caller supplies the normalised-title membership test as [matches].
 */
internal object SportsRecordingMatchPolicy {

    /**
     * @param homeTokens  home team's significant tokens (empty for event-only fixtures)
     * @param awayTokens  away team's significant tokens (empty for event-only fixtures)
     * @param eventTokens tokens of the event string (may be passed unconditionally; honoured only
     *                    when there is no home/away)
     * @param matches     does the candidate title contain this token? (the caller's word-boundary test)
     */
    fun accepts(
        homeTokens: List<String>,
        awayTokens: List<String>,
        eventTokens: List<String>,
        matches: (String) -> Boolean,
    ): Boolean {
        if (homeTokens.any(matches) && awayTokens.any(matches)) return true
        val eventOnly = homeTokens.isEmpty() && awayTokens.isEmpty()
        return eventOnly && eventTokens.isNotEmpty() && eventTokens.count(matches) >= 2
    }
}

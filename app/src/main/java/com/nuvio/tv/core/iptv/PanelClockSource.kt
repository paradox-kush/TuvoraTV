package com.nuvio.tv.core.iptv

/**
 * The panel's measured clock-pair offset ([ServerClockOffset]) for one playlist, or null when the
 * panel's clocks are junk or it is not an Xtream panel.
 *
 * A port so the catch-up policy stays testable without a panel: production reads the
 * session-memoized [XtreamClient.measuredClockOffsetMs] (at most one `player_api.php` call per
 * playlist per session, usually none — verify and the guide's epoch-skew vote seed it).
 */
fun interface PanelClockSource {
    suspend fun offsetMs(account: XtreamAccount): Long?

    companion object {
        /** No measurement: the replay start falls back to the manual correction alone (UTC when unset). */
        val NONE = PanelClockSource { null }
    }
}

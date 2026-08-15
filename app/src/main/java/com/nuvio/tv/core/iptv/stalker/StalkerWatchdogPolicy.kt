package com.nuvio.tv.core.iptv.stalker

/**
 * Cadence + wire shape of the Stalker watchdog keep-alive, from the portal's own client and server
 * (`c/watchdog.js`, `c/xpcom.common.js`, `server/lib/watchdog.class.php`, `server/config.ini`):
 *
 *  - `get_profile` advertises `watchdog_timeout` (seconds, config default 120) and a per-user
 *    `timeslot` (FLOAT seconds — `parseFloat` in the portal's own JS) that staggers clients so
 *    they don't all ping on the same second;
 *  - the client pings `type=watchdog&action=get_events` with `init=1` once at activation and
 *    `init=0` on every later tick. `init` must be PRESENT on every ping — its presence gates the
 *    server's MAC-clone logging (`isset($_REQUEST['init'])`);
 *  - a missed ping only affects the portal's "online" reporting (the users.keep_alive column) —
 *    it NEVER invalidates authentication. Failures are log-only, never retried or escalated, and
 *    the keep-alive must never re-handshake on its own (a handshake evicts the other device on a
 *    shared MAC).
 *
 * Pure cadence/param math; the timer itself lives in [StalkerSession].
 */
object StalkerWatchdogPolicy {

    const val DEFAULT_PERIOD_SECONDS = 120

    /** Guard rails for a garbage cadence echoed by a broken panel (config.ini documents 30–300). */
    const val MIN_PERIOD_SECONDS = 30
    const val MAX_PERIOD_SECONDS = 3600

    data class Timing(val periodSeconds: Int, val timeslotSeconds: Int)

    /** Clamped cadence from whatever the profile advertised (null = field absent/unparseable). */
    fun timingFrom(watchdogTimeoutSeconds: Long?, timeslotSeconds: Double? = null): Timing {
        val period = (watchdogTimeoutSeconds ?: DEFAULT_PERIOD_SECONDS.toLong())
            .coerceIn(MIN_PERIOD_SECONDS.toLong(), MAX_PERIOD_SECONDS.toLong())
            .toInt()
        val slot = (timeslotSeconds ?: 0.0)
            .let { if (it.isNaN()) 0 else it.toInt() }
            .coerceIn(0, period - 1)
        return Timing(periodSeconds = period, timeslotSeconds = slot)
    }

    /** One ping's query params. [init] true only for the activation ping. */
    fun pingParams(init: Boolean): Map<String, String> = mapOf(
        "type" to "watchdog",
        "action" to "get_events",
        // The portal's own client reports 0 while paused; we don't model play-state here and the
        // server only records the value (users.now_playing_type), so a constant 0 is faithful.
        "cur_play_type" to "0",
        "event_active_id" to "0",
        "init" to if (init) "1" else "0",
    )

    /** The init ping fires at activation; the first PERIODIC tick lands timeslot + period later. */
    fun initialPeriodicDelayMs(timing: Timing): Long =
        (timing.timeslotSeconds + timing.periodSeconds) * 1000L

    fun periodMs(timing: Timing): Long = timing.periodSeconds * 1000L
}

package com.nuvio.tv.core.iptv.stalker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cadence + wire shape of the `get_events` keep-alive, verified against the portal's own client
 * (`c/watchdog.js` + `c/xpcom.common.js`: run(watchdog_timeout, timeslot) from the profile) and
 * server (`server/lib/watchdog.class.php`: reads `init` and `cur_play_type` from the request;
 * `server/config.ini`: `watchdog_timeout = 120`). Twin of NuvioMobile's StalkerWatchdogPolicyTest.
 */
class StalkerWatchdogPolicyTest {

    @Test
    fun `default cadence is 120 seconds`() =
        assertEquals(
            StalkerWatchdogPolicy.Timing(periodSeconds = 120, timeslotSeconds = 0),
            StalkerWatchdogPolicy.timingFrom(watchdogTimeoutSeconds = null, timeslotSeconds = null)
        )

    @Test
    fun `the advertised cadence is used`() =
        assertEquals(300, StalkerWatchdogPolicy.timingFrom(300, null).periodSeconds)

    @Test
    fun `a garbage low cadence clamps to 30`() =
        assertEquals(30, StalkerWatchdogPolicy.timingFrom(5, null).periodSeconds)

    @Test
    fun `a garbage high cadence clamps to 3600`() =
        assertEquals(3600, StalkerWatchdogPolicy.timingFrom(86_400, null).periodSeconds)

    /** The portal sends timeslot as a float (`parseFloat` in xpcom.common.js) — whole seconds win. */
    @Test
    fun `timeslot floors to whole seconds`() =
        assertEquals(45, StalkerWatchdogPolicy.timingFrom(120, 45.9).timeslotSeconds)

    @Test
    fun `timeslot clamps below one period`() =
        assertEquals(119, StalkerWatchdogPolicy.timingFrom(120, 500.0).timeslotSeconds)

    @Test
    fun `a negative timeslot clamps to zero`() =
        assertEquals(0, StalkerWatchdogPolicy.timingFrom(120, -3.0).timeslotSeconds)

    @Test
    fun `the init ping carries init 1`() {
        val p = StalkerWatchdogPolicy.pingParams(init = true)
        assertEquals("watchdog", p["type"])
        assertEquals("get_events", p["action"])
        assertEquals("1", p["init"])
        assertEquals("0", p["cur_play_type"])
        assertEquals("0", p["event_active_id"])
    }

    /** `init` is PRESENT on every ping — its presence gates the server's MAC-clone logging. */
    @Test
    fun `periodic pings carry init 0`() =
        assertEquals("0", StalkerWatchdogPolicy.pingParams(init = false)["init"])

    /** The init ping fires at activation; the first periodic tick lands timeslot + period later. */
    @Test
    fun `first periodic tick is one period plus the timeslot after activation`() =
        assertEquals(
            150_000L,
            StalkerWatchdogPolicy.initialPeriodicDelayMs(StalkerWatchdogPolicy.Timing(120, 30))
        )

    @Test
    fun `period converts to millis`() =
        assertEquals(120_000L, StalkerWatchdogPolicy.periodMs(StalkerWatchdogPolicy.Timing(120, 0)))
}

package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerClockOffsetTest {

    /** 2024-03-09 16:00:00 UTC — the same instant every pair below describes. */
    private val ts = 1_710_000_000L

    @Test
    fun `a panel an hour ahead yields plus one hour`() {
        assertEquals(3_600_000L, ServerClockOffset.offsetMs("2024-03-09 17:00:00", ts))
    }

    @Test
    fun `a panel behind utc yields a negative offset`() {
        // New York in March: UTC-5.
        assertEquals(-18_000_000L, ServerClockOffset.offsetMs("2024-03-09 11:00:00", ts))
    }

    @Test
    fun `a half-hour zone is preserved`() {
        // India: UTC+5:30.
        assertEquals(19_800_000L, ServerClockOffset.offsetMs("2024-03-09 21:30:00", ts))
    }

    @Test
    fun `an aligned clock yields zero`() {
        assertEquals(0L, ServerClockOffset.offsetMs("2024-03-09 16:00:00", ts))
    }

    /**
     * Some panels render time_now and timestamp_now a beat apart, so the raw difference carries
     * seconds of noise. Real zone offsets are whole minutes; the noise rounds away.
     */
    @Test
    fun `sub-minute clock jitter rounds away`() {
        assertEquals(3_600_000L, ServerClockOffset.offsetMs("2024-03-09 17:00:22", ts))
        assertEquals(3_600_000L, ServerClockOffset.offsetMs("2024-03-09 16:59:41", ts))
        assertEquals(-18_000_000L, ServerClockOffset.offsetMs("2024-03-09 10:59:45", ts))
    }

    /** XUI sends seconds, but a minute-precision time_now still decides the offset. */
    @Test
    fun `seconds are optional in time_now`() {
        assertEquals(3_600_000L, ServerClockOffset.offsetMs("2024-03-09 17:00", ts))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(0L, ServerClockOffset.offsetMs("  2024-03-09 16:00:00  ", ts))
    }

    /** The whole point of the derivation is surviving junk — junk in, null out, never a throw. */
    @Test
    fun `junk time_now yields no offset`() {
        assertNull(ServerClockOffset.offsetMs(null, ts))
        assertNull(ServerClockOffset.offsetMs("", ts))
        assertNull(ServerClockOffset.offsetMs("   ", ts))
        assertNull(ServerClockOffset.offsetMs("soon", ts))
        assertNull(ServerClockOffset.offsetMs("2024-13-09 16:00:00", ts))
        assertNull(ServerClockOffset.offsetMs("2024-03-09 25:00:00", ts))
        assertNull(ServerClockOffset.offsetMs("2024-03-40 16:00:00", ts))
        assertNull(ServerClockOffset.offsetMs("2024-03-09", ts))
        assertNull(ServerClockOffset.offsetMs("16:00:00", ts))
    }

    @Test
    fun `an implausible timestamp yields no offset`() {
        assertNull(ServerClockOffset.offsetMs("2024-03-09 16:00:00", 0L))
        assertNull(ServerClockOffset.offsetMs("2024-03-09 16:00:00", -5L))
    }
}

package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UX179 on TV — what "Refresh now" does per source, the monotonic per-playlist cooldown, when a rebuild landed. */
class IptvManualRefreshPolicyTest {
    @Test
    fun `xtream and m3u url refresh the catalog, stalker reloads on open, a file with no copy cannot`() {
        assertEquals("xtream", IptvManualRefreshPolicy.Mode.REFRESH_CATALOG, IptvManualRefreshPolicy.modeFor(isStalker = false, isFile = false, hasLocalCopy = false))
        assertEquals("stalker", IptvManualRefreshPolicy.Mode.RELOAD_ON_OPEN, IptvManualRefreshPolicy.modeFor(isStalker = true, isFile = false, hasLocalCopy = false))
        assertEquals("file, no copy", IptvManualRefreshPolicy.Mode.NEEDS_LOCAL_FILE, IptvManualRefreshPolicy.modeFor(isStalker = false, isFile = true, hasLocalCopy = false))
        assertEquals("file with copy", IptvManualRefreshPolicy.Mode.REFRESH_CATALOG, IptvManualRefreshPolicy.modeFor(isStalker = false, isFile = true, hasLocalCopy = true))
    }

    @Test
    fun `a second refresh inside the minute waits, then may start - measured on a monotonic clock`() {
        val c = IptvManualRefreshPolicy.COOLDOWN_MS
        assertEquals("none yet", 0L, IptvManualRefreshPolicy.cooldownRemainingMs(null))
        assertEquals("just started", c, IptvManualRefreshPolicy.cooldownRemainingMs(0L))
        assertEquals("10 s in", c - 10_000L, IptvManualRefreshPolicy.cooldownRemainingMs(10_000L))
        assertEquals("a minute later", 0L, IptvManualRefreshPolicy.cooldownRemainingMs(c))
        assertEquals("negative elapsed is just now", c, IptvManualRefreshPolicy.cooldownRemainingMs(-9_999L))
    }

    @Test
    fun `a rebuild counts only when every enabled kind's build time changed, whatever the clock did`() {
        assertEquals("all changed", IptvManualRefreshPolicy.Landed.ALL, IptvManualRefreshPolicy.landed(listOf(40L, 50L), listOf(150L, 160L)))
        assertEquals("first ever build", IptvManualRefreshPolicy.Landed.ALL, IptvManualRefreshPolicy.landed(listOf(null, null), listOf(150L, 160L)))
        assertEquals("none changed", IptvManualRefreshPolicy.Landed.NONE, IptvManualRefreshPolicy.landed(listOf(40L, null), listOf(40L, null)))
        assertEquals("one changed", IptvManualRefreshPolicy.Landed.SOME, IptvManualRefreshPolicy.landed(listOf(40L, 50L), listOf(150L, 50L)))
        assertEquals("clock moved backwards", IptvManualRefreshPolicy.Landed.ALL, IptvManualRefreshPolicy.landed(listOf(900L), listOf(100L)))
        assertEquals("nothing enabled", IptvManualRefreshPolicy.Landed.NONE, IptvManualRefreshPolicy.landed(emptyList(), emptyList()))
    }

    @Test
    fun `the updated line says just now, minutes, hours or days - and nothing when unknown`() {
        val now = 10_000_000_000L
        assertNull("null", IptvManualRefreshPolicy.age(null, now))
        assertNull("zero", IptvManualRefreshPolicy.age(0L, now))
        assertEquals("just now", IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.JUST_NOW, 0), IptvManualRefreshPolicy.age(now - 20_000L, now))
        assertEquals("minutes", IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.MINUTES, 5), IptvManualRefreshPolicy.age(now - (5 * 60_000L + 1_000L), now))
        assertEquals("hours", IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.HOURS, 3), IptvManualRefreshPolicy.age(now - (3 * 3_600_000L + 600_000L), now))
        assertEquals("days", IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.DAYS, 2), IptvManualRefreshPolicy.age(now - (2 * 86_400_000L + 3_600_000L), now))
        assertEquals("future stamp is just now", IptvManualRefreshPolicy.Age(IptvManualRefreshPolicy.AgeUnit.JUST_NOW, 0), IptvManualRefreshPolicy.age(now + 5_000L, now))
    }
}

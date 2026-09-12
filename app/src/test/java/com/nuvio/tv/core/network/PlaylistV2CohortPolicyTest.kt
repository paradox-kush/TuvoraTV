package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B24 — TV twin of the canary cohort resolver test. */
class PlaylistV2CohortPolicyTest {

    private fun resolve(mode: String?, pct: Int? = null, platforms: List<String>? = null,
                        user: String? = "user-abc", platform: String = "tv") =
        PlaylistV2CohortPolicy.resolveMode(mode, pct, platforms, user, platform)

    @Test fun passThrough() {
        assertEquals("disabled", resolve("disabled"))
        assertEquals("debug_only", resolve("debug_only"))
        assertEquals("debug_only", resolve(null))
    }

    @Test fun enabledFullByDefault() { assertEquals("enabled", resolve("enabled")) }

    @Test fun percentBounds() {
        assertEquals("disabled", resolve("enabled", pct = 0))
        assertEquals("enabled", resolve("enabled", pct = 100))
    }

    @Test fun platformGate() {
        assertEquals("enabled", resolve("enabled", platforms = listOf("tv")))
        assertEquals("disabled", resolve("enabled", platforms = listOf("android"), platform = "tv"))
    }

    @Test fun missingAccountBucketsOut() {
        assertEquals("disabled", resolve("enabled", pct = 50, user = null))
        assertEquals("enabled", resolve("enabled", pct = 100, user = null))
    }

    @Test fun monotonicAdd() {
        for (i in 0 until 200) {
            val u = "acct-$i"; var wasIn = false
            for (pct in 0..100) {
                val inNow = PlaylistV2CohortPolicy.resolveMode("enabled", pct, null, u, "tv") == "enabled"
                if (wasIn) assertTrue("acct $u dropped at $pct", inNow)
                if (inNow) wasIn = true
            }
        }
    }
}

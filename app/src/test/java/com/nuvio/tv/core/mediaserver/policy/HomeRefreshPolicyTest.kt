package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.policy.HomeRefreshPolicy.Decision
import com.nuvio.tv.core.mediaserver.policy.HomeRefreshPolicy.ServerState
import com.nuvio.tv.core.mediaserver.policy.HomeRefreshPolicy.SkipReason
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * The CLAUDE.md "recurring network work" rule for media-server Home rows: delta (only enabled rows; none
 * enabled = no request), lifecycle-bound (no timer in the policy - it is only asked when Home is RESUMED),
 * cheap (TTL, backoff, minimal fields).
 */
class HomeRefreshPolicyTest {
    private val all = setOf(MediaServerHomeRow.CONTINUE_WATCHING, MediaServerHomeRow.NEXT_UP, MediaServerHomeRow.RECENTLY_ADDED)

    @Test
    fun noEnabledRowsMeansNoRequestEver() {
        assertEquals(Decision.Skip(SkipReason.NO_ROWS_ENABLED), HomeRefreshPolicy.decide(emptySet(), ServerState(), 0, force = false))
        assertEquals("not even a forced pull-to-refresh", Decision.Skip(SkipReason.NO_ROWS_ENABLED), HomeRefreshPolicy.decide(emptySet(), ServerState(), 0, force = true))
    }

    @Test
    fun theFirstRefreshFetchesExactlyTheEnabledRows() {
        val one = setOf(MediaServerHomeRow.NEXT_UP)
        assertEquals("a delta, not the world", Decision.Fetch(one), HomeRefreshPolicy.decide(one, ServerState(), 1_000, false))
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, ServerState(), 1_000, false))
    }

    @Test
    fun aFreshRowSetIsNotRefetched() {
        val state = HomeRefreshPolicy.afterSuccess(ServerState(), nowMs = 10_000)
        assertEquals(Decision.Skip(SkipReason.FRESH), HomeRefreshPolicy.decide(all, state, 10_000 + HomeRefreshPolicy.ROW_TTL_MS - 1, false))
        assertEquals("aged out", Decision.Fetch(all), HomeRefreshPolicy.decide(all, state, 10_000 + HomeRefreshPolicy.ROW_TTL_MS, false))
    }

    @Test
    fun invalidationAndForceBypassTheTtl() {
        val state = HomeRefreshPolicy.afterSuccess(ServerState(), 10_000)
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, state, 11_000, force = true))
        val invalidated = HomeRefreshPolicy.invalidate(state)
        assertEquals("a websocket UserDataChanged / own playback report", Decision.Fetch(all), HomeRefreshPolicy.decide(all, invalidated, 11_000, false))
        assertFalse(HomeRefreshPolicy.afterSuccess(invalidated, 12_000).invalidated)
    }

    @Test
    fun aServiceUnavailableHonoursRetryAfter() {
        val s = HomeRefreshPolicy.afterFailure(ServerState(), nowMs = 1_000, httpStatus = 503, retryAfterSeconds = 45)
        assertEquals(46_000L, s.blockedUntilMs)
        assertEquals(Decision.Skip(SkipReason.BACKING_OFF), HomeRefreshPolicy.decide(all, s, 20_000, false))
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, s, 46_000, false))
        // an absurd Retry-After is capped
        assertEquals(1_000L + 5 * 60_000L, HomeRefreshPolicy.afterFailure(ServerState(), 1_000, 503, 86_400).blockedUntilMs)
    }

    @Test
    fun otherFailuresBackOffExponentiallyUpToACap() {
        var s = ServerState()
        val delays = mutableListOf<Long>()
        repeat(7) {
            s = HomeRefreshPolicy.afterFailure(s, nowMs = 0, httpStatus = null, retryAfterSeconds = null)
            delays += s.blockedUntilMs!!
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), delays)
    }

    @Test
    fun aServerIsOfflineAfterTwoFailuresAndForgivenOnSuccess() {
        var s = HomeRefreshPolicy.afterFailure(ServerState(), 0, null, null)
        assertFalse(HomeRefreshPolicy.isOffline(s))
        s = HomeRefreshPolicy.afterFailure(s, 40_000, null, null)
        assertTrue(HomeRefreshPolicy.isOffline(s))
        s = HomeRefreshPolicy.afterSuccess(s, 100_000)
        assertFalse(HomeRefreshPolicy.isOffline(s))
        assertNull(s.blockedUntilMs)
    }

    @Test
    fun aForcedRefreshMayProbeAnOfflineServer() {
        val s = HomeRefreshPolicy.afterFailure(ServerState(), 0, null, null)
        assertEquals(Decision.Fetch(all), HomeRefreshPolicy.decide(all, s, 1_000, force = true))
    }

    @Test
    fun theRowQueryIsMinimal() {
        assertEquals(20, HomeRefreshPolicy.rowQuery.limit)
        assertFalse(HomeRefreshPolicy.rowQuery.enableTotalRecordCount)
        assertFalse("the heaviest field never rides a row", HomeRefreshPolicy.rowQuery.fields.contains("MediaSources"))
    }

    @Test
    fun aServerRowHidesWhatTuvoraContinueWatchingAlreadyShows() {
        assertEquals(listOf("ms:a", "ms:c"), HomeRefreshPolicy.dedupeAgainstContinueWatching(listOf("ms:a", "ms:b", "ms:c"), setOf("ms:b")))
    }

    @Test
    fun aLibraryRowFollowsTheSameTtlAndBackoffGate() {
        assertTrue("never fetched", HomeRefreshPolicy.shouldFetchList(ServerState(), 1_000, false))
        val fresh = HomeRefreshPolicy.afterSuccess(ServerState(), 1_000)
        assertFalse("inside the ttl: no request", HomeRefreshPolicy.shouldFetchList(fresh, 1_000 + 60_000, false))
        assertTrue("pull-to-refresh", HomeRefreshPolicy.shouldFetchList(fresh, 1_000 + 60_000, true))
        assertTrue("aged out", HomeRefreshPolicy.shouldFetchList(fresh, 1_000 + HomeRefreshPolicy.ROW_TTL_MS, false))
        assertTrue("invalidated by our own report", HomeRefreshPolicy.shouldFetchList(HomeRefreshPolicy.invalidate(fresh), 2_000, false))
        val failing = HomeRefreshPolicy.afterFailure(ServerState(), 5_000, httpStatus = null, retryAfterSeconds = null)
        assertFalse("backing off", HomeRefreshPolicy.shouldFetchList(failing, 5_000 + 10_000, false))
        assertTrue(HomeRefreshPolicy.shouldFetchList(failing, 5_000 + 31_000, false))
    }
}

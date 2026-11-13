package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.stalker.StalkerStreamIdentityPolicy.StreamIdentity
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.ProviderResolutionTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real Stalker provider answers its own create_link stream with HTTP 401 whenever the request
 * presents the MAG set-top-box User-Agent; any other UA plays. Other providers need that identity on
 * the stream, so it stays the default and the app learns, per playlist, which one works.
 */
class StalkerStreamIdentityPolicyTest {

    private val playlist = "http://portal.invalid|00:1A:79:00:00:01"
    private val other = "http://other.invalid|00:1A:79:00:00:02"
    private val channel = "stalker:42"

    private fun StalkerStreamIdentityPolicy.tune(
        id: String = playlist,
        key: String = channel,
        override: Boolean = false,
    ) = identityFor(id, key, ProviderResolutionTrigger.INITIAL, previousFailure = null, hasUserAgentOverride = override, onPortalHost = true)

    private fun StalkerStreamIdentityPolicy.remint(
        failure: FailureCode?,
        id: String = playlist,
        key: String = channel,
        override: Boolean = false,
        trigger: ProviderResolutionTrigger = ProviderResolutionTrigger.RECOVERY,
    ): Pair<Boolean, StreamIdentity> {
        val switches = switchesOnRemint(id, key, trigger, failure, override)
        return switches to identityFor(id, key, trigger, failure, override, onPortalHost = true)
    }

    @Test
    fun `a playlist starts with the portal identity, so providers that work today are unchanged`() {
        val policy = StalkerStreamIdentityPolicy()
        assertEquals("default", StreamIdentity.IDENTITY, policy.tune())
        assertEquals("nothing learned", StreamIdentity.IDENTITY, policy.remembered(playlist))
    }

    @Test
    fun `an auth rejection before the first frame re-mints at once with the neutral profile`() {
        for (code in listOf(FailureCode.AUTHORIZATION_REJECTED)) {
            val policy = StalkerStreamIdentityPolicy()
            policy.tune()
            val (switches, next) = policy.remint(code)
            assertTrue("$code: the re-mint switches, so it skips the single-use spacing", switches)
            assertEquals("$code: next attempt is neutral", StreamIdentity.NEUTRAL, next)
        }
    }

    @Test
    fun `the handoff re-mint switches too`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED, trigger = ProviderResolutionTrigger.HANDOFF)
        assertTrue("switch", switches)
        assertEquals("neutral", StreamIdentity.NEUTRAL, next)
    }

    @Test
    fun `neutral that plays is remembered for that playlist only`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.onPlaybackStarted(playlist, channel)

        assertEquals("learned", StreamIdentity.NEUTRAL, policy.remembered(playlist))
        assertEquals("next tune starts neutral", StreamIdentity.NEUTRAL, policy.tune(key = "stalker:43"))
        assertEquals("another playlist is unaffected", StreamIdentity.IDENTITY, policy.tune(id = other))
    }

    @Test
    fun `neutral not yet proven is not remembered`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertEquals("not proven", StreamIdentity.IDENTITY, policy.remembered(playlist))
    }

    /**
     * Review HIGH 1: a provider that NEEDS the MAG identity can refuse a first open for a passing reason
     * (the previous connection still held, an expired token). Neutral is then a one-shot trial: when it
     * is refused too, the rest of the ladder goes back to the tune's starting identity, spaced as before
     * this change, so the next re-mint can still recover.
     */
    @Test
    fun `a refused neutral trial goes back to the starting identity with normal spacing`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("going back is not an immediate switch: the re-mint keeps the spacing", switches)
        assertEquals("the rest of the ladder presents the identity again", StreamIdentity.IDENTITY, next)
        val (again, last) = policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("no second trial in the same tune", again)
        assertEquals("still the identity", StreamIdentity.IDENTITY, last)
        assertEquals("nothing learned", StreamIdentity.IDENTITY, policy.remembered(playlist))
    }

    @Test
    fun `a neutral trial that fails for any other reason before media also goes back`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        val (switches, next) = policy.remint(FailureCode.NETWORK_TIMEOUT)
        assertFalse("spaced", switches)
        assertEquals("back to the identity", StreamIdentity.IDENTITY, next)
    }

    @Test
    fun `a neutral that played stays for the rest of the tune`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.onPlaybackStarted(playlist, channel)
        assertEquals("a later drop re-mints neutral", StreamIdentity.NEUTRAL, policy.remint(FailureCode.NETWORK_TIMEOUT).second)
    }

    /** Review LOW: a guide preview of the same channel is another session and must not reset this tune. */
    @Test
    fun `another session tuning the same channel does not reset this session's tune`() {
        val policy = StalkerStreamIdentityPolicy()
        val fullscreen = 1L
        val preview = 2L
        policy.identityFor(playlist, channel, ProviderResolutionTrigger.INITIAL, null, false, true, sessionKey = fullscreen)
        policy.identityFor(playlist, channel, ProviderResolutionTrigger.RECOVERY, FailureCode.AUTHORIZATION_REJECTED, false, true, sessionKey = fullscreen)

        policy.identityFor(playlist, channel, ProviderResolutionTrigger.INITIAL, null, false, true, sessionKey = preview)

        assertFalse(
            "the fullscreen tune already spent its trial",
            policy.switchesOnRemint(playlist, channel, ProviderResolutionTrigger.RECOVERY, FailureCode.AUTHORIZATION_REJECTED, false, sessionKey = fullscreen),
        )
        assertEquals(
            "fullscreen goes back to the identity, spaced",
            StreamIdentity.IDENTITY,
            policy.identityFor(playlist, channel, ProviderResolutionTrigger.RECOVERY, FailureCode.AUTHORIZATION_REJECTED, false, true, sessionKey = fullscreen),
        )
    }

    @Test
    fun `a new tune of the same channel may try both profiles again`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("spent", policy.remint(FailureCode.AUTHORIZATION_REJECTED).first)

        assertEquals("the viewer's Retry starts over", StreamIdentity.IDENTITY, policy.tune())
        assertTrue("and may switch once more", policy.remint(FailureCode.AUTHORIZATION_REJECTED).first)
    }

    @Test
    fun `a user agent override is used as set and never switched`() {
        val policy = StalkerStreamIdentityPolicy()
        assertEquals("override keeps the identity profile", StreamIdentity.IDENTITY, policy.tune(override = true))
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED, override = true)
        assertFalse("no auto-switch", switches)
        assertEquals("unchanged", StreamIdentity.IDENTITY, next)
    }

    @Test
    fun `a learned neutral never overrides the user's own user agent`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.onPlaybackStarted(playlist, channel)
        assertEquals("override wins", StreamIdentity.IDENTITY, policy.tune(override = true))
    }

    @Test
    fun `non-auth failures do not switch`() {
        for (code in FailureCode.entries - FailureCode.AUTHORIZATION_REJECTED) {
            val policy = StalkerStreamIdentityPolicy()
            policy.tune()
            val (switches, next) = policy.remint(code)
            assertFalse("$code must not switch", switches)
            assertEquals("$code keeps identity", StreamIdentity.IDENTITY, next)
        }
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        assertFalse("no feedback", policy.remint(null).first)
    }

    @Test
    fun `an auth rejection after the identity already played is not an identity problem`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.onPlaybackStarted(playlist, channel)
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("first frame was shown under identity", switches)
        assertEquals("identity", StreamIdentity.IDENTITY, next)
    }

    @Test
    fun `a stream on a foreign host never had the identity, so nothing switches`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.identityFor(playlist, channel, ProviderResolutionTrigger.INITIAL, null, hasUserAgentOverride = false, onPortalHost = false)
        assertFalse(
            "foreign host",
            policy.switchesOnRemint(playlist, channel, ProviderResolutionTrigger.RECOVERY, FailureCode.AUTHORIZATION_REJECTED, false),
        )
    }

    @Test
    fun `a remembered neutral that is refused before its first frame falls back to the identity once`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.onPlaybackStarted(playlist, channel)

        assertEquals("starts neutral", StreamIdentity.NEUTRAL, policy.tune(key = "stalker:43"))
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED, key = "stalker:43")
        assertTrue("the provider changed: try the identity", switches)
        assertEquals("identity", StreamIdentity.IDENTITY, next)
        policy.onPlaybackStarted(playlist, "stalker:43")
        assertEquals("identity plays again: forget neutral", StreamIdentity.IDENTITY, policy.remembered(playlist))
    }

    @Test
    fun `a recovery for a channel never tuned starts from the playlist default`() {
        val policy = StalkerStreamIdentityPolicy()
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("no tune to switch", switches)
        assertEquals("default", StreamIdentity.IDENTITY, next)
    }
}

package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.stalker.StalkerStreamIdentityPolicy.StreamIdentity
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.ProviderResolutionTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Until 1.21.3 the TV never sent the MAG identity (UA, X-User-Agent, cookie, token, Referer, Origin)
 * to a Stalker stream, and providers played. 1.21.3 started sending it on the portal host and a real
 * provider answers that with 401. So the pre-1.21.3 request is the default again (NEUTRAL), and the
 * MAG identity is a one-shot fallback for a provider that refuses the plain request.
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
    fun `a playlist starts with the pre-1_21_3 neutral request`() {
        val policy = StalkerStreamIdentityPolicy()
        assertEquals("default", StreamIdentity.NEUTRAL, policy.tune())
        assertEquals("nothing learned", StreamIdentity.NEUTRAL, policy.remembered(playlist))
    }

    @Test
    fun `an auth rejection before the first frame re-mints at once with the MAG identity`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertTrue("the re-mint switches, so it skips the single-use spacing", switches)
        assertEquals("next attempt presents the identity", StreamIdentity.IDENTITY, next)
    }

    @Test
    fun `the handoff re-mint switches too`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED, trigger = ProviderResolutionTrigger.HANDOFF)
        assertTrue("switch", switches)
        assertEquals("identity", StreamIdentity.IDENTITY, next)
    }

    @Test
    fun `an identity that plays is remembered for that playlist only`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.onPlaybackStarted(playlist, channel)

        assertEquals("learned", StreamIdentity.IDENTITY, policy.remembered(playlist))
        assertEquals("next tune starts with the identity", StreamIdentity.IDENTITY, policy.tune(key = "stalker:43"))
        assertEquals("another playlist is unaffected", StreamIdentity.NEUTRAL, policy.tune(id = other))
    }

    @Test
    fun `an identity not yet proven is not remembered`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertEquals("not proven", StreamIdentity.NEUTRAL, policy.remembered(playlist))
    }

    /**
     * A provider that plays the plain request can still refuse one open for a passing reason (the
     * previous connection still held, an expired token). The identity is then a one-shot trial: when it
     * is refused too, the rest of the ladder goes back to the neutral request, spaced as before.
     */
    @Test
    fun `a refused identity trial goes back to the neutral request with normal spacing`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("going back is not an immediate switch: the re-mint keeps the spacing", switches)
        assertEquals("the rest of the ladder is neutral again", StreamIdentity.NEUTRAL, next)
        val (again, last) = policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("no second trial in the same tune", again)
        assertEquals("still neutral", StreamIdentity.NEUTRAL, last)
        assertEquals("nothing learned", StreamIdentity.NEUTRAL, policy.remembered(playlist))
    }

    @Test
    fun `an identity trial that fails for any other reason before media also goes back`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        val (switches, next) = policy.remint(FailureCode.NETWORK_TIMEOUT)
        assertFalse("spaced", switches)
        assertEquals("back to neutral", StreamIdentity.NEUTRAL, next)
    }

    @Test
    fun `an identity that played stays for the rest of the tune`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.onPlaybackStarted(playlist, channel)
        assertEquals("a later drop re-mints with the identity", StreamIdentity.IDENTITY, policy.remint(FailureCode.NETWORK_TIMEOUT).second)
    }

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
            "fullscreen goes back to neutral, spaced",
            StreamIdentity.NEUTRAL,
            policy.identityFor(playlist, channel, ProviderResolutionTrigger.RECOVERY, FailureCode.AUTHORIZATION_REJECTED, false, true, sessionKey = fullscreen),
        )
    }

    @Test
    fun `a new tune of the same channel may try the identity again`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("spent", policy.remint(FailureCode.AUTHORIZATION_REJECTED).first)

        assertEquals("the viewer's Retry starts over", StreamIdentity.NEUTRAL, policy.tune())
        assertTrue("and may try once more", policy.remint(FailureCode.AUTHORIZATION_REJECTED).first)
    }

    @Test
    fun `a user agent override is used as set and never switched`() {
        val policy = StalkerStreamIdentityPolicy()
        assertEquals("override: the plain request with the user's UA", StreamIdentity.NEUTRAL, policy.tune(override = true))
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED, override = true)
        assertFalse("no auto-switch", switches)
        assertEquals("unchanged", StreamIdentity.NEUTRAL, next)
    }

    @Test
    fun `a learned identity never overrides the user's own user agent`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.onPlaybackStarted(playlist, channel)
        assertEquals("override wins", StreamIdentity.NEUTRAL, policy.tune(override = true))
    }

    @Test
    fun `non-auth failures do not switch`() {
        for (code in FailureCode.entries - FailureCode.AUTHORIZATION_REJECTED) {
            val policy = StalkerStreamIdentityPolicy()
            policy.tune()
            val (switches, next) = policy.remint(code)
            assertFalse("$code must not switch", switches)
            assertEquals("$code keeps neutral", StreamIdentity.NEUTRAL, next)
        }
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        assertFalse("no feedback", policy.remint(null).first)
    }

    @Test
    fun `an auth rejection after neutral already played is not an identity problem`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.onPlaybackStarted(playlist, channel)
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("first frame was shown under neutral", switches)
        assertEquals("neutral", StreamIdentity.NEUTRAL, next)
    }

    @Test
    fun `a stream on a foreign host never gets the identity, so nothing switches`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.identityFor(playlist, channel, ProviderResolutionTrigger.INITIAL, null, hasUserAgentOverride = false, onPortalHost = false)
        assertFalse(
            "foreign host",
            policy.switchesOnRemint(playlist, channel, ProviderResolutionTrigger.RECOVERY, FailureCode.AUTHORIZATION_REJECTED, false),
        )
    }

    @Test
    fun `a remembered identity that is refused before its first frame falls back to neutral once`() {
        val policy = StalkerStreamIdentityPolicy()
        policy.tune()
        policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        policy.onPlaybackStarted(playlist, channel)

        assertEquals("starts with the identity", StreamIdentity.IDENTITY, policy.tune(key = "stalker:43"))
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED, key = "stalker:43")
        assertTrue("the provider changed: try the plain request", switches)
        assertEquals("neutral", StreamIdentity.NEUTRAL, next)
        policy.onPlaybackStarted(playlist, "stalker:43")
        assertEquals("neutral plays again: forget the identity", StreamIdentity.NEUTRAL, policy.remembered(playlist))
    }

    @Test
    fun `a recovery for a channel never tuned starts from the playlist default`() {
        val policy = StalkerStreamIdentityPolicy()
        val (switches, next) = policy.remint(FailureCode.AUTHORIZATION_REJECTED)
        assertFalse("no tune to switch", switches)
        assertEquals("default", StreamIdentity.NEUTRAL, next)
    }
}

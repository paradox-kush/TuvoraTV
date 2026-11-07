package com.nuvio.tv.core.sports.detail.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchResolverTest {
    private fun c(id: String, conf: Int = 0, score: Int = 10, name: String = id) = WatchCandidate(id, name, conf, score)
    private fun resolve(
        watching: String? = null, watchingName: String? = null, cands: List<WatchCandidate> = emptyList(),
        matching: Boolean = false, failed: Boolean = false, playlists: Boolean = true,
    ) = WatchResolver.resolve(watching, watchingName, cands, matching, failed, playlists)

    @Test fun `browsing with a match offers Play of the best match`() {
        val w = resolve(cands = listOf(c("b", conf = 1, score = 99), c("a", conf = 0, score = 5)))
        assertEquals("mode", WatchMode.BROWSING, w.mode)
        assertEquals("best confidence beats score", PrimaryAction.Play(c("a", 0, 5)), w.primary)
    }
    @Test fun `higher score wins within a confidence tier`() =
        assertEquals("score", PrimaryAction.Play(c("hi", 0, 50)), resolve(cands = listOf(c("lo", 0, 5), c("hi", 0, 50))).primary)
    @Test fun `watching a different channel offers Switch and names the current one`() {
        val w = resolve(watching = "cur", watchingName = "Sky Sports 1", cands = listOf(c("best")))
        assertEquals("mode", WatchMode.WATCHING, w.mode)
        assertEquals("current", "Sky Sports 1", w.currentChannelName)
        assertEquals("switch", PrimaryAction.Switch(c("best")), w.primary)
    }
    @Test fun `already on a channel that carries the game offers no Switch`() {
        val w = resolve(watching = "cur", watchingName = "X", cands = listOf(c("best"), c("cur", conf = 1)))
        assertEquals("none", PrimaryAction.None, w.primary)
        assertEquals("status", WatchStatus.ALREADY_ON_IT, w.status)
    }
    @Test fun `no playlists offers adding a provider`() {
        val w = resolve(playlists = false)
        assertEquals("add provider", PrimaryAction.AddProvider, w.primary)
        assertEquals("status", WatchStatus.NO_PROVIDER, w.status)
    }
    @Test fun `still matching shows finding and no action yet`() {
        val w = resolve(matching = true)
        assertEquals("finding", WatchStatus.FINDING, w.status)
        assertEquals("no action", PrimaryAction.None, w.primary)
    }
    @Test fun `a matching failure is not rendered as no channels`() {
        assertEquals("failed", WatchStatus.FAILED, resolve(failed = true).status)
    }
    @Test fun `no channel carries the game`() = assertEquals("none", WatchStatus.NO_CHANNELS, resolve().status)
    @Test fun `partial results already allow Play while matching continues`() {
        val w = resolve(cands = listOf(c("a")), matching = true)
        assertTrue("play", w.primary is PrimaryAction.Play)
        assertEquals("ready", WatchStatus.READY, w.status)
    }
    @Test fun `alternatives count excludes the primary`() = assertEquals("n", 2, resolve(cands = listOf(c("a"), c("b"), c("c"))).alternatives)

    @Test fun `nothing matched offers Find channels and hides the dead-end Channels button`() {
        val w = resolve()
        assertEquals("action", PrimaryAction.Rematch(failed = false), w.primary)
        assertEquals("channels hidden", false, w.showChannels)
    }
    @Test fun `a failed or timed out lookup offers Retry`() {
        val w = resolve(failed = true)
        assertEquals("retry", PrimaryAction.Rematch(failed = true), w.primary)
        assertEquals("channels hidden", false, w.showChannels)
    }
    @Test fun `channels is shown while finding and when there are matches`() {
        assertTrue("finding", resolve(matching = true).showChannels)
        assertTrue("ready", resolve(cands = listOf(c("a"))).showChannels)
        assertTrue("already on it", resolve(watching = "a", cands = listOf(c("a"))).showChannels)
        assertEquals("no provider hides it", false, resolve(playlists = false).showChannels)
    }
    @Test fun `deadline constant is about six seconds`() = assertEquals("ms", 6_000L, WatchResolver.LOOKUP_DEADLINE_MS)
}

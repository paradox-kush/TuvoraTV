package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.policy.MatchCache.Answer
import com.nuvio.tv.core.mediaserver.policy.MatchLookupPolicy.ItemKind
import org.junit.Test
import org.junit.Assert.assertEquals

class MatchCacheTest {
    private fun key(id: String = "tmdb.603", server: String = "jellyfin:m:u", kind: ItemKind = ItemKind.MOVIE) = MatchCache.Key(server, kind, id)

    @Test
    fun aPositiveAnswerIsServedUntilItsTtlThenForgotten() {
        val c = MatchCache(positiveTtlMs = 1_000, negativeTtlMs = 100)
        c.put(key(), listOf("a", "b"), nowMs = 0)
        assertEquals(Answer.Hit(listOf("a", "b")), c.get(key(), nowMs = 999))
        assertEquals("expired exactly at the ttl", Answer.Unknown, c.get(key(), nowMs = 1_000))
        assertEquals("an expired entry is dropped, not kept", 0, c.sizeForTest)
    }

    @Test
    fun aNotOnThisServerAnswerIsShortLivedBecauseTheOwnerMayAddTheTitle() {
        val c = MatchCache(positiveTtlMs = 1_000_000, negativeTtlMs = 100)
        c.put(key(), emptyList(), nowMs = 0)
        assertEquals(Answer.NotOnServer, c.get(key(), nowMs = 99))
        assertEquals(Answer.Unknown, c.get(key(), nowMs = 100))
    }

    @Test
    fun theAnswerIsPerServerAndPerKind() {
        val c = MatchCache()
        c.put(key(), listOf("a"), 0)
        assertEquals("another server knows nothing of it", Answer.Unknown, c.get(key(server = "emby:m:u"), 1))
        assertEquals("a movie and a series with the same id are different titles", Answer.Unknown, c.get(key(kind = ItemKind.SERIES), 1))
        assertEquals(Answer.Unknown, c.get(key(id = "tmdb.604"), 1))
    }

    @Test
    fun theCacheIsBoundedAndEvictsTheLeastRecentlyUsed() {
        val c = MatchCache(maxEntries = 3)
        c.put(key("1"), listOf("x"), 0); c.put(key("2"), listOf("x"), 0); c.put(key("3"), listOf("x"), 0)
        c.get(key("1"), 1) // 1 is now the freshest; 2 the stalest
        c.put(key("4"), listOf("x"), 2)
        assertEquals(3, c.sizeForTest)
        assertEquals(Answer.Unknown, c.get(key("2"), 3))
        assertEquals(Answer.Hit(listOf("x")), c.get(key("1"), 3))
    }

    @Test
    fun invalidateAndForgetServerDropEntries() {
        val c = MatchCache()
        c.put(key("1"), listOf("x"), 0); c.put(key("2", server = "other"), listOf("y"), 0)
        c.invalidate(key("1"))
        assertEquals(Answer.Unknown, c.get(key("1"), 1))
        c.forgetServer("other")
        assertEquals(0, c.sizeForTest)
    }

    @Test
    fun titlesAreCachedUnderTmdbWhenKnownElseImdb() {
        assertEquals("tmdb.603", MatchCache.externalId(MatchLookupPolicy.ExternalIds(tmdb = " 603 ", imdb = "tt1")))
        assertEquals("imdb.tt0133093", MatchCache.externalId(MatchLookupPolicy.ExternalIds(imdb = "TT0133093")))
        assertEquals(null, MatchCache.externalId(MatchLookupPolicy.ExternalIds()))
    }
}

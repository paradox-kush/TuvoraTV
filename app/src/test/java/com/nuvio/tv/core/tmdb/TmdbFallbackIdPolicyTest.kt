package com.nuvio.tv.core.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbFallbackIdPolicyTest {
    @Test
    fun `tmdb ids look up directly`() {
        assertEquals(TmdbFallbackId.Tmdb(603), TmdbFallbackIdPolicy.classify("tmdb:603"))
        assertEquals(TmdbFallbackId.Tmdb(1399), TmdbFallbackIdPolicy.classify("TMDB:1399:1:2"))
    }

    // Regression: a catalog-only add-on, MDBList/Trakt collection, Library or Continue Watching
    // hands the detail screen an IMDb id. With no meta add-on installed the fallback refused it
    // and the detail page failed with "no addon provides meta".
    @Test
    fun `imdb ids resolve through tmdb`() {
        assertEquals(TmdbFallbackId.Imdb("tt0133093"), TmdbFallbackIdPolicy.classify("tt0133093"))
        assertEquals(TmdbFallbackId.Imdb("tt0944947"), TmdbFallbackIdPolicy.classify(" tt0944947:1:1 "))
    }

    @Test
    fun `ids tmdb cannot map get no fallback`() {
        assertNull(TmdbFallbackIdPolicy.classify("kitsu:1"))
        assertNull(TmdbFallbackIdPolicy.classify("tmdb:abc"))
        assertNull(TmdbFallbackIdPolicy.classify("tmdb:0"))
        assertNull(TmdbFallbackIdPolicy.classify("tt"))
        assertNull(TmdbFallbackIdPolicy.classify("ttabc"))
        assertNull(TmdbFallbackIdPolicy.classify("xtream:acc:vod:12"))
        assertNull(TmdbFallbackIdPolicy.classify(""))
    }
}

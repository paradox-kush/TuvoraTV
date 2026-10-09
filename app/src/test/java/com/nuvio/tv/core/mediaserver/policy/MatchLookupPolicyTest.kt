package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDialect
import com.nuvio.tv.core.mediaserver.policy.MatchLookupPolicy.ExternalIds
import com.nuvio.tv.core.mediaserver.policy.MatchLookupPolicy.ItemKind
import com.nuvio.tv.core.mediaserver.policy.MatchLookupPolicy.Query
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class MatchLookupPolicyTest {
    private val ids = ExternalIds(tmdb = "603", imdb = "tt0133093")

    @Test
    fun embyFiltersByProviderIdOnTheServer() {
        val q = MatchLookupPolicy.query(MediaBrowserDialect.EMBY, ItemKind.MOVIE, ids, title = "The Matrix", year = 1999)
        assertEquals(Query.ByProviderId(listOf("tmdb.603", "imdb.tt0133093"), "Movie"), q)
    }

    @Test
    fun jellyfinHasNoProviderIdFilterSoItSearchesByTitleAndYear() {
        val q = MatchLookupPolicy.query(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, ids, title = " The Matrix ", year = 1999)
        assertEquals(Query.ByTitle("The Matrix", listOf(1998, 1999, 2000), "Movie"), q)
        assertEquals(Query.ByTitle("Severance", emptyList(), "Series"), MatchLookupPolicy.query(MediaBrowserDialect.JELLYFIN, ItemKind.SERIES, ExternalIds(tmdb = "95396"), "Severance", null))
    }

    @Test
    fun noIdsOrNoTitleMeansNoLookup() {
        assertNull(MatchLookupPolicy.query(MediaBrowserDialect.EMBY, ItemKind.MOVIE, ExternalIds(), "x", 2000))
        assertNull("Jellyfin needs a title to search", MatchLookupPolicy.query(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, ids, null, 1999))
        assertNull(MatchLookupPolicy.query(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, ids, "  ", 1999))
    }

    private fun item(id: String, vararg providers: Pair<String, String?>) = ItemDto(id = id, providerIds = providers.toMap())

    @Test
    fun aTitleSearchCandidateMustAgreeOnAnExternalId() {
        val candidates = listOf(
            item("1", "Tmdb" to "603", "Imdb" to "tt0133093"),
            item("2", "Tmdb" to "604"),                       // a sequel with a similar title
            item("3", "tmdb" to "603"),                       // key casing differs per server version
            item("4"),                                        // no provider ids at all
            item("5", "Imdb" to "TT0133093"),                 // id casing
        )
        assertEquals(listOf("1", "3", "5"), MatchLookupPolicy.verify(candidates, ids).map { it.id })
    }

    @Test
    fun blankIdsNeverMatchBlankProviderIds() {
        assertEquals(emptyList<Any>(), MatchLookupPolicy.verify(listOf(item("1", "Tmdb" to "")), ExternalIds(tmdb = "")))
        assertEquals(emptyList<Any>(), MatchLookupPolicy.verify(listOf(item("1", "Tmdb" to null)), ExternalIds(tmdb = "603")))
    }

    private val facts = MatchLookupPolicy.TitleFacts(
        ids = ids, primary = "The Matrix", original = "The Matrix", alternatives = listOf("Matrix", "Matrix 1"), year = 1999,
    )

    @Test
    fun embyAsksOnceByIdWhateverTheTitles() {
        val qs = MatchLookupPolicy.queries(MediaBrowserDialect.EMBY, ItemKind.MOVIE, facts)
        assertEquals(listOf<Query>(Query.ByProviderId(listOf("tmdb.603", "imdb.tt0133093"), "Movie")), qs)
    }

    @Test
    fun jellyfinSearchesEachDistinctTitleOncePrimaryFirstAndCapsTheCount() {
        val qs = MatchLookupPolicy.queries(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, facts)
        // primary == original (case-insensitive duplicate) is asked once; then ONE alternative; never more than the cap
        assertEquals(listOf("The Matrix", "Matrix"), qs.map { (it as Query.ByTitle).searchTerm })
        assertEquals(listOf(1998, 1999, 2000), (qs.first() as Query.ByTitle).years)
        val many = facts.copy(primary = "A", original = "B", alternatives = listOf("C", "D", "E"))
        assertEquals(listOf("A", "B", "C"), MatchLookupPolicy.queries(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, many).map { (it as Query.ByTitle).searchTerm })
        assertEquals(MatchLookupPolicy.MAX_TITLE_QUERIES, 3)
    }

    @Test
    fun noQueriesWithoutIdsOrWithoutAnyTitleOnJellyfin() {
        assertEquals(emptyList<Any>(), MatchLookupPolicy.queries(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, facts.copy(ids = ExternalIds())))
        assertEquals(emptyList<Any>(), MatchLookupPolicy.queries(MediaBrowserDialect.JELLYFIN, ItemKind.MOVIE, facts.copy(primary = null, original = " ", alternatives = emptyList())))
        // Emby does not need a title at all
        assertEquals(1, MatchLookupPolicy.queries(MediaBrowserDialect.EMBY, ItemKind.MOVIE, facts.copy(primary = null, original = null)).size)
    }
}

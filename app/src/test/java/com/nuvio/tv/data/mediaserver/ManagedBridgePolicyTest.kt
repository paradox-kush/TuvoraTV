package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.core.tracking.TrackingExternalIds
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ManagedBridgePolicyTest {
    private fun preview(connection: String, item: String) = MetaPreview(
        id = ServerItemRef(connection, item).encode(), type = ContentType.MOVIE, name = "Same title",
        poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList())

    @Test fun exchangeAddressIsBoundToTheExactManagedOriginAndIdentity() {
        val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        assertTrue(ManagedBridgePolicy.validAddress(id, "https://addon.thekush.dev/jellyfin/$id"))
        for (address in listOf("https://attacker.example/jellyfin/$id", "http://addon.thekush.dev/jellyfin/$id", "https://addon.thekush.dev/jellyfin/$id?redirect=1", "https://addon.thekush.dev/jellyfin/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
            assertFalse(ManagedBridgePolicy.validAddress(id, address))
    }
    @Test fun matchingTitleAloneCannotSelectTheWrongMovie() {
        val titles = listOf(ServerTitle(preview("bridge", "wrong"), TrackingExternalIds(imdb = "tt2222222")))
        assertTrue(managedItemIds("bridge", titles, TrackingExternalIds(imdb = "tt1111111")).isEmpty())
    }
    @Test fun conflictingIdsAndAnotherConnectionsResultsAreRejected() {
        fun title(connection: String, item: String, tmdb: Long) = ServerTitle(
            preview(connection, item), TrackingExternalIds(imdb = "tt1111111", tmdb = tmdb))
        val titles = listOf(title("bridge", "correct", 7), title("bridge", "conflict", 9), title("other", "foreign", 7))
        assertEquals(listOf("correct"), managedItemIds("bridge", titles, TrackingExternalIds(imdb = "tt1111111", tmdb = 7)))
    }
}

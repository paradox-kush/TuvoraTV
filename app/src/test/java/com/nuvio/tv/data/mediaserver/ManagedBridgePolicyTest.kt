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

    private fun status(state: String, error: String? = null) = kotlinx.serialization.json.Json.parseToJsonElement(
        """{"id":"x","state":"$state","result":{${if (error != null) "\"error\":\"$error\"" else ""}}}""") as kotlinx.serialization.json.JsonObject

    @Test fun aRebuildingOrFailedBridgeNeverDeletesTheUsersConnection() {
        assertEquals(ManagedBridgeDecision.Ready, ManagedBridgePolicy.decide(status("ready")))
        assertEquals(ManagedBridgeDecision.Preparing, ManagedBridgePolicy.decide(status("pending")))
        assertEquals(ManagedBridgeDecision.Preparing, ManagedBridgePolicy.decide(status("working")))
        assertEquals(ManagedBridgeDecision.Failed("provisioning_timeout"), ManagedBridgePolicy.decide(status("failed", "provisioning_timeout")))
        assertEquals(ManagedBridgeDecision.Failed(null), ManagedBridgePolicy.decide(status("failed")))
    }

    @Test fun onlyAMissingOrDisconnectedConnectionIsForgotten() {
        assertEquals(ManagedBridgeDecision.Forget, ManagedBridgePolicy.decide(null))
        assertEquals(ManagedBridgeDecision.Forget, ManagedBridgePolicy.decide(status("disconnected")))
    }

    @Test fun exchangeRefusalsAreExplainedNotSwallowed() {
        assertEquals(ManagedBridgeNotice.Preparing, ManagedBridgePolicy.exchangeFailure(409, """{"error":"connection_reprovisioning"}"""))
        assertEquals(ManagedBridgeNotice.Preparing, ManagedBridgePolicy.exchangeFailure(410, ""))
        assertEquals(ManagedBridgeNotice.Failed("addon_selection_changed"), ManagedBridgePolicy.exchangeFailure(409, """{"error":"addon_selection_changed"}"""))
        assertEquals(ManagedBridgeNotice.Failed("http_503"), ManagedBridgePolicy.exchangeFailure(503, "<html>"))
    }
}

package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.*
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.core.tracking.TrackingExternalIds
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/** Wire projections captured from the real direct-only Remux HTTP regression.
 * No hand-invented library kinds, provider IDs or episode/source fields. */
class RemuxLibraryContractTest {
    private val viewsWire = """{"Items":[{"Id":"f47ac10b58cc4372a5670e02b2c3d479","Name":"Collections","Type":"CollectionFolder","CollectionType":"boxsets","LocationType":"FileSystem","ProviderIds":{}},{"Id":"a1b2c3d4000040008000000000000001","Name":"Movies","Type":"CollectionFolder","CollectionType":"movies","LocationType":"FileSystem","ProviderIds":{}},{"Id":"a1b2c3d4000040008000000000000002","Name":"Shows","Type":"CollectionFolder","CollectionType":"tvshows","LocationType":"FileSystem","ProviderIds":{}}]}"""
    private val movieWire = """{"Items":[{"Id":"7d4496e92ffe48169944d40d1af07abb","Name":"Synthetic POC Movie","MediaSources":[{"Id":"7d4496e92ffe48169944d40d1af07abb","MediaStreams":[],"Name":"Synthetic POC Movie","Path":"/remux/7d4496e9-2ffe-4816-9944-d40d1af07abb","Protocol":"File","RequiredHttpHeaders":{},"SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true,"Type":"Default"},{"Id":"283ca0d417d65746880a200765f26163","MediaStreams":[],"Name":"Synthetic POC Movie (2)","Protocol":"File","SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true,"Type":"Default"}],"Path":"/remux/7d4496e9-2ffe-4816-9944-d40d1af07abb.strm","Type":"Movie","LocationType":"FileSystem","ProviderIds":{"Imdb":"tt990000001"}}]}"""
    private val seriesWire = """{"Items":[{"Id":"b4bc0069868748eda68e36447d85ea67","Name":"Synthetic POC Series","Type":"Series","LocationType":"FileSystem","ProviderIds":{"Imdb":"tt990000002"}}]}"""
    private val episodesWire = """{"Items":[{"Id":"6d714c8fe8a55a66a47a4db01738205a","Name":"Synthetic episode","PremiereDate":"2025-01-01T00:00:00Z","MediaSources":[{"Id":"6d714c8fe8a55a66a47a4db01738205a","MediaStreams":[],"Name":"Synthetic episode","Path":"/remux/6d714c8f-e8a5-5a66-a47a-4db01738205a","Protocol":"File","RequiredHttpHeaders":{},"SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true,"Type":"Default"},{"Id":"ec49b17f87985ea9a184bfbd9525a0f0","MediaStreams":[],"Name":"Synthetic episode (2)","Protocol":"File","SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true,"Type":"Default"}],"Path":"/remux/6d714c8f-e8a5-5a66-a47a-4db01738205a.strm","IndexNumber":1,"ParentIndexNumber":1,"Type":"Episode","LocationType":"FileSystem","ProviderIds":{"Imdb":"tt990000002"}}]}"""
    private val episodePlaybackWire = """{"MediaSources":[{"Container":"mp4","Id":"6d714c8fe8a55a66a47a4db01738205a","MediaStreams":[{"Type":"Video"}],"Name":"Private tenant a","Path":"https://tenant-a.example/private-a.mp4","Protocol":"Http","RequiredHttpHeaders":{},"SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":false,"Type":"Default"}]}"""
    private val episodeItemWire = """{"Id":"6d714c8fe8a55a66a47a4db01738205a","Name":"Synthetic episode","PremiereDate":"2025-01-01T00:00:00Z","MediaSources":[{"Id":"6d714c8fe8a55a66a47a4db01738205a","MediaStreams":[],"Name":"Synthetic episode","Path":"/remux/6d714c8f-e8a5-5a66-a47a-4db01738205a","Protocol":"File","RequiredHttpHeaders":{},"SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true,"Type":"Default"},{"Id":"ec49b17f87985ea9a184bfbd9525a0f0","MediaStreams":[],"Name":"Synthetic episode (2)","Protocol":"File","SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true,"Type":"Default"}],"Path":"/remux/6d714c8f-e8a5-5a66-a47a-4db01738205a.strm","IndexNumber":1,"ParentIndexNumber":1,"Type":"Episode","LocationType":"FileSystem","ProviderIds":{"Imdb":"tt990000002"}}"""

    @Test
    fun discoversLibrariesAndResolvesMovieAndEpisode() = runBlocking {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/UserViews") -> viewsWire
                request.url.encodedPath.endsWith("/System/Info/Public") -> """{"Version":"10.11.0","ProductName":"Remux"}"""
                request.url.encodedPath.endsWith("/Episodes") -> episodesWire
                request.url.encodedPath.endsWith("/PlaybackInfo") -> episodePlaybackWire
                request.url.encodedPath.endsWith("/Items") -> if (request.url.queryParameter("includeItemTypes") == "Movie") movieWire else seriesWire
                else -> episodeItemWire
            }
        }
        val provider = JellyfinProvider(http.client, testIdentity)
        val base = ServerConnection("wire", "jellyfin", "Remux", "https://fixture.invalid", "s", "u", "viewer", "k")
        val initial = ServerSession(base, "synthetic")
        val libraries = provider.libraries(initial)
        assertEquals(setOf(ServerMediaKind.COLLECTION, ServerMediaKind.MOVIE, ServerMediaKind.SERIES), libraries.map { it.kind }.toSet())
        val connection = base.copy(libraries = libraries)
        val session = ServerSession(connection, "synthetic")
        val movies = provider.search(session, ServerMediaKind.MOVIE, connection.selectedLibraries(ServerMediaKind.MOVIE), "Synthetic POC", 25)
        assertEquals(1, managedItemIds(connection.id, movies, TrackingExternalIds(imdb = "tt990000001")).size)
        val shows = provider.search(session, ServerMediaKind.SERIES, connection.selectedLibraries(ServerMediaKind.SERIES), "Synthetic POC", 25)
        val showId = managedItemIds(connection.id, shows, TrackingExternalIds(imdb = "tt990000002")).single()
        val episode = provider.findEpisode(session, showId, 1, 1)!!
        val candidate = provider.candidates(session, episode.itemId).first()
        val playback = provider.preparePlayback(session, ServerPlaybackRequest(candidate.target, ServerPlayerCapabilities()))
        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        assertTrue(playback.url.startsWith("https://fixture.invalid/Videos/"))
        assertTrue(playback.url.contains("static=true"))
        val searches = http.requests.filter { it.url.encodedPath.endsWith("/Items") }
        assertEquals(libraries.single { it.kind == ServerMediaKind.MOVIE }.id, searches[0].url.queryParameter("parentId"))
        assertEquals(libraries.single { it.kind == ServerMediaKind.SERIES }.id, searches[1].url.queryParameter("parentId"))
        http.client.connectionPool.evictAll()
        http.client.dispatcher.executorService.shutdown()
    }
}

package com.nuvio.tv.core.contracts

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The plural source ports (Wave 3 / J4 seam): duplicate refused, composites speak with one voice, nothing registered = inert. */
class SourceRegistriesContractTest {

    @Before @After
    fun reset() {
        OwnSourcePolicy.resetForTest()
        StreamSourceRegistry.resetForTest()
        MetaSourceRegistry.resetForTest()
        SearchProviderRegistry.resetForTest()
        PlaybackSessionReporterRegistry.resetForTest()
        PlaybackResumeOfferRegistry.resetForTest()
        HomeSectionContributorRegistry.resetForTest()
        OwnSourceSubtitleRegistry.resetForTest()
    }

    @Test
    fun `a duplicate registration is refused in every registry`() {
        OwnSourcePolicy.registerContentIdPredicate("a") { true }
        expectRefused { OwnSourcePolicy.registerContentIdPredicate("a") { false } }
        OwnSourcePolicy.registerProviderIdPredicate("a") { true }
        expectRefused { OwnSourcePolicy.registerProviderIdPredicate("a") { false } }
        OwnSourcePolicy.registerSubtitleScopedPredicate("a") { true }
        expectRefused { OwnSourcePolicy.registerSubtitleScopedPredicate("a") { false } }
        OwnSourcePolicy.registerScrobbleExclusion("a") { true }
        expectRefused { OwnSourcePolicy.registerScrobbleExclusion("a") { false } }
        OwnSourcePolicy.registerTelemetryRewriter("a") { _, _ -> null }
        expectRefused { OwnSourcePolicy.registerTelemetryRewriter("a") { _, _ -> null } }
        StreamSourceRegistry.register("a", FakeStream("x:"))
        expectRefused { StreamSourceRegistry.register("a", FakeStream("y:")) }
        MetaSourceRegistry.register("a", FakeMeta("x:"))
        expectRefused { MetaSourceRegistry.register("a", FakeMeta("y:")) }
        SearchProviderRegistry.register("a", FakeSearch(true, emptyList(), flowOf(null)))
        expectRefused { SearchProviderRegistry.register("a", FakeSearch(true, emptyList(), flowOf(null))) }
        PlaybackSessionReporterRegistry.register(FakeReporter("a"))
        expectRefused { PlaybackSessionReporterRegistry.register(FakeReporter("a")) }
    }

    private fun expectRefused(block: () -> Unit) {
        try {
            block()
            fail("a duplicate registration must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `with nothing registered every source port is inert`() = runBlocking {
        assertFalse(OwnSourcePolicy.isOwnContentId("ms:x"))
        assertFalse(OwnSourcePolicy.isOwnProviderId("ms"))
        assertFalse(OwnSourcePolicy.isSubtitleScopedId("ms:x"))
        assertFalse(OwnSourcePolicy.isExcludedFromTrackingScrobble("ms:x"))
        assertEquals("ms:x", OwnSourcePolicy.telemetryId("ms:x", "salt"))
        val streams = StreamSourceAccess.current()
        assertFalse(streams.isHandledId("ms:x"))
        assertTrue(streams.directStreams("ms:x").isEmpty())
        assertTrue(streams.matchSourceGroups("movie").isEmpty())
        assertFalse(streams.isDeferredUrl("ms-deferred:a|b|c"))
        assertNull(streams.resolveDeferredUrl("ms-deferred:a|b|c", false))
        assertFalse(MetaSourceAccess.handlesId("ms:x"))
        val search = CompositeSearchProvider { SearchProviderRegistry.all }
        assertFalse(search.hasSearchableSources())
        assertTrue(search.search("q").isEmpty())
        assertNull(search.sourceSignature().first())
    }

    @Test
    fun `own-source predicates are the union over every registration`() {
        OwnSourcePolicy.registerContentIdPredicate("one") { it.startsWith("one:") }
        OwnSourcePolicy.registerContentIdPredicate("two") { it.startsWith("two:") }
        assertTrue(OwnSourcePolicy.isOwnContentId("one:1"))
        assertTrue(OwnSourcePolicy.isOwnContentId("two:1"))
        assertFalse(OwnSourcePolicy.isOwnContentId("three:1"))
        assertFalse(OwnSourcePolicy.isOwnContentId(null))
        OwnSourcePolicy.registerProviderIdPredicate("one") { it == "one" || it.startsWith("one-match:") }
        assertTrue(OwnSourcePolicy.isOwnProviderId("one-match:x"))
        assertFalse(OwnSourcePolicy.isOwnProviderId(""))
        assertFalse(OwnSourcePolicy.isOwnProviderId(null))
        // a content id and a provider id are different namespaces
        assertFalse(OwnSourcePolicy.isOwnProviderId("one:1"))
    }

    @Test
    fun `subtitle scope trims like the old policy and telemetry takes the first rewriter that claims the id`() {
        OwnSourcePolicy.registerSubtitleScopedPredicate("x") { it.startsWith("x:") }
        assertTrue(OwnSourcePolicy.isSubtitleScopedId("  x:1  "))
        assertFalse(OwnSourcePolicy.isSubtitleScopedId(null))
        OwnSourcePolicy.registerTelemetryRewriter("a") { id, salt -> if (id.startsWith("a:")) "hashed-$salt" else null }
        OwnSourcePolicy.registerTelemetryRewriter("b") { id, _ -> if (id.startsWith("b:")) "b-redacted" else null }
        assertEquals("hashed-s", OwnSourcePolicy.telemetryId("a:1", "s"))
        assertEquals("b-redacted", OwnSourcePolicy.telemetryId("b:1", "s"))
        assertEquals("tt1", OwnSourcePolicy.telemetryId("tt1", "s"))
    }

    @Test
    fun `stream sources are asked in the claiming provider only`() = runBlocking {
        StreamSourceRegistry.register("a", FakeStream("a:"))
        StreamSourceRegistry.register("b", FakeStream("b:"))
        val s = StreamSourceAccess.current()
        assertTrue(s.isHandledId("a:1"))
        assertTrue(s.isHandledId("b:1"))
        assertFalse(s.isHandledId("c:1"))
        assertEquals("a:1 from a:", s.directStreams("a:1").single().addonName)
        assertEquals("b:1 from b:", s.directStreams("b:1").single().addonName)
        assertTrue(s.isDeferredUrl("b:deferred"))
        assertEquals("minted b:deferred", s.resolveDeferredUrl("b:deferred", false))
        assertEquals(listOf("a:g", "b:g"), s.matchSourceGroups("movie").map { it.sourceId })
    }

    @Test
    fun `search composite is the lone provider unchanged and the union with several`() = runBlocking {
        val row = IptvSearchRow("c", "n", "movie", listOf(IptvSearchHit("id", "name", null, false)))
        val sig = MutableStateFlow<String?>("sigA")
        SearchProviderRegistry.register("a", FakeSearch(true, listOf(row), sig))
        val composite = CompositeSearchProvider { SearchProviderRegistry.all }
        assertEquals(listOf(row), composite.search("q"))
        assertEquals("sigA", composite.sourceSignature().first())
        assertTrue(composite.hasSearchableSources())

        val sigB = MutableStateFlow<String?>(null)
        SearchProviderRegistry.register("b", FakeSearch(true, listOf(row.copy(catalogId = "c2")), sigB))
        assertEquals(listOf("c", "c2"), composite.search("q").map { it.catalogId })
        assertEquals("sigA", composite.sourceSignature().first())
        sigB.value = "sigB"
        assertEquals("sigA\u0004sigB", composite.sourceSignature().first())
    }

    @Test
    fun `a provider with no sources is not searched and one that throws costs only its rows`() = runBlocking {
        val row = IptvSearchRow("c", "n", "movie", emptyList())
        SearchProviderRegistry.register("off", FakeSearch(false, listOf(row.copy(catalogId = "off")), flowOf(null)))
        SearchProviderRegistry.register("boom", FakeSearch(true, emptyList(), flowOf(null), throws = true))
        SearchProviderRegistry.register("ok", FakeSearch(true, listOf(row), flowOf("s")))
        val composite = CompositeSearchProvider { SearchProviderRegistry.all }
        assertEquals(listOf("c"), composite.search("q").map { it.catalogId })
    }

    @Test
    fun `reporters only hear about their own plays and one failing does not starve the others`() = runBlocking {
        val log = mutableListOf<String>()
        PlaybackSessionReporterRegistry.register(FakeReporter("bad", prefix = "ms:", log = log, throws = true))
        PlaybackSessionReporterRegistry.register(FakeReporter("good", prefix = "ms:", log = log))
        PlaybackSessionReporterRegistry.register(FakeReporter("other", prefix = "xtream:", log = log))
        val s = PlaybackSessionState("ms:1", "ms:1", "ms", 0, 100)
        PlaybackSessionReporterRegistry.start(s)
        PlaybackSessionReporterRegistry.progress(s, paused = true)
        PlaybackSessionReporterRegistry.stop(s)
        assertEquals(listOf("good:start", "good:progress:true", "good:stop"), log)
    }

    @Test
    fun `resume offers come from the first source that owns the item and a failing source offers nothing`() = runBlocking {
        PlaybackResumeOfferRegistry.register(object : PlaybackResumeOfferSource {
            override val name = "bad"
            override fun handles(videoId: String, providerAddonId: String?) = true
            override suspend fun offer(videoId: String, tuvoraPositionMs: Long?, tuvoraUpdatedAtMs: Long?, durationMs: Long?): PlaybackResumeOffer? = error("boom")
        })
        PlaybackResumeOfferRegistry.register(object : PlaybackResumeOfferSource {
            override val name = "ok"
            override fun handles(videoId: String, providerAddonId: String?) = videoId.startsWith("ms:")
            override suspend fun offer(videoId: String, tuvoraPositionMs: Long?, tuvoraUpdatedAtMs: Long?, durationMs: Long?) = PlaybackResumeOffer(42)
        })
        assertEquals(42L, PlaybackResumeOfferRegistry.offerFor("ms:1", null, 0, 0, 100)?.positionMs)
        assertNull(PlaybackResumeOfferRegistry.offerFor("tt1", null, 0, 0, 100).takeIf { false })
    }

    @Test
    fun `home contributors are collected in order, a failing one costs only its rows and a repeated key keeps the first`() = runBlocking {
        fun row(key: String) = ContributedHomeRow(key, "src", key, key, "sub", "movie", emptyList(), false)
        HomeSectionContributorRegistry.register(object : HomeSectionContributor {
            override val name = "a"
            override suspend fun sections(forceRefresh: Boolean) = listOf(row("k1"), row("k2"))
            override fun ownsSource(sourceKey: String) = sourceKey == "src"
            override suspend fun loadSourcePage(sourceKey: String, listId: String, skip: Int?) = ContributedPage(emptyList(), null)
            override fun declaredRows() = listOf(ContributedRowDeclaration("k1", "One", "a"))
        })
        HomeSectionContributorRegistry.register(object : HomeSectionContributor {
            override val name = "boom"
            override suspend fun sections(forceRefresh: Boolean): List<ContributedHomeRow> = error("boom")
            override fun ownsSource(sourceKey: String) = false
            override suspend fun loadSourcePage(sourceKey: String, listId: String, skip: Int?) = ContributedPage(emptyList(), null)
            override fun declaredRows(): List<ContributedRowDeclaration> = error("boom")
        })
        HomeSectionContributorRegistry.register(object : HomeSectionContributor {
            override val name = "dup"
            override suspend fun sections(forceRefresh: Boolean) = listOf(row("k2"), row("k3"))
            override fun ownsSource(sourceKey: String) = false
            override suspend fun loadSourcePage(sourceKey: String, listId: String, skip: Int?) = ContributedPage(emptyList(), null)
            override fun declaredRows() = listOf(ContributedRowDeclaration("k1", "Dup", "dup"), ContributedRowDeclaration("k9", "Nine", "dup"))
        })
        assertEquals(listOf("k1", "k2", "k3"), HomeSectionContributorRegistry.collectSections(false).map { it.key })
        assertEquals(listOf("k1", "k9"), HomeSectionContributorRegistry.declaredRows().map { it.key })
        assertEquals("One", HomeSectionContributorRegistry.declaredRows().first().title)
        assertTrue(HomeSectionContributorRegistry.isContributedKey("k9"))
        assertFalse(HomeSectionContributorRegistry.isContributedKey("k2"))
        assertNull(HomeSectionContributorRegistry.loadSourcePage("nobody", "x", null))
        assertTrue(HomeSectionContributorRegistry.loadSourcePage("src", "x", null)?.items?.isEmpty() == true)
    }

    @Test
    fun ownSourceSubtitlesComeOnlyFromTheOwnerAndAFailureCostsNothing() = runBlocking {
        fun sub(id: String) = com.nuvio.tv.domain.model.Subtitle(id = id, url = "http://x/$id", lang = "en", addonName = "A", addonLogo = null)
        OwnSourceSubtitleRegistry.register(object : OwnSourceSubtitleProvider {
            override val name = "a"
            override fun handles(videoId: String) = videoId.startsWith("a:")
            override suspend fun subtitles(videoId: String) = listOf(sub("one"), sub("two"))
        })
        OwnSourceSubtitleRegistry.register(object : OwnSourceSubtitleProvider {
            override val name = "boom"
            override fun handles(videoId: String) = true
            override suspend fun subtitles(videoId: String): List<com.nuvio.tv.domain.model.Subtitle> = error("boom")
        })
        assertEquals(listOf("one", "two"), OwnSourceSubtitleRegistry.subtitlesFor("a:1").map { it.id })
        assertTrue("nothing owns it but the failing source: still no throw", OwnSourceSubtitleRegistry.subtitlesFor("tt1").isEmpty())
        assertTrue(OwnSourceSubtitleRegistry.subtitlesFor(null).isEmpty())
    }

    private class FakeStream(private val prefix: String) : StreamSourceProvider {
        override fun isHandledId(videoId: String?) = videoId?.startsWith(prefix) == true
        override suspend fun directStreams(videoId: String) = listOf(AddonStreams("$videoId from $prefix", null, emptyList()))
        override fun matchSourceGroups(type: String) = listOf(StreamSourceGroup("${prefix}g", "G"))
        override suspend fun resolveMatchStreams(sourceId: String, type: String, videoId: String, season: Int?, episode: Int?) = emptyList<Stream>()
        override fun isMatchSourceId(providerAddonId: String) = providerAddonId.startsWith(prefix)
        override fun isDeferredUrl(url: String?) = url == "${prefix}deferred"
        override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean) = "minted $url"
    }

    private class FakeMeta(private val prefix: String) : MetaSourceProvider {
        override fun handlesId(id: String) = id.startsWith(prefix)
        override suspend fun meta(type: String, id: String): Meta? = null
    }

    private class FakeSearch(
        private val searchable: Boolean,
        private val rows: List<IptvSearchRow>,
        private val signature: Flow<String?>,
        private val throws: Boolean = false,
    ) : IptvSearchProvider {
        override suspend fun hasSearchableSources() = searchable
        override suspend fun search(query: String): List<IptvSearchRow> = if (throws) error("boom") else rows
        override fun sourceSignature() = signature
    }

    private class FakeReporter(
        override val name: String,
        private val prefix: String = "",
        private val log: MutableList<String> = mutableListOf(),
        private val throws: Boolean = false,
    ) : PlaybackSessionReporter {
        override fun handles(videoId: String, providerAddonId: String?) = videoId.startsWith(prefix)
        override suspend fun onStart(session: PlaybackSessionState) { if (throws) error("boom"); log += "$name:start" }
        override suspend fun onProgress(session: PlaybackSessionState, paused: Boolean) { if (throws) error("boom"); log += "$name:progress:$paused" }
        override suspend fun onStop(session: PlaybackSessionState) { if (throws) error("boom"); log += "$name:stop" }
    }
}

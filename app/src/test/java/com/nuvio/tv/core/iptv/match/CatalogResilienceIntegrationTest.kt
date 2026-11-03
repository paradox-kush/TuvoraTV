package com.nuvio.tv.core.iptv.match

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nuvio.tv.core.iptv.HttpStatusException
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.journal.StartupJournal
import com.nuvio.tv.core.journal.StartupJournalStore
import com.nuvio.tv.core.tmdb.TmdbTitleBundle
import com.nuvio.tv.data.local.XtreamAccountStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Regression (2026-11-02, owner's iPad + TV): two real Xtream panels answered a full `get_series` with a 200
 * whose body stopped mid-JSON (24 KB / 110 KB) or trickled at ~0.45 KB/s. Every lookup waited on that catalog,
 * the source lane cut it, and every title read "No streams found" — even titles another device had already
 * matched. The REAL resolver and index (framework SQLite under Robolectric) over a faked client.
 * Twin of Mobile's CatalogResilienceIntegrationTest. JUnit: assertEquals(message, expected, actual).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CatalogResilienceIntegrationTest {

    private lateinit var context: Context
    private lateinit var index: XtreamMatchIndex
    private val client = mockk<XtreamClient>()
    private val sync = mockk<XtreamMatchSyncService>()
    private val accountStore = mockk<XtreamAccountStore>()
    private lateinit var resolver: XtreamTmdbResolver

    private val undekhi = TmdbTitleBundle(primary = "Undekhi", original = null, alternatives = emptyList(), year = 2020)

    private fun account(id: String) = XtreamAccount(id = "http://$id.test|u", name = id, baseUrl = "http://$id.test", username = "u", password = "p")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Without a healthy StartupJournal the resolver withholds every build (beginAttempt == null), so a
        // hanging download would never be awaited and the test could not tell blocking from not blocking.
        val dir = java.nio.file.Files.createTempDirectory("journal").toFile()
        StartupJournalStore.initialize(mockk<Context> { every { filesDir } returns dir })
        resetJournalSingleton()
        every { sync.triggerPush(any()) } just runs
        coEvery { sync.pullOnce(any()) } just runs
        index = XtreamMatchIndex(context)
        resolver = XtreamTmdbResolver(context, client, index, sync, accountStore)
    }

    /** The journal is a process-wide singleton that may already have initialised unhealthy in another test. */
    private fun resetJournalSingleton() {
        val impl = StartupJournal::class.java.getDeclaredField("impl").apply { isAccessible = true }.get(StartupJournal)
        impl.javaClass.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(impl, false)
    }

    private fun seriesCategories(acc: XtreamAccount, vararg ids: String) {
        coEvery { client.seriesCategories(acc) } returns Result.success(ids.map { XtreamCategory(it, "cat $it") })
    }

    @Test
    fun `a remembered match plays while the catalog download hangs`() = runBlocking {
        val acc = account("hang")
        seriesCategories(acc, "1")
        coEvery { client.seriesIndexItemsInto(acc, any(), any()) } coAnswers { awaitCancellation() } // never lands
        index.putMapping(acc.id, MatchKind.SERIES, tmdb = 777, sid = 42, matchedName = "Undekhi")

        val match = withTimeoutOrNull(3_000) { resolver.resolve(acc, MatchKind.SERIES, 777, undekhi) }

        assertNotNull("the lookup must not wait for a catalog that never lands", match)
        assertEquals("the remembered sid", 42, match!!.item.sid)
    }

    @Test
    fun `a lookup with nothing remembered answers at once and reports the catalog pending`() = runBlocking {
        val acc = account("pending")
        seriesCategories(acc, "1")
        coEvery { client.seriesIndexItemsInto(acc, any(), any()) } coAnswers { awaitCancellation() }

        val answered = withTimeoutOrNull(3_000) { resolver.resolve(acc, MatchKind.SERIES, 888, undekhi); true }

        assertEquals("the lookup must answer without waiting for the catalog", true, answered)
        assertTrue("nothing local to search yet", resolver.catalogPending(acc.id, MatchKind.SERIES))
        assertNull("a pending lookup must not cache a 'not on this provider' verdict", index.cachedMapping(acc.id, MatchKind.SERIES, 888))
    }

    @Test
    fun `a cut off full list keeps what arrived and the category walk completes the catalog`() = runBlocking {
        val acc = account("cutoff")
        seriesCategories(acc, "1", "2")
        coEvery { client.seriesIndexItemsInto(acc, any(), any()) } coAnswers {
            val onItem = thirdArg<(IndexedItem) -> Unit>()
            when (secondArg<String?>()) {
                // A 200 whose body stops mid-JSON, as measured on the real panel: one row, then the cut.
                null -> { onItem(IndexedItem(1, "Alpha Show", null, null, null, categoryId = "1")); Result.failure(IOException("unterminated string")) }
                "1" -> { onItem(IndexedItem(1, "Alpha Show", null, null, null, categoryId = "1")); Result.success(1) }
                else -> { onItem(IndexedItem(5, "Undekhi", null, null, null, categoryId = "2")); Result.success(1) }
            }
        }

        resolver.ensureIndexed(acc, MatchKind.SERIES)

        assertNotNull("a complete category walk stamps the catalog built", index.builtAt(acc.id, MatchKind.SERIES))
        assertEquals("the title the cut-off list never reached is found", listOf(5), index.searchByName(acc.id, MatchKind.SERIES, "Undekhi", 5).map { it.sid })
        assertFalse("no longer pending", resolver.catalogPending(acc.id, MatchKind.SERIES))
    }

    @Test
    fun `a provider that refuses mid walk leaves a searchable partial index that is not built`() = runBlocking {
        val acc = account("refuse")
        seriesCategories(acc, "1", "2", "3")
        coEvery { client.seriesIndexItemsInto(acc, any(), any()) } coAnswers {
            val onItem = thirdArg<(IndexedItem) -> Unit>()
            when (secondArg<String?>()) {
                null -> Result.failure(IOException("cut"))
                "1" -> { onItem(IndexedItem(5, "Undekhi", null, null, null, categoryId = "1")); Result.success(1) }
                else -> Result.failure(HttpStatusException(429, "HTTP 429"))
            }
        }

        resolver.ensureIndexed(acc, MatchKind.SERIES)

        assertNull("a walk that stopped is never reported built", index.builtAt(acc.id, MatchKind.SERIES))
        assertEquals("what arrived stays searchable", listOf(5), index.searchByName(acc.id, MatchKind.SERIES, "Undekhi", 5).map { it.sid })
        assertFalse("partial rows are something to search, so not 'pending'", resolver.catalogPending(acc.id, MatchKind.SERIES))
    }
}

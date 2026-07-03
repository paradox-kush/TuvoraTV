package com.nuvio.tv.core.iptv

import android.net.Uri
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.iptv.epg.XmltvClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * Round-trips the FILE M3U source: write a local .m3u (plain + gzip), ensureIngested it through the
 * SAME parser/DB as a URL playlist, and verify the missing-local-file case degrades cleanly.
 * Robolectric for real SQLite + a real ContentResolver (file:// uris), like IptvContentDbTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class M3UFileIngestTest {

    private val app = RuntimeEnvironment.getApplication()
    private val db = IptvContentDb(app)
    private val fileStore = M3UFileStore(app)
    // XmltvClient is exercised only for its (no-op here) EPG refresh; a bare OkHttp is never called
    // because these file playlists have no epgUrl / url-tvg header.
    private val xmltv = XmltvClient(db, OkHttpClient())
    private val client = M3UClient(db, OkHttpClient(), fileStore, xmltv)

    private val SAMPLE = """
        #EXTM3U
        #EXTINF:-1 tvg-id="bbc.uk" tvg-logo="http://img/bbc.png" group-title="UK",BBC One
        http://h:8080/live/u/p/1.ts
        #EXTINF:-1 tvg-name="Alien Romulus (2024)" group-title="MOVIES",Alien Romulus (2024)
        https://h:443/movie/u/p/385215.mp4
        #EXTINF:-1 group-title="SERIES",The Grand Tour S01E02
        http://h/series/u/p/11.mkv
    """.trimIndent()

    private fun fileAccount(id: String = newM3UFilePlaylistId()) =
        m3uAccountFromFile(id, fileName = "myplaylist.m3u", name = "My File")

    /** Simulate the user picking a file: copy source bytes into the store via a file:// uri. */
    private suspend fun importSource(acc: XtreamAccount, bytes: ByteArray) {
        val src = File(app.cacheDir, "picked-${acc.id.hashCode()}.m3u").apply { writeBytes(bytes) }
        fileStore.importFrom(acc.id, Uri.fromFile(src))
        src.delete()   // the SOURCE can vanish — ingest must use our copy
    }

    @Test
    fun `file account maps to SOURCE_FILE with a stable id and fileName`() {
        val acc = fileAccount("file:abc")
        assertEquals(XtreamAccount.SOURCE_FILE, acc.sourceType)
        assertTrue(acc.isM3UFile())
        assertTrue(acc.isM3UBacked())
        assertEquals("file:abc", acc.id)
        assertEquals("myplaylist.m3u", acc.fileName)
        assertEquals("", acc.baseUrl)   // no URL — the local copy is the source
    }

    @Test
    fun `import then ingest browses like a URL playlist`() = runTest {
        val acc = fileAccount()
        importSource(acc, SAMPLE.toByteArray(Charsets.UTF_8))
        assertTrue(fileStore.exists(acc.id))

        client.ensureIngested(acc, force = true)

        assertNotNull("meta row written -> built", db.builtAt(acc.id))
        // Same classification the URL path produces: 1 live, 1 vod, 1 series episode.
        val channels = db.channelsFor(acc.id, null)
        assertEquals(listOf("BBC One"), channels.map { it.name })
        assertEquals("bbc.uk", channels[0].tvgId)
        assertEquals(listOf("Alien Romulus (2024)"), db.vodFor(acc.id, null).map { it.name })
        val series = db.seriesFor(acc.id, null)
        assertEquals(listOf("The Grand Tour"), series.map { it.name })
        assertEquals(1, db.episodesFor(acc.id, series[0].sid).size)
    }

    @Test
    fun `gzip-compressed file ingests transparently`() = runTest {
        val acc = fileAccount()
        val gz = java.io.ByteArrayOutputStream().also { bos ->
            java.util.zip.GZIPOutputStream(bos).use { it.write(SAMPLE.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        importSource(acc, gz)

        client.ensureIngested(acc, force = true)

        assertNotNull(db.builtAt(acc.id))
        assertEquals(listOf("BBC One"), db.channelsFor(acc.id, null).map { it.name })
    }

    @Test
    fun `missing local file does not crash and leaves catalog unbuilt`() = runTest {
        val acc = fileAccount()   // never imported -> no local copy (e.g. synced from another device)
        assertFalse(fileStore.exists(acc.id))

        // Must not throw; builtAt stays null so the hub shows the re-import affordance.
        client.ensureIngested(acc, force = true)

        assertNull(db.builtAt(acc.id))
        assertTrue(db.channelsFor(acc.id, null).isEmpty())
    }

    @Test
    fun `re-import replaces the local copy and re-ingests`() = runTest {
        val acc = fileAccount()
        importSource(acc, SAMPLE.toByteArray(Charsets.UTF_8))
        client.ensureIngested(acc, force = true)
        assertEquals(1, db.channelsFor(acc.id, null).size)

        // Re-pick a different, smaller playlist under the SAME id (edit / re-import).
        val smaller = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",Only One\nhttp://h/live/u/p/9.ts"
        importSource(acc, smaller.toByteArray(Charsets.UTF_8))
        client.ensureIngested(acc, force = true)

        val channels = db.channelsFor(acc.id, null)
        assertEquals(listOf("Only One"), channels.map { it.name })
        assertTrue(db.vodFor(acc.id, null).isEmpty())
    }
}

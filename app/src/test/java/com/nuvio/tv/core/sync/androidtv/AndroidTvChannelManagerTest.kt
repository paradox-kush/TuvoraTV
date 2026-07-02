package com.nuvio.tv.core.sync.androidtv

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

// Robolectric supplies real Intent/ContentValues/Uri so the reconcile-path tests can
// exercise buildProgramValues. application = plain Application: Robolectric instantiates
// the manifest app class per test, and @HiltAndroidApp NuvioApplication can't boot here.
// sdk = 35, not 36: Robolectric's SDK 36 sandbox requires Java 21; this repo builds on 17.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidTvChannelManagerTest {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val prefs: TvChannelPreferences = mockk(relaxed = true)
    private lateinit var manager: AndroidTvChannelManager

    private val channelId = 42L

    @Before
    fun setUp() {
        // reconcile() swallows failures into Log.w — echo logs so they show in test output
        ShadowLog.stream = System.out

        every { context.packageManager } returns packageManager
        every { context.contentResolver } returns contentResolver
        every { context.packageName } returns "com.nuvio.tv"
        every { context.getString(any<Int>()) } returns "Continue Watching"
        manager = AndroidTvChannelManager(context, prefs)
    }

    @Test
    fun `isSupported returns false on non-leanback device`() {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns false
        assert(!manager.isSupported())
    }

    @Test
    fun `isSupported returns true on leanback device`() {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns true
        assert(manager.isSupported())
    }

    @Test
    fun `reconcile is no-op on non-leanback device`() = runTest {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns false
        manager.reconcile(listOf(fakeProgress("tt001", "movie")))
        verify(exactly = 0) { contentResolver.insert(any(), any()) }
        verify(exactly = 0) { contentResolver.update(any(), any(), any(), any()) }
        verify(exactly = 0) { contentResolver.delete(any(), any(), any()) }
    }

    @Test
    fun `reconcile inserts program when channel exists and no existing programs`() = runTest {
        setupLeanback()
        setupChannelExists()
        stubProgramQuery(existingRows = emptyMap())
        every { contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, any()) } returns
            Uri.parse("content://android.media.tv/preview_program/1")

        manager.reconcile(listOf(fakeProgress("tt001", "movie")))

        verify(exactly = 1) { contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, any()) }
        verify(exactly = 0) { contentResolver.update(any(), any(), null, null) }
    }

    @Test
    fun `reconcile updates program that already exists in channel`() = runTest {
        setupLeanback()
        setupChannelExists()
        stubProgramQuery(existingRows = mapOf("tt001" to 99L))
        every { contentResolver.update(any(), any(), null, null) } returns 1

        manager.reconcile(listOf(fakeProgress("tt001", "movie")))

        verify(exactly = 0) { contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, any()) }
        verify(exactly = 1) { contentResolver.update(any(), any(), null, null) }
    }

    @Test
    fun `reconcile deletes program that is no longer in the list`() = runTest {
        setupLeanback()
        setupChannelExists()
        // Channel has tt002 but we reconcile with only tt001
        stubProgramQuery(existingRows = mapOf("tt002" to 77L))
        every { contentResolver.delete(any(), null, null) } returns 1
        every { contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, any()) } returns
            Uri.parse("content://android.media.tv/preview_program/1")

        manager.reconcile(listOf(fakeProgress("tt001", "movie")))

        verify(exactly = 1) { contentResolver.delete(any(), null, null) }
        verify(exactly = 1) { contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, any()) }
    }

    // --- helpers ---

    private fun setupLeanback() {
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns true
    }

    private fun setupChannelExists() {
        coEvery { prefs.getChannelId() } returns channelId
        val channelCursor = mockk<Cursor>(relaxed = true) {
            every { moveToFirst() } returns true
            every { getLong(0) } returns channelId
        }
        // any() URI: don't couple the stub to exact channel-URI construction
        every { contentResolver.query(any(), any(), null, null, null) } returns channelCursor
    }

    private fun stubProgramQuery(existingRows: Map<String, Long>) {
        val rows = existingRows.entries.toList()
        val cursor = mockk<Cursor>(relaxed = true) {
            var idx = -1
            every { moveToNext() } answers { idx++; idx < rows.size }
            every { getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID) } returns 0
            every { getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID) } returns 1
            // queryExistingPrograms filters by channel id in memory (Fire OS workaround)
            every { getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID) } returns 2
            every { getLong(0) } answers { rows[idx].value }
            every { getString(1) } answers { rows[idx].key }
            every { getLong(2) } returns channelId
        }
        every {
            contentResolver.query(TvContractCompat.PreviewPrograms.CONTENT_URI, any(), any(), any(), null)
        } returns cursor
    }

    private fun fakeProgress(contentId: String, contentType: String) = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = "Test Title",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 300_000L,
        duration = 5_400_000L,
        lastWatched = System.currentTimeMillis()
    )
}

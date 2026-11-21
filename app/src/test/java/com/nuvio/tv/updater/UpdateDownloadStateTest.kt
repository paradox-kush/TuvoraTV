package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B274: a release was downloading in the background while the banner still offered "Download", and flipped to
 * "Install" only at the next launch. The old banner knew nothing but its own manual download, so every case below
 * with a background download in flight would have resolved to isDownloading=false.
 *
 * JUnit argument order: assertEquals(message, expected, actual).
 */
class UpdateDownloadStateTest {

    private val tag = "v1.24.0"

    private fun resolve(
        download: UpdateDownloadState,
        manualPending: Boolean = false,
        knownApkPath: String? = null,
        knownError: String? = null,
        updateTag: String? = tag
    ) = UpdateDownloadUiPolicy.resolve(updateTag, download, manualPending, knownApkPath, knownError)

    @Test
    fun `background download in flight shows downloading not Download`() {
        val r = resolve(UpdateDownloadState.Downloading(tag, 0.4f))
        assertTrue("banner must say downloading", r.isDownloading)
        assertEquals("progress carried through", 0.4f, r.downloadProgress)
        assertNull("no APK yet", r.downloadedApkPath)
    }

    @Test
    fun `background download without progress yet is still downloading`() {
        val r = resolve(UpdateDownloadState.Downloading(tag, null))
        assertTrue("downloading", r.isDownloading)
        assertNull("unknown progress", r.downloadProgress)
    }

    @Test
    fun `background download finishing flips to Install without a relaunch`() {
        val r = resolve(UpdateDownloadState.Ready(tag, "/files/updates/app.apk"))
        assertFalse("not downloading", r.isDownloading)
        assertEquals("install is offered", "/files/updates/app.apk", r.downloadedApkPath)
    }

    @Test
    fun `download of another release does not leak into this banner`() {
        val r = resolve(UpdateDownloadState.Downloading("v1.25.0", 0.9f))
        assertFalse("different tag", r.isDownloading)
        assertNull("no progress", r.downloadProgress)
    }

    @Test
    fun `ready file of another release is not offered`() {
        val r = resolve(UpdateDownloadState.Ready("v1.23.9", "/old.apk"))
        assertNull("stale file ignored", r.downloadedApkPath)
    }

    @Test
    fun `press on Download queued behind the job shows downloading at once`() {
        val r = resolve(UpdateDownloadState.Idle, manualPending = true)
        assertTrue("pressed, so busy", r.isDownloading)
        assertEquals("starts at zero", 0f, r.downloadProgress)
    }

    @Test
    fun `idle store keeps what the view model knows`() {
        val r = resolve(UpdateDownloadState.Idle, knownApkPath = "/a.apk", knownError = "boom")
        assertFalse("idle", r.isDownloading)
        assertEquals("path kept", "/a.apk", r.downloadedApkPath)
        assertEquals("error kept", "boom", r.errorMessage)
    }

    @Test
    fun `background failure surfaces its message for retry`() {
        val r = resolve(UpdateDownloadState.Failed(tag, "HTTP 500"))
        assertFalse("not downloading", r.isDownloading)
        assertEquals("failure shown", "HTTP 500", r.errorMessage)
    }

    @Test
    fun `retry press hides the old failure`() {
        val r = resolve(UpdateDownloadState.Failed(tag, "HTTP 500"), manualPending = true)
        assertTrue("busy again", r.isDownloading)
        assertNull("old error gone", r.errorMessage)
    }

    @Test
    fun `no release means nothing is downloading`() {
        val r = resolve(UpdateDownloadState.Downloading(tag, 0.5f), updateTag = null)
        assertFalse("no banner subject", r.isDownloading)
    }
}

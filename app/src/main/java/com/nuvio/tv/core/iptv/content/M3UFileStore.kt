package com.nuvio.tv.core.iptv.content

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-disk copy of a **file** M3U playlist (sourceType = SOURCE_FILE). When the user picks
 * a document we stream its bytes into `files/playlists/{hash}.m3u` so browsing/playback no longer
 * depends on the original file (which can be moved/deleted, or is a transient SAF content:// uri).
 * [M3UClient] then ingests from this local copy exactly like it ingests a URL body — same parser,
 * same content DB.
 *
 * The filename is a hash of the playlist id (ids are `file:{uuid}`, already safe, but hashing keeps
 * a bounded, always-valid filename regardless of the id scheme). Gzip-aware reading is handled by
 * the ingest path (it sniffs the magic bytes), so we store the raw bytes verbatim here.
 */
@Singleton
class M3UFileStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private fun dir(): File = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** The local copy for a playlist id (may or may not exist — call [exists]). */
    fun fileFor(playlistId: String): File = File(dir(), fileName(playlistId))

    /** True once a local copy has been imported on THIS device. */
    fun exists(playlistId: String): Boolean = fileFor(playlistId).let { it.exists() && it.length() > 0 }

    /**
     * Copy the picked document's bytes into the playlist's local file, streaming (never buffering
     * the whole thing — an M3U can be large). Returns the destination file on success, throws on an
     * unreadable uri / write failure (the caller surfaces it). The temp-then-rename keeps a failed
     * copy from leaving a truncated file that would read as "imported".
     */
    suspend fun importFrom(playlistId: String, uri: Uri): File = withContext(Dispatchers.IO) {
        val dest = fileFor(playlistId)
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Cannot open the picked file" }
            tmp.outputStream().use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
        }
        if (dest.exists()) dest.delete()
        check(tmp.renameTo(dest)) { "Could not save the imported file" }
        dest
    }

    /** Remove a playlist's local copy (on account delete). */
    suspend fun delete(playlistId: String) = withContext(Dispatchers.IO) {
        fileFor(playlistId).delete()
        Unit
    }

    private fun fileName(playlistId: String): String {
        // A stable, filesystem-safe name derived from the id. SHA-256 hex is always valid.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(playlistId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".m3u"
    }

    companion object {
        private const val DIR = "playlists"
    }
}

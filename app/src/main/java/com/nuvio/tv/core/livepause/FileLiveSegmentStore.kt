package com.nuvio.tv.core.livepause

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * [LiveSegmentStore] over a directory (`cacheDir/live-pause/<instanceId>/`). The playlist is written to a
 * temp file and renamed, so a player reloading it never reads half of it. Store errors propagate (a full
 * disk must surface as a Failed state, never be swallowed).
 */
class FileLiveSegmentStore(val dir: File) : LiveSegmentStore {
    init {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create live pause directory")
    }

    val playlistFile: File get() = File(dir, PLAYLIST)

    override fun openPiece(name: String): LiveSegmentWriter {
        val out = FileOutputStream(File(dir, name))
        return object : LiveSegmentWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = out.close()
        }
    }

    /** A piece that is downloaded in one go: `<name>.part`, renamed when complete (never listed half-written). */
    fun writeWhole(name: String, write: (java.io.OutputStream) -> Unit): Long {
        val part = File(dir, "$name.part")
        val target = File(dir, name)
        try {
            FileOutputStream(part).use(write)
            if (!part.renameTo(target)) throw IOException("cannot rename a finished piece")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        return target.length()
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    override fun writePlaylistAtomically(text: String) {
        val temp = File(dir, "$PLAYLIST.tmp")
        FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        if (!temp.renameTo(playlistFile)) {
            temp.delete()
            throw IOException("cannot publish the local playlist")
        }
    }

    fun deleteAll() {
        dir.deleteRecursively()
    }

    companion object {
        const val PLAYLIST = "index.m3u8"

        /** Startup sweep: a capture directory untouched for [maxAgeMs] belongs to a process that died. */
        fun sweepStale(root: File, nowMs: Long, maxAgeMs: Long = SWEEP_AGE_MS): Int {
            var removed = 0
            root.listFiles()?.forEach { child ->
                if (nowMs - newestModified(child) > maxAgeMs && child.deleteRecursively()) removed++
            }
            return removed
        }

        private fun newestModified(file: File): Long =
            (file.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L).coerceAtLeast(file.lastModified())

        const val SWEEP_AGE_MS = 6L * 60 * 60 * 1000
    }
}

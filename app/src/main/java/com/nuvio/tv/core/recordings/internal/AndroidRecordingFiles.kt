package com.nuvio.tv.core.recordings.internal

import android.os.StatFs
import com.nuvio.tv.core.recordings.api.RecordingFiles
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-private storage: state files in [stateDir], recordings in [recordingsDir]. A state write goes
 * to a temporary file, is fsynced, then moved over the old one, so a crash leaves the old state or
 * the new one and never half of each.
 */
internal class AndroidRecordingFiles(private val stateDir: File, private val recordingsDir: File) : RecordingFiles {

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        File(stateDir, name).takeIf { it.isFile }?.readText()
    }

    override suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        check(stateDir.isDirectory || stateDir.mkdirs()) { "Cannot create recordings state directory" }
        val tmp = File(stateDir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // rename(2) within one directory is atomic and replaces the old file (minSdk 24 has no java.nio.file).
        check(tmp.renameTo(File(stateDir, name))) { "Cannot replace recordings state" }
    }

    override suspend fun keepAside(name: String) = withContext(Dispatchers.IO) {
        val f = File(stateDir, name)
        if (f.exists()) f.renameTo(File(stateDir, "$name.corrupt-${System.currentTimeMillis()}"))
        Unit
    }

    override fun pathFor(fileName: String): String {
        require(File(fileName).name == fileName && fileName.isNotBlank()) { "not a plain file name" }
        return File(recordingsDir, fileName).path
    }

    override fun freeBytes(): Long {
        recordingsDir.mkdirs()
        return StatFs(recordingsDir.path).availableBytes
    }

    override fun sizeOf(path: String): Long = File(path).takeIf { it.isFile }?.length() ?: 0L

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        File(path).delete()
        Unit
    }
}

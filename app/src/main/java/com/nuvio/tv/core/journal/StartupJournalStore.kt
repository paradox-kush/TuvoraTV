package com.nuvio.tv.core.journal

import android.content.Context
import kotlin.concurrent.Volatile
import java.io.File
import java.io.FileOutputStream

/**
 * File-backed journal in the app's private storage. Written atomically (temp + fsync + rename) and
 * verified by read-back — a setter returning is not proof of durable persistence, so this fsyncs the
 * file descriptor then confirms the bytes. [initialize] runs in app init before the resolver; until
 * then writes report failure and the journal is UNHEALTHY, so automatic risky work is withheld.
 */
internal object StartupJournalStore {
    private const val FILE = "startup-journal.json"

    @Volatile private var dir: File? = null

    fun initialize(context: Context) {
        dir = context.filesDir
    }

    fun read(): String? = runCatching {
        val f = File(dir ?: return@runCatching null, FILE)
        if (!f.exists()) return@runCatching null
        // Bound the read: a corrupt/tampered journal must not fully load into memory.
        val text = readBoundedJournal(f.inputStream(), StartupJournalPolicy.MAX_BLOB_BYTES)
        if (text == null) f.delete()
        text
    }.getOrNull()

    fun writeVerified(content: String): Boolean = runCatching {
        val d = dir ?: return false
        val target = File(d, FILE)
        val tmp = File(d, "$FILE.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) return false
        }
        target.readText() == content
    }.getOrDefault(false)
}

/** Reads up to [maxBytes] of UTF-8, returning null if the source exceeds it — enforced while
 *  consuming, so an oversized/corrupt file never fully lands in memory. */
private fun readBoundedJournal(input: java.io.InputStream, maxBytes: Int): String? {
    input.use { ins ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}

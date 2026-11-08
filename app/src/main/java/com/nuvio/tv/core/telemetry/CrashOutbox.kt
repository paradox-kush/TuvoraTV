package com.nuvio.tv.core.telemetry

import java.io.File

/**
 * The crash records waiting to be sent. Every crash gets its OWN file (`sending_<crashId>.json`), so a
 * crash loop that dies faster than the confirm window cannot overwrite an earlier crash or starve a
 * later one: one launch sends every waiting record, newest first, up to [MAX_PER_LAUNCH].
 */
object CrashOutbox {
    const val PENDING = "pending_crash.json"
    const val MAX_PER_LAUNCH = 5
    private const val PREFIX = "sending"

    data class Entry(val file: File, val record: CrashRecord)

    /**
     * Promotes the pending record (written by the crash handler) to its own `sending_<id>.json`, then
     * returns the readable waiting records. Unreadable files are deleted. Anything beyond the cap is
     * deleted too: an endless loop must not queue unbounded files.
     */
    fun collect(dir: File): List<Entry> {
        val pending = File(dir, PENDING)
        if (pending.exists()) {
            val record = runCatching { CrashRecord.fromJson(pending.readText()) }.getOrNull()
            if (record == null) pending.delete()
            else if (!pending.renameTo(File(dir, "${PREFIX}_${record.crashId}.json"))) {
                // Could not rename: keep it readable in place so the next launch tries again.
                return listOf(Entry(pending, record))
            }
        }
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".json") }
            .orEmpty().sortedByDescending { it.lastModified() }
        val entries = ArrayList<Entry>()
        for (f in files) {
            val record = runCatching { CrashRecord.fromJson(f.readText()) }.getOrNull()
            if (record == null || entries.size >= MAX_PER_LAUNCH) f.delete() else entries += Entry(f, record)
        }
        return entries
    }
}

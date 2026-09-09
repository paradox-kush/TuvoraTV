package com.nuvio.tv.core.rec

/**
 * Bounds both the RESTORE (read) and PERSIST (write) of the recommendation-event queue BEFORE any
 * large allocation. Twin of NuvioMobile/NuvioDesktop `RecEventQueueRestorePolicy` — hand-ported.
 *
 * A 500-record queue is NOT byte-bounded, so this bounds by record count, per-record size, and total
 * size, on both directions. Byte vs char are separate limits, deliberately NOT an ASCII equivalence:
 * [MAX_QUEUE_BYTES] bounds a file/stream read on the STORAGE side; [MAX_QUEUE_CHARS]/[MAX_RECORD_CHARS]
 * bound the in-memory string on the DECODE side. Pure so it is unit-tested without the file or a clock.
 */
internal object RecEventQueueRestorePolicy {
    /** Char cap for an in-memory string (decode side). ~512 KB; a healthy file measured ~100 KB. */
    const val MAX_QUEUE_CHARS = 512_000

    /** Byte cap for a file/stream read (storage side, before decode). 1 MiB, >= MAX_QUEUE_CHARS so an
     *  in-bound string always fits even at 2 bytes/char, while a corrupt/huge file is rejected WHILE
     *  the stream is consumed — not only by a preceding length check. */
    const val MAX_QUEUE_BYTES = 1_048_576

    /** Per-record char cap. A single impression record is a few short fields. */
    const val MAX_RECORD_CHARS = 8_192

    data class Bounded(val lines: List<String>, val oversized: Boolean)

    /**
     * Keeps the newest [maxRecords] in-bound lines whose combined size (with separators) stays within
     * [maxTotalChars], preserving on-disk order. Used on BOTH read (restore) and write (persist).
     */
    fun boundLines(
        lines: List<String>,
        maxRecords: Int,
        maxTotalChars: Int = MAX_QUEUE_CHARS,
    ): List<String> {
        if (maxRecords <= 0) return emptyList()
        val kept = ArrayList<String>(minOf(maxRecords, 64))
        var total = 0
        var i = lines.size - 1
        while (i >= 0 && kept.size < maxRecords) {
            val line = lines[i]
            i--
            if (line.isBlank() || line.length > MAX_RECORD_CHARS) continue
            val add = line.length + 1 // + separator
            if (total + add > maxTotalChars) break
            total += add
            kept.add(line)
        }
        kept.reverse()
        return kept
    }

    /** Restore side. Rejects an oversized blob wholesale, else returns the bounded newest-N lines.
     *  File callers MUST additionally bound the read itself with [MAX_QUEUE_BYTES] while consuming. */
    fun select(raw: String?, separator: String, maxRecords: Int): Bounded {
        if (raw.isNullOrBlank()) return Bounded(emptyList(), oversized = false)
        if (raw.length > MAX_QUEUE_CHARS) return Bounded(emptyList(), oversized = true)
        return Bounded(boundLines(raw.split(separator), maxRecords), oversized = false)
    }
}

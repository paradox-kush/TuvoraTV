package com.nuvio.tv.core.rec

/** Limits the complete serialized envelope, not the number/character length of its events. */
object RecBatchPolicy {
    const val MAX_BYTES = 60 * 1024 // Leave margin below the server's 64 KiB limit.
    fun <T> chunks(records: List<T>, bytes: (List<T>) -> Int): List<List<T>> {
        val chunks = mutableListOf<List<T>>()
        var chunk = mutableListOf<T>()
        for (record in records) {
            val next = chunk + record
            if (chunk.isNotEmpty() && (next.size > 50 || bytes(next) > MAX_BYTES)) {
                chunks += chunk.toList()
                chunk = mutableListOf()
            }
            chunk += record
        }
        if (chunk.isNotEmpty()) chunks += chunk.toList()
        return chunks
    }
    fun retryable(status: Int): Boolean = status == 408 || status == 429 || status >= 500
}

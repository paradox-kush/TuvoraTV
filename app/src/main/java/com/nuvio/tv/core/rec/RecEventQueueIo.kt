package com.nuvio.tv.core.rec

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads up to [maxBytes] of UTF-8 from [input], returning null if the content exceeds [maxBytes].
 * The limit is checked as the stream is consumed, so an oversized or growing source is rejected
 * before it fully lands in memory. Closes [input]. Testable with any InputStream.
 */
internal fun readBoundedUtf8(input: InputStream, maxBytes: Int): String? {
    input.use { ins ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        val cap = maxBytes.toLong()
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}

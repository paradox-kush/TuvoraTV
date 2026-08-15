package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.EpgProgramme
import com.squareup.moshi.JsonReader
import okio.BufferedSource
import java.util.Base64

/**
 * Reads `get_simple_data_table` — the only Xtream endpoint that returns PAST programmes, and
 * therefore the one that makes "days back" real. `get_short_epg` answers now+next and cannot power
 * a replay strip.
 *
 * Streamed one row at a time, for the same reason the catalog index is: a busy channel's week is a
 * large body, and the XMLTV out-of-memory crash was caused by holding a guide response in the heap.
 * Rows outside the parse window are dropped HERE, before a programme object exists, so a panel that
 * keeps a month costs us a week's worth of objects and no more.
 */
internal object XtreamSimpleDataTable {

    /**
     * Parses [source] into [sink], returning how many rows were kept.
     *
     * Rows are stored under [channelId] regardless of what their own `channel_id` says — the refill
     * is per-channel by contract, and panels are inconsistent about which id they echo back.
     * A body that is not a listings payload (a panel erroring mid-session answers with
     * `{"user_info":…}`) yields zero rather than throwing: the caller's fetch gate has already been
     * stamped, and a thrown parse would look like a crash rather than an empty guide.
     */
    fun parseInto(
        source: BufferedSource,
        channelId: String,
        nowMs: Long,
        catchUpDays: Int,
        sink: (EpgProgramme) -> Unit,
    ): Int = runCatching {
        JsonReader.of(source).use { reader ->
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return@use 0
            var kept = 0
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "epg_listings") {
                    reader.skipValue()
                    continue
                }
                kept += readListings(reader, channelId, nowMs, catchUpDays, sink)
            }
            reader.endObject()
            kept
        }
    }.getOrDefault(0)

    /**
     * The listings themselves. Normally an array; a handful of panels key them by index instead,
     * which is one character of difference between a full guide and an empty one.
     */
    private fun readListings(
        reader: JsonReader,
        channelId: String,
        nowMs: Long,
        catchUpDays: Int,
        sink: (EpgProgramme) -> Unit,
    ): Int {
        var kept = 0
        fun row() {
            readRow(reader, channelId, nowMs, catchUpDays)?.let { sink(it); kept++ }
        }
        when (reader.peek()) {
            JsonReader.Token.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) row()
                reader.endArray()
            }
            JsonReader.Token.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) { reader.nextName(); row() }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
        return kept
    }

    /** One listing. Null = malformed or outside the window; either way the body keeps going. */
    private fun readRow(
        reader: JsonReader,
        channelId: String,
        nowMs: Long,
        catchUpDays: Int,
    ): EpgProgramme? {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var title: String? = null
        var desc: String? = null
        var start = 0L
        var stop = 0L
        var archive = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title" -> title = reader.flexString()
                "description", "descr" -> desc = reader.flexString()
                "start_timestamp" -> start = reader.flexLong() ?: 0L
                "stop_timestamp", "end_timestamp" -> stop = reader.flexLong() ?: 0L
                "has_archive" -> archive = reader.flexLong()?.toInt() ?: 0
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val startMs = toMillis(start)
        val endMs = toMillis(stop)
        if (!CatchUpEpgWindow.keeps(startMs, endMs, nowMs, catchUpDays)) return null
        // Decoded ONCE here: the guide reads these rows back from SQLite, so a second decode per
        // repaint would be paid on every scroll.
        val decodedDesc = decodeText(desc).takeIf { it.isNotBlank() }
        return EpgProgramme(
            channelId = channelId,
            startMs = startMs,
            endMs = endMs,
            title = decodeText(title),
            desc = decodedDesc,
            hasArchive = archive > 0,
        )
    }

    /**
     * Xtream sends unix SECONDS (as strings). A panel sending milliseconds would otherwise put every
     * programme in the year 52,000 and show an empty guide, so both shapes are normalized — a value
     * already past the plausible-seconds ceiling is read as milliseconds.
     */
    private fun toMillis(raw: Long): Long =
        if (raw > MILLIS_THRESHOLD) raw else raw * 1000L

    /**
     * Xtream base64-encodes EPG text — but not every panel does, and decoding blindly is worse than
     * not decoding at all: a short plain title ("News", "Film") is valid base64 and comes back as
     * mojibake. So a decode is accepted only when the input is base64-SHAPED and what came out is
     * readable text; anything else keeps the original string.
     */
    fun decodeText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        if (trimmed.length % 4 != 0 || !trimmed.all { it.isBase64Char() }) return raw
        val bytes = runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull() ?: return raw
        if (bytes.isEmpty()) return raw
        val decoded = String(bytes, Charsets.UTF_8)
        return if (decoded.isReadableText()) decoded else raw
    }

    private fun Char.isBase64Char(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '+' || this == '/' || this == '='

    /**
     * Text a viewer could read: no C0 control characters beyond the whitespace ones, and no U+FFFD,
     * which is what non-UTF-8 bytes turn into. This is the test that separates a real base64 title
     * from four letters that merely looked like one.
     */
    private fun String.isReadableText(): Boolean =
        isNotEmpty() && none { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' } && none { it == '�' }

    /** Panels disagree on the encoding of every field, so every read tolerates all of them. */
    private fun JsonReader.flexLong(): Long? = when (peek()) {
        JsonReader.Token.NUMBER -> nextLong()
        JsonReader.Token.STRING -> nextString().trim().toLongOrNull()
        JsonReader.Token.BOOLEAN -> if (nextBoolean()) 1L else 0L
        JsonReader.Token.NULL -> nextNull()
        else -> { skipValue(); null }
    }

    private fun JsonReader.flexString(): String? = when (peek()) {
        JsonReader.Token.STRING -> nextString()
        JsonReader.Token.NUMBER -> nextString()
        JsonReader.Token.NULL -> nextNull()
        else -> { skipValue(); null }
    }

    /** Above this a "seconds" value is not a date any panel means — it is milliseconds. */
    private const val MILLIS_THRESHOLD = 100_000_000_000L
}

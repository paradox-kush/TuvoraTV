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
     *
     * Epoch-skew correction ([XtreamEpochSkew]) happens HERE, before the keep-window: [manualOffsetMs]
     * (the per-playlist setting) wins outright; otherwise the response votes the liar equality and a
     * proven liar's epochs get [clockPairOffsetMs] subtracted. While the vote is open, rows wait in a
     * small bounded buffer — the window has to judge CORRECTED epochs, or a shifted panel's forward
     * edge would be refused at parse.
     */
    fun parseInto(
        source: BufferedSource,
        channelId: String,
        nowMs: Long,
        catchUpDays: Int,
        manualOffsetMs: Long? = null,
        clockPairOffsetMs: Long? = null,
        sink: (EpgProgramme) -> Unit,
    ): Int = runCatching {
        JsonReader.of(source).use { reader ->
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return@use 0
            val emitter = SkewCorrectingEmitter(channelId, nowMs, catchUpDays, manualOffsetMs, clockPairOffsetMs, sink)
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "epg_listings") {
                    reader.skipValue()
                    continue
                }
                readListings(reader, emitter)
            }
            reader.endObject()
            emitter.finish()
        }
    }.getOrDefault(0)

    /**
     * The listings themselves. Normally an array; a handful of panels key them by index instead,
     * which is one character of difference between a full guide and an empty one.
     */
    private fun readListings(reader: JsonReader, emitter: SkewCorrectingEmitter) {
        fun row() {
            readRow(reader)?.let(emitter::offer)
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
    }

    /** One listing's raw fields. Null only for a non-object element; junk fields ride through. */
    private fun readRow(reader: JsonReader): RawRow? {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var title: String? = null
        var desc: String? = null
        var startText: String? = null
        var start = 0L
        var stop = 0L
        var archive = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title" -> title = reader.flexString()
                "description", "descr" -> desc = reader.flexString()
                // The wall-clock start STRING beside the epoch — never stored, it only votes.
                "start" -> startText = reader.flexString()
                "start_timestamp" -> start = reader.flexLong() ?: 0L
                "stop_timestamp", "end_timestamp" -> stop = reader.flexLong() ?: 0L
                "has_archive" -> archive = reader.flexLong()?.toInt() ?: 0
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return RawRow(title, desc, startText, start, stop, archive)
    }

    /** One row as the panel sent it, before correction/window/decoding. */
    private class RawRow(
        val title: String?,
        val desc: String?,
        val startText: String?,
        val startRaw: Long,
        val stopRaw: Long,
        val archive: Int,
    )

    /**
     * Applies [XtreamEpochSkew] per response, streaming. With a manual offset the vote never opens
     * and nothing is buffered. Otherwise rows wait (bounded by [XtreamEpochSkew.PENDING_ROW_CAP],
     * resolved early at [XtreamEpochSkew.SAMPLE_VOTE_CAP] votes) until the verdict fixes the
     * offset, then flush in arrival order; later rows stream straight through. The buffer holds at
     * most a few hundred small objects — the response body itself still never lands in the heap.
     */
    private class SkewCorrectingEmitter(
        private val channelId: String,
        private val nowMs: Long,
        private val catchUpDays: Int,
        manualOffsetMs: Long?,
        private val clockPairOffsetMs: Long?,
        private val sink: (EpgProgramme) -> Unit,
    ) {
        private var resolvedOffsetMs: Long? = manualOffsetMs
        private val pending = ArrayList<RawRow>()
        private var liarVotes = 0
        private var honestVotes = 0
        private var kept = 0

        fun offer(row: RawRow) {
            val resolved = resolvedOffsetMs
            if (resolved != null) {
                emit(row, resolved)
                return
            }
            when (XtreamEpochSkew.vote(row.startText, row.startRaw)) {
                true -> liarVotes++
                false -> honestVotes++
                null -> Unit
            }
            pending.add(row)
            if (liarVotes + honestVotes >= XtreamEpochSkew.SAMPLE_VOTE_CAP ||
                pending.size >= XtreamEpochSkew.PENDING_ROW_CAP
            ) {
                resolveAndFlush()
            }
        }

        fun finish(): Int {
            if (resolvedOffsetMs == null) resolveAndFlush()
            return kept
        }

        private fun resolveAndFlush() {
            val offset = XtreamEpochSkew.effectiveOffsetMs(
                null,
                XtreamEpochSkew.verdict(liarVotes, honestVotes),
                clockPairOffsetMs,
            )
            resolvedOffsetMs = offset
            pending.forEach { emit(it, offset) }
            pending.clear()
        }

        /** The pre-correction tail of the old readRow: window on corrected epochs, decode once, sink. */
        private fun emit(row: RawRow, offsetMs: Long) {
            // Only REAL epochs are shifted: an absent timestamp parses to 0 and a "corrected" 0
            // would be negative garbage nothing downstream could recognise as absent.
            val startMs = toMillis(row.startRaw).let { if (row.startRaw > 0) it + offsetMs else it }
            val endMs = toMillis(row.stopRaw).let { if (row.stopRaw > 0) it + offsetMs else it }
            if (!CatchUpEpgWindow.keeps(startMs, endMs, nowMs, catchUpDays)) return
            // Decoded ONCE here: the guide reads these rows back from SQLite, so a second decode per
            // repaint would be paid on every scroll.
            val decodedDesc = decodeText(row.desc).takeIf { it.isNotBlank() }
            sink(
                EpgProgramme(
                    channelId = channelId,
                    startMs = startMs,
                    endMs = endMs,
                    title = decodeText(row.title),
                    desc = decodedDesc,
                    hasArchive = row.archive > 0,
                )
            )
            kept++
        }
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

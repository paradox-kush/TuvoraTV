package com.nuvio.tv.core.iptv.match

import com.squareup.moshi.JsonReader
import okio.BufferedSource

/**
 * Reads a `get_vod_streams` / `get_series` array straight into [IndexedItem]s, one object
 * at a time.
 *
 * The index path used to go through the DTO list and then the `XtreamMovie` list before
 * reaching [IndexedItem], so a 175k-title panel held three full copies of the catalog —
 * and the middle one carried a per-item stream URL that the index has no field for. On a
 * TV stick (192 MB heap ceiling, ~60-90 MB already resident) that peak is what the
 * lowmemorykiller was reacting to. Here nothing but the growing [IndexedItem] list is
 * retained: each JSON object is decoded, reduced to the six fields the index stores, and
 * dropped.
 *
 * Numeric fields tolerate number | "string" | bool | null for the same reason
 * [com.nuvio.tv.data.remote.dto.FlexIntAdapter] does — panels disagree on the encoding of
 * the same field.
 */
internal object XtreamCatalogIndexParser {

    fun parseVod(source: BufferedSource): List<IndexedItem> =
        parseArray(source) { readVodItem(it) }

    fun parseSeries(source: BufferedSource): List<IndexedItem> =
        parseArray(source) { readSeriesItem(it) }

    private fun readVodItem(r: JsonReader): IndexedItem? {
        var sid: Int? = null
        var name: String? = null
        var tmdb: Int? = null
        var ext: String? = null
        var poster: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "stream_id" -> sid = r.flexInt()
                "name" -> name = r.flexString()
                "tmdb" -> tmdb = r.flexInt()
                "container_extension" -> ext = r.flexString()
                "stream_icon" -> poster = r.flexString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return sid?.let {
            val title = name.orEmpty()
            IndexedItem(
                sid = it,
                name = title,
                year = TitleNormalizer.yearOf(title),
                tmdb = tmdb?.takeIf { t -> t > 0 },
                ext = ext?.takeIf { e -> e.isNotBlank() },
                poster = poster?.takeIf { p -> p.isNotBlank() },
            )
        }
    }

    private fun readSeriesItem(r: JsonReader): IndexedItem? {
        var sid: Int? = null
        var name: String? = null
        var tmdb: Int? = null
        var poster: String? = null
        var preferredReleaseDate: String? = null
        var fallbackReleaseDate: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "series_id" -> sid = r.flexInt()
                "name" -> name = r.flexString()
                "tmdb" -> tmdb = r.flexInt()
                "cover" -> poster = r.flexString()
                // Panels can send both spellings. Always read each value before choosing
                // one: putting flexString() on the right of ?: leaves the reader sitting
                // on an unconsumed value when releaseDate was already populated.
                // XUI's camelCase field wins regardless of the fields' JSON order.
                "releaseDate" -> r.flexString().let { value ->
                    if (value != null) preferredReleaseDate = value
                }
                "release_date" -> r.flexString().let { value ->
                    if (value != null) fallbackReleaseDate = value
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return sid?.let {
            val title = name.orEmpty()
            IndexedItem(
                sid = it,
                name = title,
                year = (preferredReleaseDate ?: fallbackReleaseDate)
                    ?.trim()
                    ?.take(4)
                    ?.toIntOrNull()
                    ?: TitleNormalizer.yearOf(title),
                tmdb = tmdb?.takeIf { t -> t > 0 },
                ext = null,
                poster = poster?.takeIf { p -> p.isNotBlank() },
            )
        }
    }

    /**
     * [parseVod]/[parseSeries], streamed: each row goes to [sink] and is then garbage — the
     * catalog never exists in heap as one list (~40-50 MB of IndexedItem on a 175k panel, on
     * exactly the devices whose heap can't take it). Returns the delivered count; the same
     * mid-array/HTTP failure modes throw exactly as in list mode, so a truncated body still
     * reads as a failed fetch, never as a small catalog.
     */
    fun parseVodInto(source: BufferedSource, sink: (IndexedItem) -> Unit): Int =
        parseArrayInto(source, sink) { readVodItem(it) }

    fun parseSeriesInto(source: BufferedSource, sink: (IndexedItem) -> Unit): Int =
        parseArrayInto(source, sink) { readSeriesItem(it) }

    private inline fun parseArray(
        source: BufferedSource,
        readItem: (JsonReader) -> IndexedItem?,
    ): List<IndexedItem> = JsonReader.of(source).use { reader ->
        // A panel that errors mid-session answers with an object ({"user_info":…}) where the
        // catalog should be. Treat that as a failed fetch so the caller backs off, rather
        // than as an empty catalog, which would look like "the provider has no movies".
        if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
            error("expected a catalog array, got ${reader.peek()}")
        }
        val out = ArrayList<IndexedItem>()
        reader.beginArray()
        while (reader.hasNext()) {
            readItem(reader)?.let(out::add)
        }
        reader.endArray()
        out
    }

    private inline fun parseArrayInto(
        source: BufferedSource,
        sink: (IndexedItem) -> Unit,
        readItem: (JsonReader) -> IndexedItem?,
    ): Int = JsonReader.of(source).use { reader ->
        if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
            error("expected a catalog array, got ${reader.peek()}")
        }
        var n = 0
        reader.beginArray()
        while (reader.hasNext()) {
            readItem(reader)?.let { sink(it); n++ }
        }
        reader.endArray()
        n
    }

    private fun JsonReader.flexInt(): Int? = when (peek()) {
        JsonReader.Token.NUMBER -> nextInt()
        JsonReader.Token.STRING -> nextString().trim().toIntOrNull()
        JsonReader.Token.BOOLEAN -> if (nextBoolean()) 1 else 0
        JsonReader.Token.NULL -> nextNull()
        else -> { skipValue(); null }
    }

    private fun JsonReader.flexString(): String? = when (peek()) {
        JsonReader.Token.STRING -> nextString()
        JsonReader.Token.NUMBER -> nextString()
        JsonReader.Token.NULL -> nextNull()
        else -> { skipValue(); null }
    }
}

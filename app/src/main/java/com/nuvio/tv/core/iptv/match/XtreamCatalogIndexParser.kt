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
        parseArray(source) { r ->
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
            sid?.let {
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

    fun parseSeries(source: BufferedSource): List<IndexedItem> =
        parseArray(source) { r ->
            var sid: Int? = null
            var name: String? = null
            var tmdb: Int? = null
            var poster: String? = null
            var releaseDate: String? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "series_id" -> sid = r.flexInt()
                    "name" -> name = r.flexString()
                    "tmdb" -> tmdb = r.flexInt()
                    "cover" -> poster = r.flexString()
                    // panels send both spellings; releaseDate is the one XUI populates
                    "releaseDate" -> releaseDate = r.flexString() ?: releaseDate
                    "release_date" -> releaseDate = releaseDate ?: r.flexString()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            sid?.let {
                val title = name.orEmpty()
                IndexedItem(
                    sid = it,
                    name = title,
                    year = releaseDate?.trim()?.take(4)?.toIntOrNull() ?: TitleNormalizer.yearOf(title),
                    tmdb = tmdb?.takeIf { t -> t > 0 },
                    ext = null,
                    poster = poster?.takeIf { p -> p.isNotBlank() },
                )
            }
        }

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

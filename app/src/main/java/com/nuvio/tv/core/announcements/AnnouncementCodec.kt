package com.nuvio.tv.core.announcements

import com.nuvio.tv.domain.model.Announcement
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

/**
 * JSON for the `get_app_announcements` RPC body and the on-device cache (same shape).
 * A malformed row is skipped rather than failing the whole list; a body that isn't an array
 * throws so the caller keeps its previous cache.
 */
object AnnouncementCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun decode(body: String): List<Announcement> {
        val array: JsonArray = json.parseToJsonElement(body).jsonArray
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(Announcement.serializer(), element) }.getOrNull()
        }
    }

    fun encode(items: List<Announcement>): String =
        json.encodeToString(ListSerializer(Announcement.serializer()), items)
}

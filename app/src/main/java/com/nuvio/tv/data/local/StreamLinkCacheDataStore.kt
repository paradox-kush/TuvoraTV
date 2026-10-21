package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class CachedStreamLink(
    val url: String,
    val streamName: String,
    val headers: Map<String, String>,
    val cachedAtMs: Long,
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String>? = null,
    val bingeGroup: String? = null,
    val contentLanguage: String? = null,
    val year: String? = null,
    val deviceSourceKind: String? = null,
    val deviceSourceId: String? = null,
) {
    // Legacy entries have no provenance: discover again once instead of bypassing a device choice.
    val availableOnDevice: Boolean get() = when (val kind = deviceSourceKind) {
        "unmanaged" -> true
        null -> false
        else -> deviceSourceId?.let { com.nuvio.tv.core.sources.DeviceSources.allowsSourceId(it) } == true
    }
}

@Singleton
class StreamLinkCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "stream_link_cache"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    suspend fun save(
        contentKey: String,
        url: String,
        streamName: String,
        headers: Map<String, String>?,
        filename: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        sources: List<String>? = null,
        bingeGroup: String? = null,
        contentLanguage: String? = null,
        year: String? = null,
        deviceSourceKind: String? = null,
        deviceSourceId: String? = null,
    ) {
        val payload = JSONObject().apply {
            put("url", url)
            put("streamName", streamName)
            put("deviceSourceKind",deviceSourceKind)
            deviceSourceId?.let { put("deviceSourceId",it) }
            put("cachedAtMs", System.currentTimeMillis())
            put("headers", JSONObject(headers ?: emptyMap<String, String>()))
            put("filename", filename)
            put("videoHash", videoHash)
            videoSize?.let { put("videoSize", it) }
            infoHash?.let { put("infoHash", it) }
            fileIdx?.let { put("fileIdx", it) }
            sources?.let { put("sources", JSONArray(it)) }
            bingeGroup?.let { put("bingeGroup", it) }
            contentLanguage?.let { put("contentLanguage", it) }
            year?.let { put("year", it) }
        }.toString()

        store().edit { prefs ->
            val key = cachePrefKey(contentKey)
            // Hash observers update the same playback URL without knowing its source. Preserve
            // existing opaque provenance only for that exact URL; otherwise rediscover next time.
            val previous = prefs[key]?.let { runCatching { JSONObject(it) }.getOrNull() }
            val next = JSONObject(payload)
            if (deviceSourceKind == null && previous != null && previous.optString("url") == url &&
                (url.isNotBlank() || (infoHash != null && previous.optString("infoHash") == infoHash))) {
                previous.optString("deviceSourceKind", "").takeIf { it.isNotBlank() && it != "null" }?.let { next.put("deviceSourceKind",it) }
                previous.optString("deviceSourceId", "").takeIf { it.isNotBlank() && it != "null" }?.let { next.put("deviceSourceId",it) }
            }
            prefs[key] = next.toString()
        }
    }

    suspend fun getValid(contentKey: String, maxAgeMs: Long): CachedStreamLink? {
        if (maxAgeMs <= 0L) return null

        val key = cachePrefKey(contentKey)
        val raw = store().data.first()[key] ?: return null

        val parsed = runCatching {
            val json = JSONObject(raw)
            val cachedAtMs = json.optLong("cachedAtMs", 0L)
            val age = System.currentTimeMillis() - cachedAtMs
            if (cachedAtMs <= 0L || age > maxAgeMs) return@runCatching null

            val headersJson = json.optJSONObject("headers")
            val headers = buildMap {
                headersJson?.keys()?.forEach { headerKey ->
                    put(headerKey, headersJson.optString(headerKey, ""))
                }
            }.filterValues { it.isNotEmpty() }

            val url = json.optString("url", "")
            val streamName = json.optString("streamName", "")
            val infoHash = json.optString("infoHash", "").ifBlank { null }
            // Accept entry if it has either a real URL (HTTP stream) or an
            // infoHash (torrent stream — URL is re-resolved on each playback).
            if (streamName.isBlank() || (url.isBlank() && infoHash == null)) return@runCatching null

            val sourcesJson = json.optJSONArray("sources")
            val sources = sourcesJson?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.isNotEmpty() }
                }
            }?.takeIf { it.isNotEmpty() }

            CachedStreamLink(
                url = url,
                streamName = streamName,
                headers = headers,
                cachedAtMs = cachedAtMs,
                filename = json.optString("filename", "").ifBlank { null },
                videoHash = json.optString("videoHash", "").ifBlank { null },
                videoSize = json.optLong("videoSize", -1L).takeIf { it >= 0L },
                infoHash = infoHash,
                fileIdx = if (json.has("fileIdx")) json.optInt("fileIdx", -1).takeIf { it >= 0 } else null,
                sources = sources,
                bingeGroup = json.optString("bingeGroup", "").ifBlank { null },
                contentLanguage = json.optString("contentLanguage", "").ifBlank { null },
                year = json.optString("year", "").ifBlank { null },
                deviceSourceKind = json.optString("deviceSourceKind", "").ifBlank { null },
                deviceSourceId = json.optString("deviceSourceId", "").ifBlank { null }
            )
        }.getOrNull()

        if (parsed == null) {
            store().edit { mutablePrefs ->
                mutablePrefs.remove(key)
            }
        }

        return parsed
    }

    private fun cachePrefKey(contentKey: String): Preferences.Key<String> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(contentKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return stringPreferencesKey("stream_link_$digest")
    }
}

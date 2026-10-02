package com.nuvio.tv.core.iptv

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 2 — which playlists are managed by a provider, per account + profile, keyed by `playlist_key`.
 * A playlist is managed iff its key is in the map (the pulled rows carry no managed flag).
 *
 * Persisted so the "locked edit" guard still holds offline and right after a cold start, before the
 * first pull has refreshed it. It holds provider NAMES and public support contacts only — no code, no
 * credentials. It is refreshed only through [ManagedRefreshPolicy] (after a pull that returned
 * playlists), never on a timer; the entry for another user is never visible (keyed by user id).
 */
@Singleton
class ManagedInfoStore(private val persistence: Persistence) {

    interface Persistence {
        fun read(): String?
        fun write(value: String)
    }

    @Inject constructor(@ApplicationContext context: Context) : this(PrefsPersistence(context))

    private class PrefsPersistence(context: Context) : Persistence {
        private val prefs = context.getSharedPreferences("managed_playlists_v1", Context.MODE_PRIVATE)
        override fun read(): String? = prefs.getString("state", null)
        override fun write(value: String) { prefs.edit().putString("state", value).apply() }
    }

    /** One user+profile's cached map and the playlist-sync revision it was fetched at. */
    data class Entry(val infos: Map<String, ManagedPlaylistInfo>, val revision: Long?, val fetchedAtMs: Long = 0L)

    private val state = MutableStateFlow(load())
    val entries: StateFlow<Map<String, Entry>> = state.asStateFlow()

    private fun slot(userId: String, profileId: Int) = "$userId:$profileId"

    fun entry(userId: String, profileId: Int): Entry? = state.value[slot(userId, profileId)]

    fun infos(userId: String, profileId: Int): Map<String, ManagedPlaylistInfo> =
        entry(userId, profileId)?.infos.orEmpty()

    /** The map for [userId]'s [profileId] as a flow (empty until loaded). */
    fun infosFlow(userId: String, profileId: Int): Flow<Map<String, ManagedPlaylistInfo>> =
        state.map { it[slot(userId, profileId)]?.infos.orEmpty() }.distinctUntilChanged()

    @Synchronized
    fun replace(userId: String, profileId: Int, list: List<ManagedPlaylistInfo>, revision: Long?, nowMs: Long = System.currentTimeMillis()) {
        val next = state.value + (slot(userId, profileId) to Entry(list.associateBy { it.playlistKey }, revision, nowMs))
        state.value = next
        persistence.write(encode(next))
    }

    @Synchronized
    fun clearAll() {
        state.value = emptyMap()
        persistence.write(encode(emptyMap()))
    }

    private fun load(): Map<String, Entry> = decode(runCatching { persistence.read() }.getOrNull())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(map: Map<String, Entry>): String = buildJsonArray {
            for ((slot, entry) in map) add(buildJsonObject {
                put("slot", slot)
                entry.revision?.let { put("revision", it) }
                put("fetched_at", entry.fetchedAtMs)
                put("infos", buildJsonArray {
                    for (i in entry.infos.values) add(buildJsonObject {
                        put("playlist_key", i.playlistKey)
                        put("provider_name", i.providerName)
                        i.serviceName?.let { put("service_name", it) }
                        i.serviceUpdatedAt?.let { put("service_updated_at", it) }
                        put("support", buildJsonObject {
                            i.support.whatsapp?.let { put("whatsapp", it) }
                            i.support.telegram?.let { put("telegram", it) }
                            i.support.email?.let { put("email", it) }
                            i.support.website?.let { put("website", it) }
                        })
                    })
                })
            })
        }.toString()

        fun decode(raw: String?): Map<String, Entry> {
            if (raw.isNullOrBlank()) return emptyMap()
            val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyMap()
            return root.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val slot = (o["slot"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val rev = (o["revision"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()
                val infos = ManagedPlaylistInfo.listFromJson(o["infos"] as JsonElement?)
                val at = (o["fetched_at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                slot to Entry(infos.associateBy { it.playlistKey }, rev, at)
            }.toMap()
        }
    }
}

/**
 * Step 2 — when `get_managed_playlists` is called. ONLY after a playlist pull that returned at least
 * one playlist, never for a profile with zero playlists, never on a timer; and, to stay delta-shaped,
 * not again for an unchanged playlist revision once a map for it is cached (a provider's edit, a redeem
 * and a detach all bump the revision, so every change that could alter the map is still caught).
 */
object ManagedRefreshPolicy {
    fun shouldRefresh(
        pullSucceeded: Boolean,
        playlistCount: Int,
        revision: Long?,
        cachedRevision: Long?,
        hasCache: Boolean,
        cacheAgeMs: Long = 0L,
    ): Boolean = when {
        !pullSucceeded -> false
        playlistCount < 1 -> false
        !hasCache -> true
        cacheAgeMs >= MAX_AGE_MS -> true
        revision == null || cachedRevision == null -> true
        else -> revision != cachedRevision
    }

    /** A provider's contact edit does not bump the playlist revision, so a cache older than this is re-read at the next pull. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
}

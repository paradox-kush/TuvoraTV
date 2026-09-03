package com.nuvio.tv.core.iptv.overlay

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TV twin of NuvioMobile's IptvOverlayRepository: holds the active profile's overlay snapshot the guide
 * read-layer applies, exposes the D-pad hide/pin intents, and syncs (pull the website's edits, push this
 * device's) through the shared overlay RPCs. Everything is keyed on canon-v1 identity, so ids match.
 */
@Singleton
class IptvOverlayRepository @Inject constructor(
    private val db: IptvOverlayDb,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(OverlaySnapshot())
    val uiState: StateFlow<OverlaySnapshot> = _uiState.asStateFlow()

    private fun profile() = profileManager.activeProfileId.value
    private fun now() = System.currentTimeMillis()

    // The personalization overlay is an OPTIONAL layer: if its DB read, sync, or apply fails, the guide
    // must still render (unfiltered) — never crash. Every fire-and-forget op runs through here so a
    // throw can't reach the scope's uncaught handler and kill the app (that was the v1.6.0 crash: an
    // unguarded ensureLoaded()/db.snapshot() closed the app the moment the user opened IPTV).
    private fun launchSafely(what: String, block: suspend () -> Unit) =
        scope.launch { runCatching { block() }.onFailure { Log.w("IptvOverlay", "overlay $what failed: ${it.message}", it) } }

    fun ensureLoaded() = launchSafely("ensureLoaded") { _uiState.value = db.snapshot(profile()) }

    fun toggleChannelHidden(entityId: String, playlistId: String?) {
        val p = profile()
        val cur = _uiState.value.channels[entityId] ?: ChannelOverlay()
        launchSafely("toggleChannelHidden") {
            db.setChannel(p, entityId, playlistId, cur.copy(hidden = !cur.hidden), now())
            _uiState.value = db.snapshot(p)
            push(p)
        }
    }

    fun setChannelPinned(entityId: String, playlistId: String?, pinned: Boolean) {
        val p = profile()
        val cur = _uiState.value.channels[entityId] ?: ChannelOverlay()
        launchSafely("setChannelPinned") {
            db.setChannel(p, entityId, playlistId, cur.copy(pinned = pinned), now())
            _uiState.value = db.snapshot(p)
            push(p)
        }
    }

    @Serializable
    private data class DeltaRow(
        @SerialName("event_id") val eventId: Long,
        val operation: String,
        val kind: String,
        val okey: String,
        @SerialName("playlist_id") val playlistId: String? = null,
        val value: JsonObject = JsonObject(emptyMap()),
        @SerialName("updated_at") val updatedAt: Long = 0,
    )

    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.content?.let { it == "true" } ?: false
    private fun JsonObject.intOrNull(k: String) = (this[k] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.strOrNull(k: String) = (this[k] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    /** Pull one profile's overlay edits from the server and apply them. Called from the sync loop / guide open. */
    suspend fun pullForProfile(profileId: Int): Boolean {
        var since = db.getCursor(profileId)
        var changed = false
        while (true) {
            val rows = postgrest.rpc(
                "sync_pull_iptv_overlay_delta",
                buildJsonObject { put("p_profile_id", profileId); put("p_since_event_id", since); put("p_limit", 500) },
            ).decodeList<DeltaRow>()
            if (rows.isEmpty()) break
            for (r in rows) {
                since = maxOf(since, r.eventId)
                val deleted = r.operation == "delete"
                val v = r.value
                when (r.kind) {
                    "channel" -> db.setChannel(profileId, r.okey, r.playlistId, ChannelOverlay(v.bool("hidden"), v.bool("pinned"), v.intOrNull("position"), v.strOrNull("rename")), r.updatedAt, deletedOverride = deleted)
                    "category" -> db.setCategory(profileId, r.playlistId ?: continue, v.strOrNull("content_type") ?: "live", r.okey, CategoryOverlay(v.bool("hidden"), v.bool("pinned"), v.intOrNull("position"), v.strOrNull("rename")), r.updatedAt, deletedOverride = deleted)
                    "group" -> db.applyRemoteGroup(profileId, r.okey, r.playlistId, v.strOrNull("content_type") ?: "live", v.strOrNull("name") ?: "", v.intOrNull("position") ?: 0, r.updatedAt, deleted)
                    "member" -> r.okey.split("|", limit = 2).takeIf { it.size == 2 }?.let { db.applyRemoteMember(profileId, it[0], it[1], v.intOrNull("position") ?: 0, r.updatedAt, deleted) }
                }
                changed = true
            }
            db.setCursor(profileId, since)
            if (rows.size < 500) break
        }
        if (changed && profileId == profile()) _uiState.value = db.snapshot(profileId)
        return changed
    }

    fun pull() { scope.launch { runCatching { pullForProfile(profile()) } } }

    private fun push(profileId: Int) {
        scope.launch {
            runCatching {
                val rows = db.channelRowsForPush(profileId)
                val upserts = rows.filter { !it.deleted }
                val deletes = rows.filter { it.deleted }
                if (upserts.isNotEmpty()) postgrest.rpc("sync_push_iptv_overlay", buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_items", buildJsonArray {
                        upserts.forEach { row ->
                            add(buildJsonObject {
                                put("kind", row.kind); put("okey", row.okey)
                                if (row.playlistId != null) put("playlist_id", row.playlistId)
                                put("value", json.parseToJsonElement(row.valueJson)); put("updated_at", row.updatedAt)
                            })
                        }
                    })
                })
                if (deletes.isNotEmpty()) postgrest.rpc("sync_delete_iptv_overlay", buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_keys", buildJsonArray { deletes.forEach { add(buildJsonObject { put("kind", it.kind); put("okey", it.okey) }) } })
                })
            }
        }
    }
}

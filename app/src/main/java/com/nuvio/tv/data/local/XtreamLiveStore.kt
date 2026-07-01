package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A live channel we can replay later (favorited or recently watched). Carries its own
 *  stream URL because the `xtream:` id can't be parsed back into one (accountId has ':'). */
data class LiveChannelRef(
    val id: String,
    val name: String,
    val logo: String?,
    val streamUrl: String,
    val playedAt: Long? = null
)

/**
 * Profile-scoped persistence for live channels that need to outlive a browse session:
 * favorites (so the platform Library can play them on click) and recently-watched
 * (so the hub can show a "Recent Channels" row). Mirrors [XtreamAccountStore].
 *
 * ponytail: one flat list capped at 200, LRU-trimmed. Plenty for personal use; a real
 * DB is the upgrade path only if someone favorites hundreds of channels.
 */
@Singleton
class XtreamLiveStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val key = stringPreferencesKey("xtream_live_channels")
    private val scope = CoroutineScope(SupervisorJob())

    /** In-memory mirror for synchronous url lookup from the Library click router. */
    private val mirror = ConcurrentHashMap<String, LiveChannelRef>()

    private fun store(pid: Int = profileManager.activeProfileId.value) = factory.get(pid, FEATURE)

    private val all: Flow<List<LiveChannelRef>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> parse(prefs[key]) }
    }

    init {
        // Keep the mirror warm so urlFor() resolves without suspending.
        all.onEach { list ->
            mirror.clear()
            list.forEach { mirror[it.id] = it }
        }.launchIn(scope)
    }

    val recents: Flow<List<LiveChannelRef>> = all.map { list ->
        list.filter { it.playedAt != null }.sortedByDescending { it.playedAt }.take(RECENTS_LIMIT)
    }

    /** Synchronous resolution for replaying a favorited/recent channel by id. */
    fun urlFor(id: String): String? = mirror[id]?.streamUrl
    fun refFor(id: String): LiveChannelRef? = mirror[id]

    /** Persist a channel so it can be replayed later (favorite path). Preserves recency. */
    suspend fun remember(ref: LiveChannelRef) = upsert(ref, markPlayed = false)

    /** Record a channel as just-watched (recents + replayable). */
    suspend fun recordPlayed(ref: LiveChannelRef) = upsert(ref, markPlayed = true)

    private suspend fun upsert(ref: LiveChannelRef, markPlayed: Boolean) {
        store().edit { prefs ->
            val current = parse(prefs[key]).toMutableList()
            val existing = current.firstOrNull { it.id == ref.id }
            val playedAt = when {
                markPlayed -> System.currentTimeMillis()
                else -> existing?.playedAt
            }
            current.removeAll { it.id == ref.id }
            current.add(0, ref.copy(playedAt = playedAt))
            // LRU trim: keep the most recently touched (front = newest).
            val trimmed = current.take(MAX_CHANNELS)
            prefs[key] = gson.toJson(trimmed)
        }
    }

    private fun parse(json: String?): List<LiveChannelRef> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<LiveChannelRef>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val FEATURE = "xtream_accounts"   // reuse the IPTV datastore file
        private const val RECENTS_LIMIT = 20
        private const val MAX_CHANNELS = 200
    }
}

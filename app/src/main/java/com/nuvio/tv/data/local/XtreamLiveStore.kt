package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
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

/** URL-free persisted identity returned only by an explicit-profile read. */
internal data class StoredLiveChannelIdentity(
    val contentId: String,
    val title: String,
    val logo: String?,
) {
    override fun toString(): String =
        "StoredLiveChannelIdentity(hasLogo=${logo != null})"
}

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
    fun urlFor(id: String): String? = mirror[id]?.streamUrl?.takeIf(String::isNotBlank)
    fun refFor(id: String): LiveChannelRef? = mirror[id]

    /**
     * Clean URL-free lookup bound to exactly [profileId]. It never consults the active-profile
     * mirror and deliberately projects the stored row before it leaves this persistence owner.
     */
    internal suspend fun identityForProfile(
        profileId: Int,
        contentId: String,
    ): StoredLiveChannelIdentity? {
        require(profileId > 0) { "Profile id must be positive" }
        require(contentId.isNotBlank()) { "Live content id must not be blank" }
        val ref = parse(store(profileId).data.first()[key])
            .firstOrNull { it.id == contentId }
            ?: return null
        return StoredLiveChannelIdentity(
            contentId = ref.id,
            title = ref.name,
            logo = ref.logo,
        )
    }

    /** Persist a channel so it can be replayed later (favorite path). Preserves recency. */
    suspend fun remember(ref: LiveChannelRef) = upsert(ref, markPlayed = false)

    /** Record a channel as just-watched (recents + replayable). */
    suspend fun recordPlayed(ref: LiveChannelRef) = upsert(ref, markPlayed = true)

    /**
     * Clean URL-free history entrance. Existing legacy transport is retained for that exact row;
     * a new identity-only row deliberately stores a blank transport value.
     */
    suspend fun recordPlayedIdentity(
        contentId: String,
        title: String,
        logo: String?,
    ) {
        require(contentId.isNotBlank()) { "Live content id must not be blank" }
        upsert(
            ref = LiveChannelRef(contentId, title, logo, streamUrl = ""),
            markPlayed = true,
            preserveExistingTransport = true,
        )
    }

    /**
     * Profile-explicit clean playback history. This never follows the active-profile flow, even
     * when the viewer switches profiles while the write is suspended in DataStore.
     */
    internal suspend fun recordPlayedIdentityForProfile(
        profileId: Int,
        contentId: String,
        title: String,
        logo: String?,
    ) {
        require(profileId > 0) { "Profile id must be positive" }
        require(contentId.isNotBlank()) { "Live content id must not be blank" }
        upsertForProfile(
            profileId = profileId,
            ref = LiveChannelRef(contentId, title, logo, streamUrl = ""),
            markPlayed = true,
            preserveExistingTransport = true,
        )
    }

    /**
     * IPTV playlist edit: applies [transform] to every ref under the old account's id prefix
     * (rewrite id + rebuild streamUrl against the new server); a null transform drops them
     * instead (different playlist). The in-memory mirror refreshes via the flow collector.
     */
    suspend fun migrateAccount(oldPrefix: String, transform: ((LiveChannelRef) -> LiveChannelRef)?) {
        store().edit { prefs ->
            val current = parse(prefs[key])
            if (current.none { it.id.startsWith(oldPrefix) }) return@edit
            val updated = current.mapNotNull { ref ->
                when {
                    !ref.id.startsWith(oldPrefix) -> ref
                    transform == null -> null
                    else -> transform(ref)
                }
            }
            prefs[key] = gson.toJson(updated)
        }
    }

    private suspend fun upsert(
        ref: LiveChannelRef,
        markPlayed: Boolean,
        preserveExistingTransport: Boolean = false,
    ) = upsertInto(store(), ref, markPlayed, preserveExistingTransport)

    private suspend fun upsertForProfile(
        profileId: Int,
        ref: LiveChannelRef,
        markPlayed: Boolean,
        preserveExistingTransport: Boolean,
    ) = upsertInto(store(profileId), ref, markPlayed, preserveExistingTransport)

    private suspend fun upsertInto(
        targetStore: DataStore<Preferences>,
        ref: LiveChannelRef,
        markPlayed: Boolean,
        preserveExistingTransport: Boolean,
    ) {
        targetStore.edit { prefs ->
            val current = parse(prefs[key]).toMutableList()
            val existing = current.firstOrNull { it.id == ref.id }
            val playedAt = when {
                markPlayed -> System.currentTimeMillis()
                else -> existing?.playedAt
            }
            current.removeAll { it.id == ref.id }
            val storedRef = if (preserveExistingTransport) {
                ref.copy(streamUrl = existing?.streamUrl.orEmpty())
            } else {
                ref
            }
            current.add(0, storedRef.copy(playedAt = playedAt))
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

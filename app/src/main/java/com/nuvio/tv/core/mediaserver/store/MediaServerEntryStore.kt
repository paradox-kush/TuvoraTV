package com.nuvio.tv.core.mediaserver.store

import com.nuvio.tv.core.mediaserver.MsLog as Logger
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps
import com.nuvio.tv.core.mediaserver.api.MediaServerSyncSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Raw per-profile persistence of the entry list (a JSON blob). */
internal interface MediaServerEntriesPersistence {
    fun load(profileId: Int): String?
    fun save(profileId: Int, json: String)
    fun remove(profileId: Int)
}

internal object PlatformEntriesPersistence : MediaServerEntriesPersistence {
    override fun load(profileId: Int) = MediaServerStorage.loadEntriesJson(profileId)
    override fun save(profileId: Int, json: String) = MediaServerStorage.saveEntriesJson(profileId, json)
    override fun remove(profileId: Int) = MediaServerStorage.removeEntriesJson(profileId)
}

@Serializable
private data class EntriesBlob(val version: Int = 1, val entries: List<MediaServerEntry> = emptyList())

internal sealed interface AddResult {
    data object Added : AddResult
    /** An entry with this key already exists (the same server and the same user). */
    data class Duplicate(val existing: MediaServerEntry) : AddResult
    data object NotSaved : AddResult
}

internal sealed interface ReLoginResult {
    data class Updated(val entry: MediaServerEntry) : ReLoginResult
    /** The new user already has an entry for this server: the old one was folded into it (its saved data moved over). */
    data class MergedInto(val entry: MediaServerEntry) : ReLoginResult
    data object NotFound : ReLoginResult
    data object NotSaved : ReLoginResult
}

/**
 * The media-server entries of ONE profile at a time (design 5.3) - a server address + user per row, never a
 * credential. Loaded per profile (a switch reloads, so nothing leaks across profiles), mutated under a lock,
 * persisted before any sync intent is recorded (a failed write reports false and records nothing), and
 * guarded like the Xtream store: a blob this build cannot fully read is shown but never written back or
 * pushed (it could truncate the server's collection) until a clean load or a remote pull heals it.
 *
 * Saved data (Library / WatchProgress / Watched) of a removed or re-logged-in server is moved by id prefix
 * `ms:{type}:{machineId}:{userId}:` through [migrateSavedData] - the same fan-out an IPTV playlist edit uses.
 */
internal class MediaServerEntryStore(
    private val persistence: MediaServerEntriesPersistence,
    private val sink: () -> MediaServerSyncSink?,
    private val activeProfileId: () -> Int,
    private val migrateSavedData: (oldPrefix: String, newPrefix: String?) -> Unit,
) {
    private val log = Logger.withTag("MediaServerEntryStore")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableEntries = MutableStateFlow<List<MediaServerEntry>>(emptyList())

    val entries: StateFlow<List<MediaServerEntry>> = mutableEntries.asStateFlow()

    private var loadedProfile: Int? = null
    private var authoritative = false
    private var damaged = false

    fun ensureLoaded() = synchronized(lock) {
        val profile = activeProfileId()
        if (loadedProfile != profile) loadLocked(profile)
    }

    /** A profile switch: reload for the new profile (no cross-profile leak). */
    fun onProfileChanged(profileId: Int) = synchronized(lock) { loadLocked(profileId) }

    fun current(): List<MediaServerEntry> {
        ensureLoaded()
        return mutableEntries.value
    }

    fun entryByServerKey(serverKey: String): MediaServerEntry? = current().firstOrNull { it.serverKey == serverKey }

    fun entryByKey(key: String): MediaServerEntry? = current().firstOrNull { it.key == key }

    /** Whether the in-memory list may overwrite storage / full-replace the server's media rows (see class doc). */
    fun canPushFullReplace(): Boolean = synchronized(lock) { authoritative && !damaged }

    fun add(entry: MediaServerEntry): AddResult = synchronized(lock) {
        ensureLoadedLocked()
        mutableEntries.value.firstOrNull { it.key == entry.key }?.let { return@synchronized AddResult.Duplicate(it) }
        val before = mutableEntries.value
        mutableEntries.value = before + entry
        if (!persistLocked()) {
            mutableEntries.value = before
            return@synchronized AddResult.NotSaved
        }
        sink()?.recordAdd(loadedProfile!!, entry)
        AddResult.Added
    }

    /** Applies [transform] to the entry [key]; false when it does not exist or could not be saved. */
    fun update(key: String, transform: (MediaServerEntry) -> MediaServerEntry): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val before = mutableEntries.value
        val old = before.firstOrNull { it.key == key } ?: return@synchronized false
        val updated = transform(old).copy(key = old.key) // the key is frozen
        if (updated == old) return@synchronized true
        mutableEntries.value = before.map { if (it.key == key) updated else it }
        if (!persistLocked()) {
            mutableEntries.value = before
            return@synchronized false
        }
        sink()?.recordUpdate(loadedProfile!!, updated, base = old)
        true
    }

    /** Removes the entry. [purgeSavedData] also drops its Library / Continue Watching / Watched ids (a user's own removal; never a sync pull). */
    fun remove(key: String, purgeSavedData: Boolean): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val before = mutableEntries.value
        val old = before.firstOrNull { it.key == key } ?: return@synchronized false
        mutableEntries.value = before - old
        if (!persistLocked()) {
            mutableEntries.value = before
            return@synchronized false
        }
        sink()?.recordDelete(loadedProfile!!, key)
        if (purgeSavedData && before.none { it.key != key && it.machineId == old.machineId && it.userId == old.userId && it.type == old.type }) {
            migrateSavedData(savedDataPrefix(old), null)
        }
        true
    }

    /**
     * Signing in again as a DIFFERENT user of the same server: the entry keeps its frozen key but now names the
     * new user, and the saved ids under the old user's prefix move to the new one (design 4). When the new user
     * already has an entry for this server the old entry folds into it.
     */
    fun reLogin(key: String, newUserId: String, newUserName: String?): ReLoginResult = synchronized(lock) {
        ensureLoadedLocked()
        val before = mutableEntries.value
        val old = before.firstOrNull { it.key == key } ?: return@synchronized ReLoginResult.NotFound
        if (old.userId == newUserId) {
            val renamed = old.copy(userName = newUserName ?: old.userName)
            if (renamed == old) return@synchronized ReLoginResult.Updated(old)
            mutableEntries.value = before.map { if (it.key == key) renamed else it }
            if (!persistLocked()) {
                mutableEntries.value = before
                return@synchronized ReLoginResult.NotSaved
            }
            sink()?.recordUpdate(loadedProfile!!, renamed, base = old)
            return@synchronized ReLoginResult.Updated(renamed)
        }
        val existing = before.firstOrNull { it.key != key && it.type == old.type && it.machineId == old.machineId && it.userId == newUserId }
        val oldPrefix = savedDataPrefix(old)
        if (existing != null) {
            mutableEntries.value = before - old
            if (!persistLocked()) {
                mutableEntries.value = before
                return@synchronized ReLoginResult.NotSaved
            }
            sink()?.recordDelete(loadedProfile!!, key)
            migrateSavedData(oldPrefix, savedDataPrefix(existing))
            return@synchronized ReLoginResult.MergedInto(existing)
        }
        val updated = old.copy(userId = newUserId, userName = newUserName)
        mutableEntries.value = before.map { if (it.key == key) updated else it }
        if (!persistLocked()) {
            mutableEntries.value = before
            return@synchronized ReLoginResult.NotSaved
        }
        sink()?.recordUpdate(loadedProfile!!, updated, base = old)
        migrateSavedData(oldPrefix, savedDataPrefix(updated))
        ReLoginResult.Updated(updated)
    }

    /**
     * The server's copy is authoritative (the sync engine calls this on a pull / reconcile, never echoing a push
     * back): replaces the list, keeping what only this device knows ([MediaServerPendingOps.applyRemote]), and
     * heals a locally damaged or absent blob.
     */
    fun applyFromRemote(profileId: Int, remote: List<MediaServerEntry>) = synchronized(lock) {
        if (loadedProfile != profileId) loadLocked(profileId)
        val merged = MediaServerPendingOps.applyRemote(remote, mutableEntries.value)
        authoritative = true
        damaged = false
        mutableEntries.value = merged
        writeLocked(profileId, merged)
    }

    /** The stored entries of [profileId] (not necessarily the active one) - for a profile delete deciding which tokens can go. */
    fun entriesOf(profileId: Int): List<MediaServerEntry> = synchronized(lock) {
        if (loadedProfile == profileId) mutableEntries.value
        else persistence.load(profileId)?.let { decode(it).first }.orEmpty()
    }

    /** A profile was deleted: its entries go with it (device-local; the sync row follows the profile's own delete). */
    fun clearProfile(profileId: Int) = synchronized(lock) {
        persistence.remove(profileId)
        if (loadedProfile == profileId) {
            mutableEntries.value = emptyList()
            loadedProfile = null
            authoritative = false
            damaged = false
        }
    }

    /** Sign-out / account wipe: every profile's entries are device-local state that must not outlive the account. */
    fun clearAll(profileIds: Iterable<Int>) = synchronized(lock) {
        profileIds.forEach { persistence.remove(it) }
        mutableEntries.value = emptyList()
        loadedProfile = null
        authoritative = false
        damaged = false
    }

    /** The profile ids (of [candidates]) whose stored entries still reference [machineId]+[userId] - used to decide whether a token can go. */
    fun profilesReferencing(candidates: Iterable<Int>, type: com.nuvio.tv.core.mediaserver.api.MediaServerType, machineId: String, userId: String): List<Int> =
        candidates.filter { profile ->
            val raw = persistence.load(profile) ?: return@filter false
            decode(raw).first.any { it.type == type && it.machineId == machineId && it.userId == userId }
        }

    // --- internals ---

    private fun ensureLoadedLocked() {
        val profile = activeProfileId()
        if (loadedProfile != profile) loadLocked(profile)
    }

    private fun loadLocked(profileId: Int) {
        val raw = try {
            persistence.load(profileId)
        } catch (e: Throwable) {
            // The platform storage is not ready yet (Android hands it a Context a moment after the registrations
            // run): show nothing, never push, and TRY AGAIN on the next call instead of caching an empty list.
            log.w { "media-server storage not ready: ${e::class.simpleName}" }
            loadedProfile = null
            mutableEntries.value = emptyList()
            authoritative = false
            damaged = false
            return
        }
        loadedProfile = profileId
        if (raw == null) {
            mutableEntries.value = emptyList()
            authoritative = false // absent: never persisted - must not full-replace the server
            damaged = false
            return
        }
        val (decoded, ok) = decode(raw)
        mutableEntries.value = decoded
        authoritative = ok
        damaged = !ok
    }

    private fun decode(raw: String): Pair<List<MediaServerEntry>, Boolean> {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyList<MediaServerEntry>() to false
        val array = root["entries"] as? JsonArray ?: return emptyList<MediaServerEntry>() to false
        var clean = true
        val entries = array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(MediaServerEntry.serializer(), element) }
                .onFailure { clean = false; log.w { "an unreadable media-server entry was skipped" } }
                .getOrNull()
        }
        return entries to clean
    }

    private fun persistLocked(): Boolean {
        if (damaged) return false // never overwrite bytes this build cannot read with a truncated list
        val profile = loadedProfile ?: return false
        val wrote = writeLocked(profile, mutableEntries.value)
        if (wrote) authoritative = true
        return wrote
    }

    private fun writeLocked(profileId: Int, entries: List<MediaServerEntry>): Boolean =
        runCatching { persistence.save(profileId, json.encodeToString(EntriesBlob.serializer(), EntriesBlob(entries = entries))) }
            .onFailure { log.e(it) { "writing the media-server entries failed" } }
            .isSuccess

    private fun savedDataPrefix(entry: MediaServerEntry) = "ms:${entry.type.wire}:${entry.machineId}:${entry.userId}:"
}

package com.nuvio.tv.core.mediaserver

import com.nuvio.tv.core.mediaserver.MsLog as Logger
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.api.MediaServerSyncCodec
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.client.AuthSession
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.client.ServerInfo
import com.nuvio.tv.core.mediaserver.source.MediaServerItemRegistry
import com.nuvio.tv.core.mediaserver.store.AddResult
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import com.nuvio.tv.core.mediaserver.store.ReLoginResult
import com.nuvio.tv.core.mediaserver.store.StoredCredential

internal sealed interface SignInResult {
    data class Success(val entry: MediaServerEntry) : SignInResult
    /** This sign-in belongs to a different server than the entry (the server's own id changed): never silently re-point a session. */
    data object DifferentServer : SignInResult
    /** Neither a key nor a credential could be saved. */
    data object NotSaved : SignInResult
    /** The server's ids cannot form a sync key (a segment with `|`, `/`, `:` or whitespace). */
    data object UnusableServerIds : SignInResult
}

/**
 * The account-level operations the screens call (add a server, sign an entry in on this device, sign out,
 * remove, edit) - the one place that orders the two stores correctly: the credential is saved FIRST (a failed
 * entry write then removes it - no orphan token, no entry that looks signed in), tokens never touch the entry,
 * and the password the user typed never reaches here at all (the client uses it once).
 */
internal class MediaServerAccounts(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
    private val allProfileIds: Iterable<Int> = 1..com.nuvio.tv.core.profile.ProfileManager.MAX_PROFILES,
) {
    private val log = Logger.withTag("MediaServerAccounts")

    /** A brand-new server entry for the signed-in user. A repeat of the same server+user just signs the existing entry in again. */
    fun addServer(
        type: MediaServerType,
        baseUrl: String,
        info: ServerInfo,
        session: AuthSession,
        displayName: String?,
        syncAddress: Boolean = true,
    ): SignInResult {
        val key = MediaServerSyncCodec.playlistKey(type, info.machineId, session.userId) ?: return SignInResult.UnusableServerIds
        val entry = MediaServerEntry(
            key = key,
            type = type,
            machineId = info.machineId.trim(),
            userId = session.userId.trim(),
            name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: info.name,
            address = baseUrl,
            syncAddress = syncAddress,
            userName = session.userName,
        )
        if (!saveCredential(entry.serverKey, session)) return SignInResult.NotSaved
        return when (val added = store.add(entry)) {
            AddResult.Added -> done(entry)
            is AddResult.Duplicate -> {
                val refreshed = added.existing.copy(address = baseUrl, userName = session.userName ?: added.existing.userName)
                if (store.update(added.existing.key) { refreshed }) done(refreshed) else failCredential(entry.serverKey)
            }
            AddResult.NotSaved -> failCredential(entry.serverKey)
        }
    }

    /**
     * Signs an EXISTING entry (typically one that arrived from another device as "Sign in to ...") in on this
     * device. [serverMachineId] is what the server at [baseUrl] reported now: a different id is a different
     * server. Signing in as another user of the same server re-keys the entry's user and moves its saved data.
     */
    fun signIn(entry: MediaServerEntry, baseUrl: String, serverMachineId: String, session: AuthSession): SignInResult {
        if (serverMachineId.trim() != entry.machineId) return SignInResult.DifferentServer
        val newServerKey = "${entry.type.wire}:${entry.machineId}:${session.userId.trim()}"
        if (!saveCredential(newServerKey, session)) return SignInResult.NotSaved
        if (session.userId.trim() == entry.userId) {
            val ok = store.update(entry.key) { it.copy(address = baseUrl, userName = session.userName ?: it.userName) }
            return if (ok) done(store.entryByKey(entry.key) ?: entry) else failCredential(newServerKey)
        }
        return when (val r = store.reLogin(entry.key, session.userId.trim(), session.userName)) {
            is ReLoginResult.Updated -> {
                store.update(r.entry.key) { it.copy(address = baseUrl) }
                services.credentials.remove(entry.serverKey) // the old user's token on this device is no longer used
                done(store.entryByKey(r.entry.key) ?: r.entry)
            }
            is ReLoginResult.MergedInto -> {
                services.credentials.remove(entry.serverKey)
                done(r.entry)
            }
            ReLoginResult.NotFound, ReLoginResult.NotSaved -> failCredential(newServerKey)
        }
    }

    /** Forget this device's sign-in; the entry stays (and syncs) as "Sign in to ...". */
    fun signOut(entry: MediaServerEntry) {
        services.credentials.remove(entry.serverKey)
        services.clearExpired(entry.serverKey)
        services.notifyCredentialsChanged()
    }

    /**
     * Removes the entry (and its sync row via the sink). The token goes with it unless ANOTHER profile on this
     * device still has an entry for the same server+user. [purgeSavedData] also drops its Library / Continue
     * Watching ids - a user's own choice, never a sync pull.
     */
    fun remove(entry: MediaServerEntry, purgeSavedData: Boolean): Boolean {
        if (!store.remove(entry.key, purgeSavedData)) return false
        val stillReferenced = store.profilesReferencing(allProfileIds, entry.type, entry.machineId, entry.userId).isNotEmpty()
        if (!stillReferenced) services.credentials.remove(entry.serverKey)
        MediaServerItemRegistry.forget(entry.serverKey)
        services.clearExpired(entry.serverKey)
        services.notifyCredentialsChanged()
        return true
    }

    fun rename(entry: MediaServerEntry, name: String): Boolean {
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return false
        return store.update(entry.key) { it.copy(name = trimmed) }
    }

    fun setEnabled(entry: MediaServerEntry, enabled: Boolean): Boolean = store.update(entry.key) { it.copy(enabled = enabled) }

    /** D7: whether the typed address rides the account sync (per server, on by default). */
    fun setSyncAddress(entry: MediaServerEntry, sync: Boolean): Boolean = store.update(entry.key) { it.copy(syncAddress = sync) }

    fun setAddress(entry: MediaServerEntry, address: String): Boolean = store.update(entry.key) { it.copy(address = address) }

    /** D2: which of the server's own shelves show on Home (device-local; none by default). */
    fun setHomeRows(entry: MediaServerEntry, rows: Set<MediaServerHomeRow>): Boolean = store.update(entry.key) { it.copy(homeRows = rows) }

    /** A library on Home as a row of its own ([name] is kept so the Home layout can list the row without asking the server). */
    fun setHomeLibrary(entry: MediaServerEntry, viewId: String, name: String, on: Boolean): Boolean = store.update(entry.key) {
        it.copy(homeLibraries = if (on) it.homeLibraries + (viewId to name) else it.homeLibraries - viewId)
    }

    private fun saveCredential(serverKey: String, session: AuthSession): Boolean = try {
        services.credentials.save(serverKey, StoredCredential(session.accessToken, session.userName))
        true
    } catch (e: Exception) {
        log.e { "the sign-in could not be stored securely: ${e::class.simpleName}" }
        false
    }

    private fun done(entry: MediaServerEntry): SignInResult {
        services.clearExpired(entry.serverKey)
        services.notifyCredentialsChanged()
        return SignInResult.Success(entry)
    }

    private fun failCredential(serverKey: String): SignInResult {
        services.credentials.remove(serverKey)
        return SignInResult.NotSaved
    }
}

package com.nuvio.tv.core.iptv

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 2 — the managed-playlist cache's writer. Lives apart from [ProviderSetupRepository] so the
 * playlist sync service (which the repository itself calls for the forced pull) can use it without a
 * dependency cycle.
 */
@Singleton
class ManagedInfoRefresher @Inject constructor(
    private val api: ProviderSetupApi,
    private val store: ManagedInfoStore,
    private val authManager: AuthManager,
) {
    private fun userId(): String? = (authManager.authState.value as? AuthState.FullAccount)?.userId

    /**
     * Called by the playlist sync after a pull. Refreshes the profile's managed map only per
     * [ManagedRefreshPolicy] (>= 1 playlist; revision changed, cache missing, or cache older than a day).
     */
    suspend fun afterPull(profileId: Int, pullSucceeded: Boolean, playlistCount: Int, revision: Long?) {
        val uid = userId() ?: return
        val cached = store.entry(uid, profileId)
        val now = System.currentTimeMillis()
        val should = ManagedRefreshPolicy.shouldRefresh(
            pullSucceeded = pullSucceeded,
            playlistCount = playlistCount,
            revision = revision,
            cachedRevision = cached?.revision,
            hasCache = cached != null,
            cacheAgeMs = cached?.let { now - it.fetchedAtMs } ?: 0L,
        )
        if (should) fetchInto(uid, profileId, revision)
    }

    /** Unconditional refresh (after a detach). False when the read failed (the old map stays). */
    suspend fun refreshNow(profileId: Int, revision: Long? = null): Boolean {
        val uid = userId() ?: return false
        return fetchInto(uid, profileId, revision)
    }

    private suspend fun fetchInto(uid: String, profileId: Int, revision: Long?): Boolean =
        api.managedPlaylists(profileId).fold(
            onSuccess = { list -> store.replace(uid, profileId, list, revision); true },
            onFailure = { Log.w("ManagedInfoRefresher", "managed playlists read failed: ${it.javaClass.simpleName}"); false },
        )

    /** The profile's managed keys as the server reports them now (the TV code screen's snapshot/poll). */
    suspend fun fetchKeys(profileId: Int): Result<Set<String>> =
        api.managedPlaylists(profileId).map { list -> list.map { it.playlistKey }.toSet() }

    /** The managed map for the signed-in user's [profileId] (empty when signed out). */
    fun infosFlow(profileId: Int): Flow<Map<String, ManagedPlaylistInfo>> =
        authManager.authState.flatMapLatestUser { uid -> store.infosFlow(uid, profileId) }

    fun infosNow(profileId: Int): Map<String, ManagedPlaylistInfo> {
        val uid = userId() ?: return emptyMap()
        return store.infos(uid, profileId)
    }
}

private fun kotlinx.coroutines.flow.Flow<AuthState>.flatMapLatestUser(
    block: (String) -> Flow<Map<String, ManagedPlaylistInfo>>,
): Flow<Map<String, ManagedPlaylistInfo>> = flatMapLatest { state ->
    if (state is AuthState.FullAccount) block(state.userId) else flowOf(emptyMap())
}


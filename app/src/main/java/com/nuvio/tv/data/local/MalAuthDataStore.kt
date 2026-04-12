package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile storage for MyAnimeList OAuth tokens + phone-pair session state.
 *
 * Access tokens live 1 h; refresh tokens live ~30 days. [expiresAtEpochMs] is
 * computed at save time (now + expires_in) so token freshness checks don't
 * need to chase the original token_created_at reference point.
 */
data class MalAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long? = null,
    val userId: String? = null,
    val username: String? = null,
    val sessionCode: String? = null,
    val sessionPollIntervalSeconds: Int? = null,
    val sessionExpiresAtEpochMs: Long? = null,
    val sessionWebUrl: String? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MalAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object { private const val FEATURE = "mal_auth_store" }

    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val userIdKey = stringPreferencesKey("user_id")
    private val usernameKey = stringPreferencesKey("username")
    private val sessionCodeKey = stringPreferencesKey("session_code")
    private val sessionPollIntervalKey = longPreferencesKey("session_poll_interval")
    private val sessionExpiresAtKey = longPreferencesKey("session_expires_at")
    private val sessionWebUrlKey = stringPreferencesKey("session_web_url")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val state: Flow<MalAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { p ->
            MalAuthState(
                accessToken = p[accessTokenKey],
                refreshToken = p[refreshTokenKey],
                expiresAtEpochMs = p[expiresAtKey],
                userId = p[userIdKey],
                username = p[usernameKey],
                sessionCode = p[sessionCodeKey],
                sessionPollIntervalSeconds = p[sessionPollIntervalKey]?.toInt(),
                sessionExpiresAtEpochMs = p[sessionExpiresAtKey],
                sessionWebUrl = p[sessionWebUrlKey]
            )
        }
    }

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }

    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        val expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L
        store().edit {
            it[accessTokenKey] = accessToken
            it[refreshTokenKey] = refreshToken
            it[expiresAtKey] = expiresAt
        }
    }

    suspend fun saveUser(userId: String?, username: String?) {
        store().edit {
            if (userId.isNullOrBlank()) it.remove(userIdKey) else it[userIdKey] = userId
            if (username.isNullOrBlank()) it.remove(usernameKey) else it[usernameKey] = username
        }
    }

    suspend fun saveSession(code: String, webUrl: String, pollIntervalSeconds: Int, expiresAtEpochMs: Long) {
        store().edit {
            it[sessionCodeKey] = code
            it[sessionWebUrlKey] = webUrl
            it[sessionPollIntervalKey] = pollIntervalSeconds.toLong()
            it[sessionExpiresAtKey] = expiresAtEpochMs
        }
    }

    suspend fun clearSession() {
        store().edit {
            it.remove(sessionCodeKey)
            it.remove(sessionWebUrlKey)
            it.remove(sessionPollIntervalKey)
            it.remove(sessionExpiresAtKey)
        }
    }

    suspend fun clearAuth() {
        store().edit {
            it.remove(accessTokenKey)
            it.remove(refreshTokenKey)
            it.remove(expiresAtKey)
            it.remove(userIdKey)
            it.remove(usernameKey)
            it.remove(sessionCodeKey)
            it.remove(sessionWebUrlKey)
            it.remove(sessionPollIntervalKey)
            it.remove(sessionExpiresAtKey)
        }
    }
}

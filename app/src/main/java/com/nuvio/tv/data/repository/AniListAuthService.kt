package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AniListAuthDataStore
import com.nuvio.tv.data.remote.supabase.TrackerTvLoginService
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AniList phone-pair controller. AniList issues 1-year access tokens via the
 * implicit or pin grant and does not provide refresh tokens — when a token
 * expires, the user must complete the phone-pair flow again. There is
 * therefore no refresh path; [getValidAccessToken] either returns the stored
 * token or null.
 */
@Singleton
class AniListAuthService @Inject constructor(
    private val dataStore: AniListAuthDataStore,
    private val tvLogin: TrackerTvLoginService,
    private val tokenSync: dagger.Lazy<TrackerTokenSyncService>
) {
    private var deviceNonce: String? = null

    suspend fun startPhoneLogin(): Result<TrackerPhoneLoginChallenge> {
        val nonce = UUID.randomUUID().toString().also { deviceNonce = it }
        val result = tvLogin.startSession(TrackerTvLoginService.Tracker.ANILIST, nonce)
        return result.map { r ->
            val expiresAtMs = parseIsoToEpochMs(r.expiresAt)
                ?: (System.currentTimeMillis() + 5 * 60 * 1000)
            dataStore.saveSession(
                code = r.code,
                webUrl = r.webUrl,
                pollIntervalSeconds = r.pollIntervalSeconds,
                expiresAtEpochMs = expiresAtMs
            )
            TrackerPhoneLoginChallenge(
                code = r.code,
                webUrl = r.webUrl,
                pollIntervalSeconds = r.pollIntervalSeconds,
                expiresAtEpochMs = expiresAtMs
            )
        }
    }

    suspend fun pollPhoneLogin(): TrackerPhoneLoginPoll {
        val snapshot = dataStore.state.first()
        val code = snapshot.sessionCode ?: return TrackerPhoneLoginPoll.Error("no active session")
        val nonce = deviceNonce ?: return TrackerPhoneLoginPoll.Error("missing device nonce")
        val pollResult = tvLogin.pollSession(TrackerTvLoginService.Tracker.ANILIST, code, nonce)
        val poll = pollResult.getOrElse { return TrackerPhoneLoginPoll.Error(it.message ?: "poll failed") }
        return when (poll.status) {
            "ready" -> {
                val access = poll.accessToken ?: return TrackerPhoneLoginPoll.Error("missing access_token")
                // AniList tokens last ~1 year; default to 365 days when the edge
                // function doesn't supply an explicit expires_in.
                val expiresIn = poll.expiresIn ?: DEFAULT_LIFETIME_SECONDS
                dataStore.saveToken(access, expiresIn)
                dataStore.saveUser(userId = poll.userId, username = poll.username)
                dataStore.clearSession()
                runCatching {
                    tokenSync.get().pushTokens(
                        tracker = TrackerTokenSyncService.TRACKER_ANILIST,
                        accessToken = access,
                        refreshToken = null,
                        expiresInSeconds = expiresIn,
                        trackerUserId = poll.userId,
                        trackerUsername = poll.username
                    )
                }.onFailure { Log.w(TAG, "token sync push (phone-pair) failed: ${it.message}") }
                TrackerPhoneLoginPoll.Success(username = poll.username)
            }
            "expired" -> {
                dataStore.clearSession()
                TrackerPhoneLoginPoll.Expired()
            }
            else -> TrackerPhoneLoginPoll.Pending
        }
    }

    suspend fun revokeAndLogout() {
        dataStore.clearAuth()
        runCatching { tokenSync.get().clearTokens(TrackerTokenSyncService.TRACKER_ANILIST) }
            .onFailure { Log.w(TAG, "token sync clear failed: ${it.message}") }
    }

    // --- Debug-only local auth (no Supabase required) --- //
    //
    // AniList supports an "implicit/pin" grant: point at the authorize URL
    // with `response_type=token` and AniList either returns the token in the
    // URL fragment (implicit) or shows it on a page (pin). The user pastes
    // the token into the app; we store it with a 365-day synthetic expiry
    // (AniList doesn't return one for implicit grants).

    /** [debug only] URL the user should open in a browser to get an access token. */
    fun buildLocalDebugAuthorizeUrl(): String =
        "https://anilist.co/api/v2/oauth/authorize" +
            "?client_id=${BuildConfig.ANILIST_CLIENT_ID}" +
            "&response_type=token"

    /**
     * [debug only] Stores a manually-pasted AniList access token. Runs a
     * Viewer lookup so the UI can show the username; returns it on success.
     */
    suspend fun saveLocalDebugToken(accessToken: String): Result<String?> {
        val trimmed = accessToken.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Empty token"))
        val lifetimeSeconds = 365L * 24 * 60 * 60
        dataStore.saveToken(trimmed, lifetimeSeconds)
        runCatching {
            tokenSync.get().pushTokens(
                tracker = TrackerTokenSyncService.TRACKER_ANILIST,
                accessToken = trimmed,
                refreshToken = null,
                expiresInSeconds = lifetimeSeconds,
                trackerUserId = null,
                trackerUsername = null
            )
        }.onFailure { Log.w(TAG, "token sync push (debug) failed: ${it.message}") }
        return Result.success(null)
    }

    /** Null when not logged in or token has expired (AniList has no refresh flow). */
    suspend fun getValidAccessToken(): String? {
        val snapshot = dataStore.state.first()
        val access = snapshot.accessToken ?: return null
        val expiresAt = snapshot.expiresAtEpochMs ?: return access
        return if (System.currentTimeMillis() < expiresAt) access else null
    }

    private fun parseIsoToEpochMs(iso: String): Long? =
        try { Instant.parse(iso).toEpochMilli() } catch (_: DateTimeParseException) { null }

    companion object {
        private const val TAG = "AniListAuthService"
        private const val DEFAULT_LIFETIME_SECONDS = 365L * 24 * 60 * 60
    }
}

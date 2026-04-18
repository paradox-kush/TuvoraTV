package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.local.KitsuAuthDataStore
import com.nuvio.tv.data.remote.dto.kitsu.KitsuTokenResponseDto
import com.nuvio.tv.data.remote.supabase.TrackerTvLoginService
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kitsu OAuth + phone-pair controller. Kitsu uses Doorkeeper's resource-owner
 * password grant on the initial login (handled by the phone companion) and
 * supports `refresh_token` for subsequent renewals. No client id/secret is
 * required.
 */
@Singleton
class KitsuAuthService @Inject constructor(
    private val dataStore: KitsuAuthDataStore,
    private val tvLogin: TrackerTvLoginService,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val tokenSync: dagger.Lazy<TrackerTokenSyncService>
) {
    private val refreshMutex = Mutex()
    private val tokenAdapter = moshi.adapter(KitsuTokenResponseDto::class.java)
    private var deviceNonce: String? = null

    suspend fun startPhoneLogin(): Result<TrackerPhoneLoginChallenge> {
        val nonce = UUID.randomUUID().toString().also { deviceNonce = it }
        val result = tvLogin.startSession(TrackerTvLoginService.Tracker.KITSU, nonce)
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
        val pollResult = tvLogin.pollSession(TrackerTvLoginService.Tracker.KITSU, code, nonce)
        val poll = pollResult.getOrElse { return TrackerPhoneLoginPoll.Error(it.message ?: "poll failed") }
        return when (poll.status) {
            "ready" -> {
                val access = poll.accessToken ?: return TrackerPhoneLoginPoll.Error("missing access_token")
                val refresh = poll.refreshToken ?: return TrackerPhoneLoginPoll.Error("missing refresh_token")
                val expiresIn = poll.expiresIn ?: DEFAULT_LIFETIME_SECONDS
                dataStore.saveTokens(access, refresh, expiresIn)
                dataStore.saveUser(userId = poll.userId, username = poll.username)
                dataStore.clearSession()
                runCatching {
                    tokenSync.get().pushTokens(
                        tracker = TrackerTokenSyncService.TRACKER_KITSU,
                        accessToken = access,
                        refreshToken = refresh,
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
        runCatching { tokenSync.get().clearTokens(TrackerTokenSyncService.TRACKER_KITSU) }
            .onFailure { Log.w(TAG, "token sync clear failed: ${it.message}") }
    }

    // --- Debug-only local auth (no Supabase required) --- //
    //
    // Kitsu supports OAuth2 resource-owner password grant with no client
    // registration: POST username (email) + password to /oauth/token and
    // receive an access + refresh token pair. The phone-pair flow is nice
    // UX but this direct path unblocks local testing immediately.

    suspend fun signInWithPassword(email: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                    .add("grant_type", "password")
                    .add("username", email.trim())
                    .add("password", password)
                    .build()
                val req = Request.Builder().url(TOKEN_URL).post(form).build()
                okHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "kitsu password grant ${resp.code}: $body")
                        return@withContext Result.failure(IllegalStateException("Kitsu login failed (${resp.code}): $body"))
                    }
                    val token = tokenAdapter.fromJson(body)
                        ?: return@withContext Result.failure(IllegalStateException("Malformed Kitsu token response"))
                    dataStore.saveTokens(token.accessToken, token.refreshToken, token.expiresIn)
                    Log.i(TAG, "kitsu password grant ok")
                    runCatching {
                        tokenSync.get().pushTokens(
                            tracker = TrackerTokenSyncService.TRACKER_KITSU,
                            accessToken = token.accessToken,
                            refreshToken = token.refreshToken,
                            expiresInSeconds = token.expiresIn,
                            trackerUserId = null,
                            trackerUsername = null
                        )
                    }.onFailure { Log.w(TAG, "token sync push (debug) failed: ${it.message}") }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "kitsu password grant failed", e)
                Result.failure(e)
            }
        }

    suspend fun getValidAccessToken(): String? {
        val snapshot = dataStore.state.first()
        val access = snapshot.accessToken
        val expiresAt = snapshot.expiresAtEpochMs ?: 0L
        if (!access.isNullOrBlank() && System.currentTimeMillis() < expiresAt - REFRESH_LEEWAY_MS) {
            return access
        }
        val refresh = snapshot.refreshToken ?: return null
        return refreshTokens(refresh)
    }

    private suspend fun refreshTokens(refreshToken: String): String? = refreshMutex.withLock {
        val snapshot = dataStore.state.first()
        if (!snapshot.accessToken.isNullOrBlank() &&
            System.currentTimeMillis() < (snapshot.expiresAtEpochMs ?: 0L) - REFRESH_LEEWAY_MS
        ) {
            return snapshot.accessToken
        }
        withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .build()
                val req = Request.Builder()
                    .url(TOKEN_URL)
                    .post(form)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "kitsu refresh failed code=${resp.code} body=$body")
                        if (resp.code == 400 || resp.code == 401) dataStore.clearAuth()
                        return@withContext null
                    }
                    val token = runCatching { tokenAdapter.fromJson(body) }.getOrNull()
                        ?: return@withContext null
                    dataStore.saveTokens(
                        accessToken = token.accessToken,
                        refreshToken = token.refreshToken,
                        expiresInSeconds = token.expiresIn
                    )
                    runCatching {
                        tokenSync.get().pushTokens(
                            tracker = TrackerTokenSyncService.TRACKER_KITSU,
                            accessToken = token.accessToken,
                            refreshToken = token.refreshToken,
                            expiresInSeconds = token.expiresIn,
                            trackerUserId = null,
                            trackerUsername = null
                        )
                    }.onFailure { Log.w(TAG, "token sync push (refresh) failed: ${it.message}") }
                    token.accessToken
                }
            } catch (e: Exception) {
                Log.e(TAG, "kitsu refresh threw", e)
                null
            }
        }
    }

    private fun parseIsoToEpochMs(iso: String): Long? =
        try { Instant.parse(iso).toEpochMilli() } catch (_: DateTimeParseException) { null }

    companion object {
        private const val TAG = "KitsuAuthService"
        private const val TOKEN_URL = "https://kitsu.io/api/oauth/token"
        private const val DEFAULT_LIFETIME_SECONDS = 2_592_000L // 30 days
        private const val REFRESH_LEEWAY_MS = 60_000L
    }
}

package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.MalAuthDataStore
import com.nuvio.tv.data.remote.dto.mal.MalTokenResponseDto
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
 * MyAnimeList OAuth + phone-pair controller. The initial authorization-code
 * (PKCE plain) grant happens on the phone companion — the TV only ever talks
 * directly to MAL for refresh. Access tokens live 1 h so [getValidAccessToken]
 * proactively refreshes when less than [REFRESH_LEEWAY_MS] remains.
 */
@Singleton
class MalAuthService @Inject constructor(
    private val dataStore: MalAuthDataStore,
    private val tvLogin: TrackerTvLoginService,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    private val refreshMutex = Mutex()
    private val tokenAdapter = moshi.adapter(MalTokenResponseDto::class.java)
    private var deviceNonce: String? = null

    suspend fun startPhoneLogin(): Result<TrackerPhoneLoginChallenge> {
        val nonce = UUID.randomUUID().toString().also { deviceNonce = it }
        val result = tvLogin.startSession(TrackerTvLoginService.Tracker.MAL, nonce)
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
        val pollResult = tvLogin.pollSession(TrackerTvLoginService.Tracker.MAL, code, nonce)
        val poll = pollResult.getOrElse { return TrackerPhoneLoginPoll.Error(it.message ?: "poll failed") }
        return when (poll.status) {
            "ready" -> {
                val access = poll.accessToken ?: return TrackerPhoneLoginPoll.Error("missing access_token")
                val refresh = poll.refreshToken ?: return TrackerPhoneLoginPoll.Error("missing refresh_token")
                val expiresIn = poll.expiresIn ?: 3600L
                dataStore.saveTokens(access, refresh, expiresIn)
                dataStore.saveUser(userId = poll.userId, username = poll.username)
                dataStore.clearSession()
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
        // MAL has no token revocation endpoint — just clear local state.
        dataStore.clearAuth()
    }

    // --- Debug-only local auth (no Supabase required) --- //
    //
    // MAL's OAuth uses authorization code + PKCE with `code_challenge_method=plain`
    // (SHA256 is rejected — MAL quirk). We generate a random verifier, build the
    // authorize URL, the user completes OAuth in any browser and is redirected
    // to a localhost URL with `?code=…`, then pastes the code back in. We
    // exchange (code + verifier) for tokens by calling MAL directly.

    private var pendingPkceVerifier: String? = null

    /**
     * [debug only] Returns the authorize URL the user should open in a browser.
     * Persists the PKCE verifier in-memory until the user completes the flow
     * via [completeLocalDebugAuth].
     */
    fun beginLocalDebugAuth(redirectUri: String): String {
        val verifier = generatePkceVerifier()
        pendingPkceVerifier = verifier
        val clientId = BuildConfig.MAL_CLIENT_ID
        return buildString {
            append("https://myanimelist.net/v1/oauth2/authorize")
            append("?response_type=code")
            append("&client_id=").append(clientId)
            append("&code_challenge=").append(verifier) // plain — MAL rejects SHA256
            append("&code_challenge_method=plain")
            append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
            append("&state=nuvio-debug")
        }
    }

    /**
     * [debug only] Exchanges the authorization code (pasted by the user after
     * completing OAuth on phone/browser) for access + refresh tokens. Stores
     * them in [dataStore] on success; returns the MAL username when that
     * follow-up lookup also succeeds.
     */
    suspend fun completeLocalDebugAuth(code: String, redirectUri: String): Result<String?> =
        withContext(Dispatchers.IO) {
            val verifier = pendingPkceVerifier
                ?: return@withContext Result.failure(IllegalStateException("No pending MAL auth — tap Begin first"))
            try {
                val form = FormBody.Builder()
                    .add("client_id", BuildConfig.MAL_CLIENT_ID)
                    .apply {
                        val secret = BuildConfig.MAL_CLIENT_SECRET
                        if (secret.isNotBlank()) add("client_secret", secret)
                    }
                    .add("grant_type", "authorization_code")
                    .add("code", code.trim())
                    .add("code_verifier", verifier)
                    .add("redirect_uri", redirectUri)
                    .build()
                val req = Request.Builder().url(TOKEN_URL).post(form).build()
                okHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IllegalStateException("MAL token exchange failed (${resp.code}): $body"))
                    }
                    val token = tokenAdapter.fromJson(body)
                        ?: return@withContext Result.failure(IllegalStateException("Malformed MAL token response"))
                    dataStore.saveTokens(token.accessToken, token.refreshToken, token.expiresIn)
                    pendingPkceVerifier = null
                }
                // Look up username so the UI can confirm sign-in. Non-fatal on failure.
                val username = runCatching {
                    val meReq = Request.Builder()
                        .url("https://api.myanimelist.net/v2/users/@me")
                        .header("Authorization", "Bearer ${dataStore.state.first().accessToken.orEmpty()}")
                        .get()
                        .build()
                    okHttpClient.newCall(meReq).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        // Parse just the name field — full DTO overkill here.
                        val body = resp.body.string()
                        Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    }
                }.getOrNull()
                if (!username.isNullOrBlank()) dataStore.saveUser(userId = null, username = username)
                Result.success(username)
            } catch (e: Exception) {
                Log.e(TAG, "local debug auth failed", e)
                Result.failure(e)
            }
        }

    private fun generatePkceVerifier(): String {
        // RFC 7636: 43-128 unreserved characters. Use URL-safe base64 of 32 random bytes.
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    /**
     * Returns a non-expired access token, refreshing if necessary. Callers
     * pass the returned string straight into `Authorization: Bearer …`.
     * Returns null when no refresh token is stored (user not logged in) or
     * a refresh attempt hard-failed.
     */
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
        // Re-check under lock in case another caller already refreshed.
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
                    .apply {
                        val clientId = BuildConfig.MAL_CLIENT_ID
                        val secret = BuildConfig.MAL_CLIENT_SECRET
                        if (clientId.isNotBlank()) add("client_id", clientId)
                        if (secret.isNotBlank()) add("client_secret", secret)
                    }
                    .build()
                val req = Request.Builder()
                    .url(TOKEN_URL)
                    .post(form)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "refresh failed code=${resp.code} body=$body")
                        if (resp.code == 400 || resp.code == 401) {
                            // Refresh token revoked — force re-auth.
                            dataStore.clearAuth()
                        }
                        return@withContext null
                    }
                    val token = runCatching { tokenAdapter.fromJson(body) }.getOrNull()
                        ?: return@withContext null
                    dataStore.saveTokens(
                        accessToken = token.accessToken,
                        refreshToken = token.refreshToken,
                        expiresInSeconds = token.expiresIn
                    )
                    token.accessToken
                }
            } catch (e: Exception) {
                Log.e(TAG, "refresh threw", e)
                null
            }
        }
    }

    private fun parseIsoToEpochMs(iso: String): Long? =
        try { Instant.parse(iso).toEpochMilli() } catch (_: DateTimeParseException) { null }

    companion object {
        private const val TAG = "MalAuthService"
        private const val TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
        private const val REFRESH_LEEWAY_MS = 60_000L
    }
}

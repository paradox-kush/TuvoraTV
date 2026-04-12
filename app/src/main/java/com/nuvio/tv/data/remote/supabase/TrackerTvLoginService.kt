package com.nuvio.tv.data.remote.supabase

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around three Supabase primitives that back the phone-pair login
 * flow for MyAnimeList / AniList / Kitsu:
 *
 *   RPC  start_tracker_tv_login_session(tracker, device_nonce, redirect_base_url)
 *   RPC  poll_tracker_tv_login_session(tracker, code, device_nonce)
 *   EDGE tracker-tv-logins-exchange    (invoked by the phone, not the TV)
 *
 * The TV never sees the user's tracker password and never completes the OAuth
 * grant directly — that all happens on the phone companion. The TV only:
 *
 *   1. starts a session (gets a short code + QR url),
 *   2. polls until the phone has finished and the edge function has stashed
 *      tokens in the session row,
 *   3. reads the tokens out of the poll response and stores them in the
 *      per-tracker `*AuthDataStore`.
 *
 * QR login requires an authenticated Supabase session (anonymous is fine);
 * callers must invoke [AuthManager.ensureQrSessionAuthenticated] first. This
 * service does not do that itself to keep the flow explicit at the UI layer,
 * matching how the existing Trakt TV-QR flow is structured.
 */
@Singleton
class TrackerTvLoginService @Inject constructor(
    private val postgrest: Postgrest
) {
    enum class Tracker(val key: String) {
        MAL("mal"),
        ANILIST("anilist"),
        KITSU("kitsu")
    }

    suspend fun startSession(
        tracker: Tracker,
        deviceNonce: String,
        redirectBaseUrl: String = BuildConfig.TV_LOGIN_WEB_BASE_URL
    ): Result<TrackerTvLoginStartResult> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_tracker", tracker.key)
                put("p_device_nonce", deviceNonce)
                put("p_redirect_base_url", redirectBaseUrl)
            }
            val response = postgrest.rpc("start_tracker_tv_login_session", params)
            val result = response.decodeList<TrackerTvLoginStartResult>().firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Empty response from start_tracker_tv_login_session"))
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "start_tracker_tv_login_session failed for ${tracker.key}", e)
            Result.failure(e)
        }
    }

    suspend fun pollSession(
        tracker: Tracker,
        code: String,
        deviceNonce: String
    ): Result<TrackerTvLoginPollResult> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_tracker", tracker.key)
                put("p_code", code)
                put("p_device_nonce", deviceNonce)
            }
            val response = postgrest.rpc("poll_tracker_tv_login_session", params)
            val result = response.decodeList<TrackerTvLoginPollResult>().firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("Empty response from poll_tracker_tv_login_session"))
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "poll_tracker_tv_login_session failed for ${tracker.key}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "TrackerTvLogin"
    }
}

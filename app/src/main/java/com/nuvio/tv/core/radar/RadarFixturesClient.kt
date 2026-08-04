package com.nuvio.tv.core.radar

import android.util.Log
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RadarFixturesClient"

/**
 * Calls the radar-fixtures Supabase edge function (TheSportsDB proxy + cache) with a plain
 * GET — verify_jwt=false server-side, so no auth headers needed and it works signed-out.
 * The paid API key never ships in the app.
 */
@Singleton
class RadarFixturesClient @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(
        leagueIds: Collection<String>,
        livescoreSports: Collection<String>,
        teamIds: Collection<String> = emptyList(),
    ): RadarFixturesResponse? = withContext(Dispatchers.IO) {
        if (leagueIds.isEmpty() && livescoreSports.isEmpty() && teamIds.isEmpty()) return@withContext null
        runCatching {
            val base = supabaseProvider.selectedBackend.normalizedSupabaseUrl
            val url = buildString {
                append(base).append("/functions/v1/radar-fixtures?league_ids=")
                append(URLEncoder.encode(leagueIds.joinToString(","), "UTF-8"))
                if (teamIds.isNotEmpty()) {
                    append("&team_ids=")
                    append(URLEncoder.encode(teamIds.joinToString(","), "UTF-8"))
                }
                if (livescoreSports.isNotEmpty()) {
                    append("&livescore_sports=")
                    append(URLEncoder.encode(livescoreSports.joinToString(","), "UTF-8"))
                }
            }
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                check(response.isSuccessful) { "radar-fixtures HTTP ${response.code}" }
                json.decodeFromString<RadarFixturesResponse>(response.body?.string().orEmpty())
            }
        }.onFailure { e -> Log.e(TAG, "fetch failed", e) }.getOrNull()
    }

    /** Broadcaster listings for one event (server caches 12h; empty on any failure). */
    suspend fun fetchTv(eventId: String): List<RadarTvStation> = withContext(Dispatchers.IO) {
        runCatching {
            val base = supabaseProvider.selectedBackend.normalizedSupabaseUrl
            val url = "$base/functions/v1/radar-fixtures?tv_event_ids=" +
                URLEncoder.encode(eventId, "UTF-8")
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                check(response.isSuccessful) { "radar-fixtures HTTP ${response.code}" }
                json.decodeFromString<RadarFixturesResponse>(response.body?.string().orEmpty())
                    .tv[eventId].orEmpty()
            }
        }.onFailure { e -> Log.e(TAG, "tv fetch failed", e) }.getOrDefault(emptyList())
    }
}

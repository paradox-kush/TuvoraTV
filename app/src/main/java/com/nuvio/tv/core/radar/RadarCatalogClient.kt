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

private const val TAG = "RadarCatalogClient"

/**
 * Fetches the published league catalog from the radar-fixtures edge function
 * (`?catalog=1`), so leagues can be added without shipping three app releases.
 *
 * Same posture as [RadarFixturesClient]: plain GET, verify_jwt=false server-side, works
 * signed-out. Returns null on any failure — the caller keeps whatever catalog it already
 * has, which is the last good fetch or the bundled constant.
 */
@Singleton
class RadarCatalogClient @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Leagues we deliberately didn't curate, for the "add a league" picker. Either browse by
     * country (optionally narrowed to one sport) or free-text search. Empty on any failure —
     * the picker shows "nothing found" rather than an error state.
     */
    suspend fun searchLeagues(
        country: String = "",
        sport: String = "",
        text: String = "",
    ): List<RadarLeague> = withContext(Dispatchers.IO) {
        if (country.isBlank() && text.isBlank()) return@withContext emptyList()
        runCatching {
            val base = supabaseProvider.selectedBackend.normalizedSupabaseUrl
            val url = buildString {
                append(base).append("/functions/v1/radar-fixtures?")
                if (country.isNotBlank()) {
                    append("league_country=").append(URLEncoder.encode(country, "UTF-8"))
                    if (sport.isNotBlank()) append("&league_sport=").append(URLEncoder.encode(sport, "UTF-8"))
                } else {
                    append("league_search=").append(URLEncoder.encode(text, "UTF-8"))
                }
            }
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                check(response.isSuccessful) { "league search HTTP ${response.code}" }
                json.decodeFromString<RadarLeagueSearchResponse>(response.body?.string().orEmpty()).leagues
            }
        }.onFailure { e -> Log.w(TAG, "league search failed", e) }.getOrDefault(emptyList())
    }

    /**
     * Free-text club search for the "follow a team" picker. There is no browse-by-country
     * fallback here as there is for leagues: nobody scrolls to their club through a list of
     * every team in a country, they type its name.
     */
    suspend fun searchTeams(text: String): List<RadarTeam> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()
        runCatching {
            val base = supabaseProvider.selectedBackend.normalizedSupabaseUrl
            val url = "$base/functions/v1/radar-fixtures?team_search=" + URLEncoder.encode(text, "UTF-8")
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                check(response.isSuccessful) { "team search HTTP ${response.code}" }
                json.decodeFromString<RadarTeamSearchResponse>(response.body?.string().orEmpty()).teams
            }
        }.onFailure { e -> Log.w(TAG, "team search failed", e) }.getOrDefault(emptyList())
    }

    suspend fun fetch(): RadarCatalogEnvelope? = withContext(Dispatchers.IO) {
        runCatching {
            val base = supabaseProvider.selectedBackend.normalizedSupabaseUrl
            val url = "$base/functions/v1/radar-fixtures?catalog=1"
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                check(response.isSuccessful) { "radar catalog HTTP ${response.code}" }
                json.decodeFromString<RadarCatalogEnvelope>(response.body?.string().orEmpty())
            }
        }.onFailure { e -> Log.w(TAG, "catalog fetch failed", e) }.getOrNull()
    }
}

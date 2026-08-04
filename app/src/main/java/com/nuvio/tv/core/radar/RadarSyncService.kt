package com.nuvio.tv.core.radar

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RadarSyncService"

/**
 * Syncs Sports Centre follows + featured prefs per profile, mirroring XtreamAccountSyncService:
 * debounced full-replace push (sync_push_radar) on change; pull = direct RLS-scoped selects.
 */
@Singleton
class RadarSyncService @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val authManager: AuthManager,
    private val store: RadarStore,
    private val profileManager: ProfileManager,
) {
    private val postgrest get() = supabaseProvider.postgrest
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null

    var isSyncingFromRemote: Boolean = false

    @Serializable
    private data class FollowRow(
        @SerialName("league_id") val leagueId: String,
        val sport: String = "",
        @SerialName("sort_order") val sortOrder: Int = 0,
        // Present only for leagues the user added themselves — see RadarFollow.
        val name: String? = null,
        val badge: String? = null,
        val banner: String? = null,
        val keywords: List<String>? = null,
        val custom: Boolean = false,
    )

    /** A followed club. Every column is populated — there is no team catalog to defer to. */
    @Serializable
    private data class TeamFollowRow(
        @SerialName("team_id") val teamId: String,
        val name: String = "",
        val sport: String = "",
        val badge: String? = null,
        @SerialName("league_id") val leagueId: String? = null,
        val league: String? = null,
        val keywords: List<String>? = null,
        @SerialName("sort_order") val sortOrder: Int = 0,
    )

    @Serializable
    private data class PrefsRow(
        @SerialName("featured_event_id") val featuredEventId: String = "",
        @SerialName("opt_in_state") val optInState: String = RadarOptIn.UNSET,
        @SerialName("promo_dismissed") val promoDismissed: Boolean = false,
    )

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /** Debounced push after a local change (called from RadarRepository). */
    fun triggerRemoteSync() {
        if (isSyncingFromRemote || !authManager.isAuthenticated) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(600)
            if (!isSyncingFromRemote) pushToRemote()
        }
    }

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val state = store.state.first()
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_follows", buildJsonArray {
                    state.follows.forEachIndexed { index, follow ->
                        addJsonObject {
                            put("league_id", follow.leagueId)
                            put("sport", follow.sport)
                            put("sort_order", index)
                            if (follow.custom) {
                                put("custom", true)
                                follow.name?.let { put("name", it) }
                                follow.badge?.let { put("badge", it) }
                                follow.banner?.let { put("banner", it) }
                                if (follow.keywords.isNotEmpty()) {
                                    put("keywords", buildJsonArray { follow.keywords.forEach { add(it) } })
                                }
                            }
                        }
                    }
                })
                putJsonObject("p_prefs") {
                    put("featured_event_id", state.prefs.featuredEventId)
                    put("opt_in_state", state.prefs.optInState)
                    put("promo_dismissed", state.prefs.promoDismissed)
                }
                // Always sent, even when empty: the RPC reads a MISSING p_teams as "this
                // client has nothing to say about teams" and leaves the remote rows alone, so
                // omitting it here would make unfollowing your last club un-syncable.
                put("p_teams", buildJsonArray {
                    state.teams.forEachIndexed { index, team ->
                        addJsonObject {
                            put("team_id", team.teamId)
                            put("name", team.name)
                            put("sport", team.sport)
                            put("sort_order", index)
                            team.badge?.let { put("badge", it) }
                            team.leagueId?.let { put("league_id", it) }
                            team.league?.let { put("league", it) }
                            if (team.keywords.isNotEmpty()) {
                                put("keywords", buildJsonArray { team.keywords.forEach { add(it) } })
                            }
                        }
                    }
                })
            }
            withJwtRefreshRetry { postgrest.rpc("sync_push_radar", params) }
            Log.d(TAG, "Pushed ${state.follows.size} radar follows, ${state.teams.size} teams for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push radar state", e)
            Result.failure(e)
        }
    }

    /** Pull this profile's follows+prefs and apply locally. Empty remote + non-empty local => migrate up. */
    suspend fun pullAndApply(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = authManager.getEffectiveUserId(fallbackToOwnIdOnFailure = false)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to resolve sync owner for radar sync")
                )
            val profileId = profileManager.activeProfileId.value
            val followRows = withJwtRefreshRetry {
                postgrest.from("radar_follows")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<FollowRow>()
            }
            val teamRows = withJwtRefreshRetry {
                postgrest.from("radar_team_follows")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<TeamFollowRow>()
            }
            val prefsRow = withJwtRefreshRetry {
                postgrest.from("radar_prefs")
                    .select { filter {
                        eq("user_id", effectiveUserId)
                        eq("profile_id", profileId)
                    } }
                    .decodeList<PrefsRow>()
                    .firstOrNull()
            }
            if (followRows.isEmpty() && teamRows.isEmpty() && prefsRow == null) {
                val local = store.state.first()
                if (local.follows.isNotEmpty() || local.teams.isNotEmpty() || local.prefs != RadarPrefs()) pushToRemote()
                return@withContext Result.success(Unit)
            }
            isSyncingFromRemote = true
            store.saveState(
                RadarLocalState(
                    follows = followRows.sortedBy { it.sortOrder }
                        .map {
                            RadarFollow(
                                leagueId = it.leagueId,
                                sport = it.sport,
                                sortOrder = it.sortOrder,
                                name = it.name,
                                badge = it.badge,
                                banner = it.banner,
                                keywords = it.keywords.orEmpty(),
                                custom = it.custom,
                            )
                        },
                    prefs = prefsRow?.let { RadarPrefs(it.featuredEventId, it.optInState, it.promoDismissed) }
                        ?: store.state.first().prefs,
                    teams = teamRows.sortedBy { it.sortOrder }
                        .map {
                            RadarTeamFollow(
                                teamId = it.teamId,
                                name = it.name,
                                sport = it.sport,
                                badge = it.badge,
                                leagueId = it.leagueId,
                                league = it.league,
                                keywords = it.keywords.orEmpty(),
                                sortOrder = it.sortOrder,
                            )
                        },
                )
            )
            isSyncingFromRemote = false
            Log.d(TAG, "Pulled ${followRows.size} radar follows, ${teamRows.size} teams for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            isSyncingFromRemote = false
            Log.e(TAG, "Failed to pull radar state", e)
            Result.failure(e)
        }
    }
}

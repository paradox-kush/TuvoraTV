package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AniListSettings
import com.nuvio.tv.data.local.AniListSettingsDataStore
import com.nuvio.tv.data.local.KitsuSettings
import com.nuvio.tv.data.local.KitsuSettingsDataStore
import com.nuvio.tv.data.local.MalSettings
import com.nuvio.tv.data.local.MalSettingsDataStore
import com.nuvio.tv.data.remote.supabase.ProfileTrackerSettingsRow
import com.nuvio.tv.domain.model.TrackerListStatus
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs per-profile anime-tracker catalog preferences (enabled list-row
 * statuses, row ordering, send-progress toggle) between devices via
 * Supabase `profile_tracker_settings`. Additive: old app versions never
 * touch these rows, so cross-version devices do not step on each other.
 *
 * Push is debounced 1500 ms per tracker on local change. Pull happens once
 * on startup via [com.nuvio.tv.core.sync.StartupSyncService].
 */
@Singleton
class TrackerSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager,
    private val malSettings: MalSettingsDataStore,
    private val aniListSettings: AniListSettingsDataStore,
    private val kitsuSettings: KitsuSettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var applyingRemote: Boolean = false

    companion object {
        private const val TAG = "TrackerSettingsSync"
        private const val PUSH_DEBOUNCE_MS = 1500L
        const val TRACKER_MAL = "mal"
        const val TRACKER_ANILIST = "anilist"
        const val TRACKER_KITSU = "kitsu"
    }

    init {
        observeAndPush()
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T =
        try { block() } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }

    /** Pull and apply all three trackers' settings for the active profile. */
    suspend fun pullSettingsForActiveProfile(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!authManager.isAuthenticated) return@withContext Result.success(Unit)
        try {
            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject { put("p_profile_id", profileId) }
            val response = withJwtRefreshRetry {
                postgrest.rpc("get_profile_tracker_settings", params)
            }
            val rows = response.decodeList<ProfileTrackerSettingsRow>()
            Log.i(TAG, "pulled ${rows.size} tracker settings row(s) for profile $profileId")
            applyingRemote = true
            try {
                rows.forEach { applyRow(it) }
            } finally {
                applyingRemote = false
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "pullSettingsForActiveProfile failed", e)
            Result.failure(e)
        }
    }

    private suspend fun applyRow(row: ProfileTrackerSettingsRow) {
        val enabled = row.enabledStatuses.mapNotNullTo(mutableSetOf()) { parseStatus(it) }
        val order = row.rowOrder.mapNotNull { parseStatus(it) }
            .ifEmpty { TrackerListStatus.values().toList() }
        when (row.tracker) {
            TRACKER_MAL -> malSettings.replaceAll(enabled, order, row.sendProgress)
            TRACKER_ANILIST -> aniListSettings.replaceAll(enabled, order, row.sendProgress)
            TRACKER_KITSU -> kitsuSettings.replaceAll(enabled, order, row.sendProgress)
            else -> Log.w(TAG, "ignoring unknown tracker ${row.tracker}")
        }
    }

    private fun parseStatus(name: String): TrackerListStatus? =
        runCatching { TrackerListStatus.valueOf(name) }.getOrNull()

    private fun observeAndPush() {
        scope.launch { observeTracker(TRACKER_MAL) { malSettings.settings } }
        scope.launch { observeTracker(TRACKER_ANILIST) { aniListSettings.settings } }
        scope.launch { observeTracker(TRACKER_KITSU) { kitsuSettings.settings } }
    }

    private suspend fun observeTracker(
        tracker: String,
        flowProvider: () -> kotlinx.coroutines.flow.Flow<Any>
    ) {
        flowProvider()
            .drop(1) // first emission on subscribe is the current value — not a change.
            .distinctUntilChanged()
            .debounce(PUSH_DEBOUNCE_MS)
            .collect { settings ->
                if (applyingRemote) return@collect
                if (!authManager.isAuthenticated) return@collect
                val (enabled, order, sendProgress) = destructure(settings) ?: return@collect
                pushSettings(tracker, enabled, order, sendProgress)
            }
    }

    private data class Snapshot(
        val enabled: Set<TrackerListStatus>,
        val order: List<TrackerListStatus>,
        val sendProgress: Boolean
    )

    private fun destructure(settings: Any): Snapshot? = when (settings) {
        is MalSettings -> Snapshot(settings.enabledStatuses, settings.rowOrder, settings.sendProgress)
        is AniListSettings -> Snapshot(settings.enabledStatuses, settings.rowOrder, settings.sendProgress)
        is KitsuSettings -> Snapshot(settings.enabledStatuses, settings.rowOrder, settings.sendProgress)
        else -> null
    }

    private suspend fun pushSettings(
        tracker: String,
        enabled: Set<TrackerListStatus>,
        order: List<TrackerListStatus>,
        sendProgress: Boolean
    ) {
        try {
            val profileId = profileManager.activeProfileId.value
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_tracker", tracker)
                put("p_enabled_statuses", jsonArrayOfStrings(enabled.map { it.name }))
                put("p_row_order", jsonArrayOfStrings(order.map { it.name }))
                put("p_send_progress", sendProgress)
            }
            withJwtRefreshRetry { postgrest.rpc("upsert_profile_tracker_settings", params) }
            Log.d(TAG, "pushed $tracker settings (profile=$profileId enabled=${enabled.size})")
        } catch (e: Exception) {
            Log.w(TAG, "pushSettings($tracker) failed", e)
        }
    }

    private fun jsonArrayOfStrings(values: List<String>): JsonArray =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}

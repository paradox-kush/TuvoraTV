package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.TrackerListStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile user preferences for the MyAnimeList integration: which lists
 * render as home rows, row ordering, and whether progress writes go out.
 *
 * Mirrors [AniListSettingsDataStore] and [KitsuSettingsDataStore] deliberately —
 * each tracker has its own file because their identity (username, tokens,
 * settings) is independent and should live next to its auth store on disk.
 */
data class MalSettings(
    val enabledStatuses: Set<TrackerListStatus> = emptySet(),
    val rowOrder: List<TrackerListStatus> = TrackerListStatus.values().toList(),
    val sendProgress: Boolean = true
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MalSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object { private const val FEATURE = "mal_settings" }

    private val enabledStatusesKey = stringSetPreferencesKey("enabled_statuses")
    private val rowOrderKey = stringPreferencesKey("row_order")
    private val sendProgressKey = booleanPreferencesKey("send_progress")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val settings: Flow<MalSettings> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { p ->
            val enabled = p[enabledStatusesKey].orEmpty()
                .mapNotNull { runCatching { TrackerListStatus.valueOf(it) }.getOrNull() }
                .toSet()
            val order = p[rowOrderKey].orEmpty().split(",").asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { TrackerListStatus.valueOf(it) }.getOrNull() }
                .toList()
                .ifEmpty { TrackerListStatus.values().toList() }
            MalSettings(
                enabledStatuses = enabled,
                rowOrder = order,
                sendProgress = p[sendProgressKey] ?: true
            )
        }
    }

    suspend fun setStatusEnabled(status: TrackerListStatus, enabled: Boolean) {
        store().edit { p ->
            val current = p[enabledStatusesKey].orEmpty().toMutableSet()
            if (enabled) current += status.name else current -= status.name
            p[enabledStatusesKey] = current
        }
    }

    suspend fun setRowOrder(order: List<TrackerListStatus>) {
        store().edit { p -> p[rowOrderKey] = order.joinToString(",") { it.name } }
    }

    suspend fun setSendProgress(enabled: Boolean) {
        store().edit { p -> p[sendProgressKey] = enabled }
    }
}

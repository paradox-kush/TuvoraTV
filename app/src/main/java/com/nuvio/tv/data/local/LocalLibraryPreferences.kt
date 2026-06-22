package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile-scoped DataStore for the list of configured local library sources.
 * Secrets are stored separately in [com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore].
 */
@Singleton
class LocalLibraryPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "local_library_sources"
    }

    private val gson = Gson()
    private val sourcesKey = stringPreferencesKey("sources_json")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val sources: Flow<List<LocalLibrarySourceConfig>> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> parse(prefs[sourcesKey]) }
        }

    suspend fun upsert(config: LocalLibrarySourceConfig) {
        store().edit { prefs ->
            val current = parse(prefs[sourcesKey]).toMutableList()
            val idx = current.indexOfFirst { it.id == config.id }
            if (idx >= 0) current[idx] = config else current.add(config)
            prefs[sourcesKey] = gson.toJson(current)
        }
    }

    suspend fun remove(sourceId: String) {
        store().edit { prefs ->
            val current = parse(prefs[sourcesKey]).filterNot { it.id == sourceId }
            prefs[sourcesKey] = gson.toJson(current)
        }
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) =
        mutate(sourceId) { it.copy(enabled = enabled) }

    suspend fun setScanResult(sourceId: String, itemCount: Int, matchedCount: Int, lastScanAt: Long) =
        mutate(sourceId) { it.copy(itemCount = itemCount, matchedCount = matchedCount, lastScanAt = lastScanAt) }

    private suspend fun mutate(
        sourceId: String,
        transform: (LocalLibrarySourceConfig) -> LocalLibrarySourceConfig
    ) {
        store().edit { prefs ->
            val current = parse(prefs[sourcesKey]).toMutableList()
            val idx = current.indexOfFirst { it.id == sourceId }
            if (idx < 0) return@edit
            current[idx] = transform(current[idx])
            prefs[sourcesKey] = gson.toJson(current)
        }
    }

    private fun parse(json: String?): List<LocalLibrarySourceConfig> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<LocalLibrarySourceConfig>>() {}.type
            gson.fromJson<List<LocalLibrarySourceConfig>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

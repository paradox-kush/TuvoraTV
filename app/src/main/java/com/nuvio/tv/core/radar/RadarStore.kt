package com.nuvio.tv.core.radar

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists Sports Centre state per profile: follows+prefs (synced) and the last-fetched
 * fixtures payload (offline cache, device-local). Mirrors XtreamAccountStore's
 * list-in-DataStore pattern, kotlinx JSON instead of Gson (models are @Serializable).
 */
@Singleton
class RadarStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateKey = stringPreferencesKey("radar_state")
    private val fixturesKey = stringPreferencesKey("radar_fixtures")

    private fun store(pid: Int = profileManager.activeProfileId.value) = factory.get(pid, FEATURE)

    /** Follows + featured prefs for the ACTIVE profile — switches automatically on profile change. */
    val state: Flow<RadarLocalState> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> parseState(prefs[stateKey]) }
    }

    suspend fun saveState(state: RadarLocalState) {
        store().edit { prefs -> prefs[stateKey] = json.encodeToString(state) }
    }

    suspend fun loadFixtures(): RadarFixturesResponse? =
        runCatching {
            store().data.first()[fixturesKey]?.let { json.decodeFromString<RadarFixturesResponse>(it) }
        }.getOrNull()

    suspend fun saveFixtures(response: RadarFixturesResponse) {
        store().edit { prefs -> prefs[fixturesKey] = json.encodeToString(response) }
    }

    private fun parseState(stored: String?): RadarLocalState {
        if (stored.isNullOrBlank()) return RadarLocalState()
        return runCatching { json.decodeFromString<RadarLocalState>(stored) }.getOrDefault(RadarLocalState())
    }

    companion object {
        private const val FEATURE = "radar"
    }
}

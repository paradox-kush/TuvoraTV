package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.announcements.AnnouncementCodec
import com.nuvio.tv.domain.model.Announcement
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.announcementDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_announcements",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * Per-device (not per-profile, not synced) announcement state: the last fetched list, when it was
 * fetched, and which announcement ids this device dismissed.
 */
@Singleton
class AnnouncementDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.announcementDataStore
    private val cachedJsonKey = stringPreferencesKey("cached_json")
    private val lastFetchedAtKey = longPreferencesKey("last_fetched_at_ms")
    private val dismissedIdsKey = stringSetPreferencesKey("dismissed_ids")

    val cached: Flow<List<Announcement>> = dataStore.data.map { prefs ->
        prefs[cachedJsonKey]
            ?.let { runCatching { AnnouncementCodec.decode(it) }.getOrNull() }
            .orEmpty()
    }

    val dismissedIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[dismissedIdsKey].orEmpty()
    }

    suspend fun lastFetchedAtMs(): Long? = dataStore.data.first()[lastFetchedAtKey]

    suspend fun saveFetched(items: List<Announcement>, fetchedAtMs: Long) {
        dataStore.edit { prefs ->
            prefs[cachedJsonKey] = AnnouncementCodec.encode(items)
            prefs[lastFetchedAtKey] = fetchedAtMs
        }
    }

    suspend fun addDismissed(id: String) {
        dataStore.edit { prefs ->
            prefs[dismissedIdsKey] = prefs[dismissedIdsKey].orEmpty() + id
        }
    }
}

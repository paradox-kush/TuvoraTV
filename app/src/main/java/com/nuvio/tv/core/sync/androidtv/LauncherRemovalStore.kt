package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.recommendations.LauncherRemovalPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.launcherRemovalDataStore by preferencesDataStore(
    name = "tv_launcher_removals",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/**
 * Items the viewer removed from the TV home screen, with when. Device-local and never synced:
 * what a TV shows on its own home screen is a decision about that TV.
 */
@Singleton
class LauncherRemovalStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val removedKey = stringSetPreferencesKey("removed")

    suspend fun all(): Map<String, Long> =
        LauncherRemovalPolicy.decode(context.launcherRemovalDataStore.data.map { it[removedKey].orEmpty() }.first())

    suspend fun record(internalId: String, atMs: Long) {
        context.launcherRemovalDataStore.edit { prefs ->
            val current = LauncherRemovalPolicy.decode(prefs[removedKey].orEmpty())
            prefs[removedKey] = LauncherRemovalPolicy.encode(
                LauncherRemovalPolicy.recordRemoval(current, internalId, atMs)
            )
        }
    }
}

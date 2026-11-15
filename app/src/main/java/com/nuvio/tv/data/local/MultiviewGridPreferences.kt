package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.multiview.MultiviewGridCodec
import com.nuvio.tv.core.multiview.MultiviewGridStore
import com.nuvio.tv.core.multiview.SavedGrid
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Resume last grid": the layout and the channel keys of the last multiview grid, per profile, on this device only.
 * Keys are channel ids; no stream link, host or account secret is stored, and nothing is synced.
 */
@Singleton
internal class MultiviewGridPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) : MultiviewGridStore {
    private val gridKey = stringPreferencesKey("last_grid")

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    override suspend fun load(): SavedGrid? =
        MultiviewGridCodec.decode(store().data.first()[gridKey])

    override suspend fun save(grid: SavedGrid?) {
        store().edit { prefs ->
            if (grid == null || grid.keys.none { it != null }) prefs.remove(gridKey)
            else prefs[gridKey] = MultiviewGridCodec.encode(grid)
        }
    }

    private companion object {
        const val FEATURE = "multiview_grid"
    }
}

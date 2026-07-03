package com.nuvio.tv.core.iptv.refresh

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.iptvRefreshDataStore: DataStore<Preferences> by preferencesDataStore(name = "iptv_refresh")

/**
 * Per-playlist "last auto-refresh checked" timestamps for Xtream playlists, which — unlike M3U — have
 * no ingest and therefore no `ingest_meta.built_at` to reuse. Keyed by [com.nuvio.tv.core.iptv.XtreamAccount.id]
 * (globally unique = baseUrl|username), so it is profile-agnostic like the content DB. Epoch-ms.
 *
 * M3U playlists do NOT use this — their last-refresh is [com.nuvio.tv.core.iptv.content.IptvContentDb.builtAt].
 */
@Singleton
class IptvRefreshStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.iptvRefreshDataStore

    private fun keyFor(playlistId: String) = longPreferencesKey("checked_$playlistId")

    /** The last-checked epoch-ms for [playlistId], or null if never checked. */
    suspend fun lastCheckedMs(playlistId: String): Long? =
        dataStore.data.first()[keyFor(playlistId)]

    /** Record [playlistId] as checked at [atMs] (defaults to now). */
    suspend fun markChecked(playlistId: String, atMs: Long = System.currentTimeMillis()) {
        dataStore.edit { it[keyFor(playlistId)] = atMs }
    }

    /** Drop a playlist's timestamp when it's removed (keeps the store from leaking stale keys). */
    suspend fun clear(playlistId: String) {
        dataStore.edit { it.remove(keyFor(playlistId)) }
    }
}

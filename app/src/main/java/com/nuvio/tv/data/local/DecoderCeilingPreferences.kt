package com.nuvio.tv.data.local

import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.multiview.DecoderCeilingStore
import com.nuvio.tv.core.multiview.TileCodec
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multiview's decoder ledger: "this many tiles at once was the most this box managed", per device model and codec.
 * It describes the hardware, not the viewer, so it lives with the first profile's preferences and is never synced.
 */
@Singleton
internal class DecoderCeilingPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
) : DecoderCeilingStore {
    private fun store() = factory.get(1, FEATURE)

    private fun key(codec: TileCodec) = intPreferencesKey("${Build.MODEL.orEmpty().ifBlank { "unknown" }}:${codec.name}")

    override suspend fun load(): Map<TileCodec, Int> {
        val prefs = store().data.first()
        return TileCodec.entries.mapNotNull { codec -> prefs[key(codec)]?.let { codec to it } }.toMap()
    }

    override suspend fun save(codec: TileCodec, comfortable: Int) {
        store().edit { it[key(codec)] = comfortable }
    }

    private companion object {
        const val FEATURE = "multiview_decoder_ceiling"
    }
}

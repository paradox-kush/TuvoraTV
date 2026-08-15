package com.nuvio.tv.core.iptv

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk for [CatchUpWinnerStore].
 *
 * SharedPreferences rather than the DataStore everything else uses, for two reasons: this map is
 * read synchronously on the first replay of a session (a DataStore read would have to be awaited
 * before the URL could be built), and it is device-local knowledge about one panel's URL dialect —
 * nothing a viewer would want followed to another device or another profile.
 */
@Singleton
class CatchUpWinnerPrefs @Inject constructor(
    @ApplicationContext context: Context,
) : CatchUpWinnerStore.Persistence {

    private val prefs = context.getSharedPreferences("catchup_dialect_winners", Context.MODE_PRIVATE)

    override fun load(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun save(entries: Map<String, String>) {
        prefs.edit {
            clear()
            entries.forEach { (k, v) -> putString(k, v) }
        }
    }
}

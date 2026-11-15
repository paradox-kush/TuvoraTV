package com.nuvio.tv.core.iptv

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.guideDisplayStore by preferencesDataStore(name = "tv_guide_display")

/** Device-local display preference: no account sync or network work. */
class GuideDisplayPreferences(context: Context) {
    private val store = context.applicationContext.guideDisplayStore
    private val layoutKey = stringPreferencesKey("layout")
    val layout = store.data.catch { failure ->
        if (failure is IOException) emit(emptyPreferences()) else throw failure
    }.map { prefs -> GuideLayoutPreset.fromStored(prefs[layoutKey]) }
    suspend fun setLayout(layout: GuideLayoutPreset) { store.edit { it[layoutKey] = layout.name } }

    /** A4 (IPTV redesign 2026-11-15): resume the last channel when the guide opens. Opt-in, off by default. */
    private val resumeOnOpenKey = booleanPreferencesKey("resume_on_open")
    val resumeOnOpen = store.data.catch { failure ->
        if (failure is IOException) emit(emptyPreferences()) else throw failure
    }.map { prefs -> prefs[resumeOnOpenKey] ?: com.nuvio.tv.ui.screens.iptv.GuideOptionsPolicy.RESUME_ON_OPEN_DEFAULT }
    suspend fun setResumeOnOpen(on: Boolean) { store.edit { it[resumeOnOpenKey] = on } }
}

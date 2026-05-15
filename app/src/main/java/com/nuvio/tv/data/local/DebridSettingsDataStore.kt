package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.debrid.DebridStreamFormatterDefaults
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.DebridSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "debrid_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("debrid_enabled")
    private val torboxApiKeyKey = stringPreferencesKey("torbox_api_key")
    private val realDebridApiKeyKey = stringPreferencesKey("real_debrid_api_key")
    private val streamNameTemplateKey = stringPreferencesKey("debrid_stream_name_template")
    private val streamDescriptionTemplateKey = stringPreferencesKey("debrid_stream_description_template")

    val settings: Flow<DebridSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            DebridSettings(
                enabled = prefs[enabledKey] ?: false,
                torboxApiKey = prefs[torboxApiKeyKey] ?: "",
                realDebridApiKey = prefs[realDebridApiKeyKey] ?: "",
                streamNameTemplate = prefs[streamNameTemplateKey]
                    ?: DebridStreamFormatterDefaults.NAME_TEMPLATE,
                streamDescriptionTemplate = prefs[streamDescriptionTemplateKey]
                    ?: DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setTorboxApiKey(apiKey: String) {
        store().edit { it[torboxApiKeyKey] = apiKey.trim() }
    }

    suspend fun setRealDebridApiKey(apiKey: String) {
        store().edit { it[realDebridApiKeyKey] = apiKey.trim() }
    }

    suspend fun setStreamTemplates(nameTemplate: String, descriptionTemplate: String) {
        store().edit {
            it[streamNameTemplateKey] = nameTemplate
            it[streamDescriptionTemplateKey] = descriptionTemplate
        }
    }

    suspend fun resetStreamTemplates() {
        setStreamTemplates(
            nameTemplate = DebridStreamFormatterDefaults.NAME_TEMPLATE,
            descriptionTemplate = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE
        )
    }
}

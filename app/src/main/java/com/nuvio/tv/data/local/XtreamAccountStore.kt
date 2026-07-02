package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the list of configured Xtream IPTV accounts, profile-scoped, as JSON.
 * Mirrors [AddonPreferences]' list-in-DataStore pattern.
 *
 * ponytail: credentials stored in plaintext, same as the existing Debrid API keys.
 * Encrypt-at-rest is the upgrade path if the app ever adds it for Debrid too.
 */
@Singleton
class XtreamAccountStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val accountsKey = stringPreferencesKey("xtream_accounts")

    private fun store(pid: Int = profileManager.activeProfileId.value) = factory.get(pid, FEATURE)

    val accounts: Flow<List<XtreamAccount>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> parse(prefs[accountsKey]) }
    }

    /** Insert or replace by id (id = baseUrl|username). */
    suspend fun upsert(account: XtreamAccount) {
        store().edit { prefs ->
            val current = parse(prefs[accountsKey]).toMutableList()
            val i = current.indexOfFirst { it.id == account.id }
            if (i >= 0) current[i] = account else current.add(account)
            prefs[accountsKey] = gson.toJson(current)
        }
    }

    /** Swap the account stored under oldId in place (URL/creds edit), keeping list position. */
    suspend fun replace(oldId: String, account: XtreamAccount) {
        store().edit { prefs ->
            val updated = parse(prefs[accountsKey])
                .filterNot { it.id == account.id && it.id != oldId } // drop a pre-existing duplicate of the new identity
                .map { if (it.id == oldId) account else it }
            prefs[accountsKey] = gson.toJson(updated)
        }
    }

    suspend fun remove(id: String) {
        store().edit { prefs ->
            prefs[accountsKey] = gson.toJson(parse(prefs[accountsKey]).filterNot { it.id == id })
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        store().edit { prefs ->
            val updated = parse(prefs[accountsKey]).map { if (it.id == id) it.copy(enabled = enabled) else it }
            prefs[accountsKey] = gson.toJson(updated)
        }
    }

    /** Replace all accounts for the active profile (used when applying a remote pull). */
    suspend fun replaceAll(accounts: List<XtreamAccount>) {
        store().edit { prefs -> prefs[accountsKey] = gson.toJson(accounts) }
    }

    private fun parse(json: String?): List<XtreamAccount> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<XtreamAccount>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val FEATURE = "xtream_accounts"
    }
}

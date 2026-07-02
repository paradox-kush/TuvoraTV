package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.iptv.CategorySelections
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

    private fun parse(json: String?): List<XtreamAccount> = decodeXtreamAccountsJson(gson, json)

    companion object {
        private const val FEATURE = "xtream_accounts"
    }
}

/** Decodes the persisted account list. Extracted so the decode-defaults behavior is unit-testable. */
internal fun decodeXtreamAccountsJson(gson: Gson, json: String?): List<XtreamAccount> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val raw = JsonParser.parseString(json).asJsonArray
        val decoded = gson.fromJson<List<XtreamAccount>>(json, object : TypeToken<List<XtreamAccount>>() {}.type)
            ?: return emptyList()
        // Same-source array → same order/size; the raw element tells us whether a primitive field
        // was actually present (Gson can't distinguish a missing Int from 0).
        decoded.mapIndexed { i, acc ->
            val hadAutoRefresh = raw[i].asJsonObject.has("autoRefreshHours")
            acc.withDecodeDefaults(hadAutoRefresh)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Gson instantiates [XtreamAccount] via Unsafe (no no-arg constructor), so Kotlin constructor
 * defaults do NOT apply — fields missing from previously-persisted JSON come back null even on
 * non-null types. Re-apply the defaults here. The elvis operators look useless to the compiler
 * but are load-bearing at runtime. (Can't use copy(): its non-null params null-check the current
 * field values and would throw.)
 */
@Suppress("USELESS_ELVIS")
private fun XtreamAccount.withDecodeDefaults(hadAutoRefresh: Boolean): XtreamAccount = XtreamAccount(
    id = id,
    name = name ?: "",
    baseUrl = baseUrl,
    username = username,
    password = password,
    enabled = enabled,
    sourceType = sourceType ?: XtreamAccount.SOURCE_XTREAM,
    epgUrl = epgUrl,
    dnsProvider = dnsProvider ?: XtreamAccount.DNS_SYSTEM,
    // Missing (pre-playlist-manager JSON) → the 24h default, like a freshly-added playlist;
    // present → keep the stored value (incl. a deliberate 0 = Off).
    autoRefreshHours = if (hadAutoRefresh) autoRefreshHours else XtreamAccount.DEFAULT_AUTO_REFRESH_HOURS,
    contentTypes = contentTypes ?: XtreamAccount.DEFAULT_CONTENT_TYPES,
    categorySelections = categorySelections ?: CategorySelections()
)

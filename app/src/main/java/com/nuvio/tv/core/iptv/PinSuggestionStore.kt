package com.nuvio.tv.core.iptv

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.contracts.LivePlayRecorder
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live TV play counts and the pin-suggestion switch/snooze, per profile, ON THIS DEVICE ONLY.
 *
 * Its own small DataStore file ([FEATURE]); nothing here is read by any sync service or sent anywhere. The log is
 * capped (see [PlayCountLog]) and cleared with [clearPlayCounts]. The decision of what to suggest is
 * [PinSuggestionPolicy]; this class only persists its inputs.
 */
@Singleton
class PinSuggestionStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) : LivePlayRecorder {
    private val gson = Gson()
    private val playsKey = stringPreferencesKey("live_play_counts")
    private val enabledKey = booleanPreferencesKey("suggest_pins_enabled")
    private val snoozedUntilKey = longPreferencesKey("suggest_pins_snoozed_until")

    data class Snapshot(val plays: Map<String, List<Long>>, val prefs: PinSuggestionPolicy.Prefs)

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)
    private fun activeProfile() = profileManager.activeProfileId.value

    /** Whether the active profile wants suggestions (default on); drives the settings switch. */
    val enabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { it[enabledKey] ?: true }
    }

    /** A live play of [channelId] by [profileId] (the profile that was watching, not whoever is active now). */
    override suspend fun recordPlay(profileId: Int, channelId: String) = recordPlay(profileId, channelId, System.currentTimeMillis())

    suspend fun recordPlay(profileId: Int, channelId: String, nowMs: Long) {
        if (profileId <= 0 || channelId.isBlank()) return
        store(profileId).edit { prefs ->
            val next = PlayCountLog.record(parse(prefs[playsKey]), channelId, nowMs)
            prefs[playsKey] = gson.toJson(next)
        }
    }

    override suspend fun recordPlay(channelId: String) = recordPlay(activeProfile(), channelId, System.currentTimeMillis())

    suspend fun snapshot(profileId: Int = activeProfile()): Snapshot {
        val prefs = store(profileId).data.first()
        return Snapshot(
            plays = parse(prefs[playsKey]),
            prefs = PinSuggestionPolicy.Prefs(
                enabled = prefs[enabledKey] ?: true,
                snoozedUntilMs = prefs[snoozedUntilKey] ?: 0L,
            ),
        )
    }

    /** "Stop suggesting" (false) / the settings switch. Turning it back on also ends any snooze. */
    suspend fun setEnabled(enabled: Boolean, profileId: Int = activeProfile()) {
        store(profileId).edit {
            it[enabledKey] = enabled
            if (enabled) it.remove(snoozedUntilKey)
        }
    }

    /** "Not now": hide the line for the policy's snooze. */
    suspend fun snooze(nowMs: Long = System.currentTimeMillis(), profileId: Int = activeProfile()) {
        store(profileId).edit { it[snoozedUntilKey] = PinSuggestionPolicy.snoozedUntil(nowMs) }
    }

    /** "Clear play counts": forgets the log. The switch and snooze, pins and favourites are untouched. */
    suspend fun clearPlayCounts(profileId: Int = activeProfile()) {
        store(profileId).edit { it.remove(playsKey) }
    }

    private fun parse(json: String?): Map<String, List<Long>> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            gson.fromJson<Map<String, List<Long>>>(json, object : TypeToken<Map<String, List<Long>>>() {}.type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        /** Its own DataStore file: not the IPTV account file, which the playlist sync touches. */
        const val FEATURE = "live_pin_suggestions"
    }
}

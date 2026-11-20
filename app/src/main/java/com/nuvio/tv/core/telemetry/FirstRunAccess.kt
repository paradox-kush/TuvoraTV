package com.nuvio.tv.core.telemetry

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.WATCH_PROGRESS_ARCHIVE_FEATURE
import com.nuvio.tv.data.local.WATCH_PROGRESS_RECENT_FEATURE
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.watchProgressEntriesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * What call sites use: one line at each chokepoint, a no-op until [TelemetryService.start]ed (tests, headless
 * starts). The once-gate, consent and new-vs-existing decision all live behind it ([FirstRunFunnelService]).
 */
object FirstRun {
    @Volatile internal var service: FirstRunFunnelService? = null

    /** An IPTV playlist, add-on or media server now exists on this install. */
    fun sourceAdded(kind: String, via: String) =
        step(FirstRunStep.SOURCE_ADDED, mapOf("source_kind" to kind, "via" to via))

    /** An IPTV load ended `loaded` on [surface] (the `iptv_load` surface key). */
    fun contentLoaded(surface: String) = step(FirstRunStep.FIRST_CONTENT_LOADED, mapOf("surface" to surface))

    /** Playback produced output (the `playback_started` chokepoint). */
    fun playback(kind: String, engine: String) = step(FirstRunStep.FIRST_PLAYBACK, mapOf("kind" to kind, "engine" to engine))

    private fun step(step: FirstRunStep, props: Map<String, Any>) {
        runCatching { service?.step(step, props) }
    }
}

/** The funnel state in the telemetry SharedPreferences, beside `support_code`; `applyConsent(false)` wipes `files/telemetry/`, not this. */
internal class PrefsFirstRunStateStore(context: Context) : FirstRunStateStore {
    private val prefs = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    // commit(): the once-gate must survive a kill right after the step.
    override fun write(value: String) { prefs.edit().putString(KEY, value).commit() }

    companion object {
        const val KEY = "first_run_funnel"
    }
}

/** The Preferences of profile [profileId]'s store [feature], or null when it has none. Injectable so the probe tests without DataStore. */
internal fun interface ProfilePrefsReader {
    fun read(profileId: Int, feature: String): Preferences?
}

/**
 * "Does this install already hold user state?" for the first-run funnel: any playlist, user add-on or watch
 * history/progress in ANY profile slot, straight from the stored blobs (no repository load, no network).
 * The support-code signal is read by the telemetry service before it generates one.
 */
internal class FirstRunExistingInstallProbe(
    private val profileIds: IntRange = 1..ProfileManager.MAX_PROFILES,
    private val reader: ProfilePrefsReader,
) {
    private val accountsKey = stringPreferencesKey(XtreamAccountStore.ACCOUNTS_KEY_NAME)
    private val orderedAddonsKey = stringPreferencesKey(AddonPreferences.ORDERED_URLS_KEY_NAME)
    private val legacyAddonsKey = stringSetPreferencesKey(AddonPreferences.LEGACY_URLS_KEY_NAME)
    private val watchedKey = stringSetPreferencesKey(WatchedItemsPreferences.ITEMS_KEY_NAME)

    fun hasUserState(): Boolean = profileIds.any { pid ->
        FirstRunInstallSignals.playlistsPresent(reader.read(pid, XtreamAccountStore.FEATURE)?.get(accountsKey)) ||
            hasUserAddons(reader.read(pid, AddonPreferences.FEATURE)) ||
            FirstRunInstallSignals.hasEntries(reader.read(pid, WATCH_PROGRESS_RECENT_FEATURE)?.get(watchProgressEntriesKey)) ||
            FirstRunInstallSignals.hasEntries(reader.read(pid, WATCH_PROGRESS_ARCHIVE_FEATURE)?.get(watchProgressEntriesKey)) ||
            reader.read(pid, WatchedItemsPreferences.FEATURE)?.get(watchedKey).orEmpty().isNotEmpty()
    }

    private fun hasUserAddons(prefs: Preferences?): Boolean {
        if (prefs == null) return false
        val ordered = prefs[orderedAddonsKey]?.let { raw ->
            runCatching { com.google.gson.JsonParser.parseString(raw).asJsonArray.map { it.asString } }.getOrNull()
        }
        // A blob that will not parse is present but unreadable: something was saved.
        if (prefs[orderedAddonsKey] != null && ordered == null) return true
        val urls = ordered ?: prefs[legacyAddonsKey]?.toList().orEmpty()
        return FirstRunInstallSignals.userAddonUrls(urls, AddonPreferences.DEFAULT_ADDON_URLS).isNotEmpty()
    }

    companion object {
        /** Reads from the real DataStore files; a store whose file is not on disk is skipped (no file is created by probing). */
        fun forApp(context: Context, factory: ProfileDataStoreFactory): FirstRunExistingInstallProbe =
            FirstRunExistingInstallProbe(reader = ProfilePrefsReader { pid, feature ->
                val name = if (pid == 1) feature else "${feature}_p$pid"
                val file = File(context.filesDir, "datastore/$name.preferences_pb")
                if (!file.isFile || file.length() == 0L) null
                // A read that throws propagates: the service then reads "cannot tell" as an existing install (silence).
                else runBlocking(Dispatchers.IO) { factory.get(pid, feature).data.first() }
            })
    }
}

package com.nuvio.tv.core.telemetry

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.WATCH_PROGRESS_ARCHIVE_FEATURE
import com.nuvio.tv.data.local.WATCH_PROGRESS_RECENT_FEATURE
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.watchProgressEntriesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure first-run policy, the state codec, the stored-blob signals and the existing-install probe. JUnit order: (message, expected, actual). */
class FirstRunFunnelTest {

    private val t0 = 1_000_000L

    private fun newInstallStart(): FirstRunTransition = FirstRunFunnel.start(null, t0) { false }

    @Test
    fun newInstallEmitsFirstLaunchWithIndexOneAndZeroSeconds() {
        val t = newInstallStart()
        val props = checkNotNull(t.emit)
        assertEquals("step", "first_launch", props["step"])
        assertEquals("schema", 1, props["schema_version"])
        assertEquals("launch_index is 1 on the first launch", 1, props["launch_index"])
        assertEquals("secs is 0 for first_launch", 0, props["secs_since_first_launch"])
        assertEquals("first-launch time persisted", t0, t.state!!.firstLaunchAtMs)
        assertTrue("first_launch is marked done", "first_launch" in t.state!!.done)
        assertFalse("not an existing install", t.state!!.existing)
    }

    @Test
    fun existingInstallPersistsAllDoneAndEmitsNothingEver() {
        val t = FirstRunFunnel.start(null, t0) { true }
        assertNull("nothing emitted", t.emit)
        val state = checkNotNull(t.state)
        assertTrue("persisted as existing", state.existing)
        for (step in FirstRunStep.values()) {
            val r = FirstRunFunnel.record(state, step, t0 + 5_000, mapOf("source_kind" to "m3u", "via" to "manual"))
            assertNull("existing install never emits ${step.wire}", r.emit)
            assertNull("and never changes state for ${step.wire}", r.state)
        }
        assertNull("a later start emits nothing either", FirstRunFunnel.start(state, t0 + 1, { error("never asked once decided") }).emit)
    }

    @Test
    fun existingInstallQuestionIsOnlyAskedWithoutPersistedState() {
        val state = checkNotNull(newInstallStart().state)
        var asked = false
        val t = FirstRunFunnel.start(state, t0 + 10_000) { asked = true; true }
        assertFalse("not asked when state exists", asked)
        assertEquals("launch_index goes up per process start", 2, t.state!!.launchIndex)
        assertNull("no event on a later launch", t.emit)
    }

    @Test
    fun eachStepEmitsOnceAndNeverAgain() {
        var state = checkNotNull(newInstallStart().state)
        for (step in listOf(FirstRunStep.SOURCE_ADDED, FirstRunStep.FIRST_CONTENT_LOADED, FirstRunStep.FIRST_PLAYBACK, FirstRunStep.FIRST_LAUNCH)) {
            val first = FirstRunFunnel.record(state, step, t0 + 1_000, emptyMap())
            if (step == FirstRunStep.FIRST_LAUNCH) {
                assertNull("first_launch already done at start: never re-emitted", first.emit)
                continue
            }
            assertNotNull("${step.wire} emits the first time", first.emit)
            state = checkNotNull(first.state)
            assertNull("${step.wire} never twice", FirstRunFunnel.record(state, step, t0 + 2_000, emptyMap()).emit)
        }
    }

    @Test
    fun onceOnlyAcrossARestartFromPersistedState() {
        var state = checkNotNull(newInstallStart().state)
        state = checkNotNull(FirstRunFunnel.record(state, FirstRunStep.SOURCE_ADDED, t0 + 1_000, mapOf("source_kind" to "xtream", "via" to "manual")).state)
        // Process dies; the next start reads what was persisted.
        val reloaded = checkNotNull(FirstRunStateCodec.decode(FirstRunStateCodec.encode(state)))
        assertEquals("codec round-trips", state, reloaded)
        val restarted = checkNotNull(FirstRunFunnel.start(reloaded, t0 + 60_000) { error("decided already") }.state)
        assertNull("source_added does not fire again after a restart", FirstRunFunnel.record(restarted, FirstRunStep.SOURCE_ADDED, t0 + 61_000, emptyMap()).emit)
        assertNotNull("a step not yet done still fires after the restart", FirstRunFunnel.record(restarted, FirstRunStep.FIRST_PLAYBACK, t0 + 61_000, mapOf("kind" to "live", "engine" to "exo")).emit)
    }

    @Test
    fun laterStepsCarryLaunchIndexAndElapsedSeconds() {
        var state = checkNotNull(newInstallStart().state)
        state = checkNotNull(FirstRunFunnel.start(state, t0 + 5_000) { false }.state) // launch 2
        val props = checkNotNull(FirstRunFunnel.record(state, FirstRunStep.FIRST_CONTENT_LOADED, t0 + 125_999, mapOf("surface" to "hub_categories")).emit)
        assertEquals("launch index of the second launch", 2, props["launch_index"])
        assertEquals("elapsed whole seconds since first launch", 125, props["secs_since_first_launch"])
        assertEquals("surface", "hub_categories", props["surface"])
        assertEquals("step", "first_content_loaded", props["step"])
    }

    @Test
    fun aStepBeforeStartIsDropped() {
        assertNull("no state yet: nothing to emit", FirstRunFunnel.record(null, FirstRunStep.SOURCE_ADDED, t0, emptyMap()).emit)
    }

    @Test
    fun vocabulariesAreClosedAndNeverCarryFreeText() {
        val state = checkNotNull(newInstallStart().state)
        val src = checkNotNull(FirstRunFunnel.record(state, FirstRunStep.SOURCE_ADDED, t0, mapOf("source_kind" to "http://host.example/x", "via" to "Bob's list")).emit)
        assertEquals("unknown kind", "unknown", src["source_kind"])
        assertEquals("unknown via", "unknown", src["via"])
        val play = checkNotNull(FirstRunFunnel.record(state, FirstRunStep.FIRST_PLAYBACK, t0, mapOf("kind" to "movie", "engine" to "exo/http://h")).emit)
        assertEquals("kind outside live/vod", "unknown", play["kind"])
        assertEquals("engine cut at the first non-identifier char", "exo", play["engine"])
        val ok = checkNotNull(FirstRunFunnel.record(state, FirstRunStep.SOURCE_ADDED, t0, mapOf("source_kind" to "media_server", "via" to "setup_code")).emit)
        assertEquals("media_server kept", "media_server", ok["source_kind"])
        assertEquals("setup_code kept", "setup_code", ok["via"])
    }

    @Test
    fun noPropertyUsesARedactedName() {
        var state = checkNotNull(newInstallStart().state)
        val all = mutableListOf<Map<String, Any>>(checkNotNull(newInstallStart().emit))
        for ((step, extra) in listOf(
            FirstRunStep.SOURCE_ADDED to mapOf("source_kind" to "m3u", "via" to "manual"),
            FirstRunStep.FIRST_CONTENT_LOADED to mapOf("surface" to "hub_categories"),
            FirstRunStep.FIRST_PLAYBACK to mapOf("kind" to "vod", "engine" to "mpv"),
        )) {
            val t = FirstRunFunnel.record(state, step, t0, extra)
            state = checkNotNull(t.state); all += checkNotNull(t.emit)
        }
        val redacted = setOf("code", "state", "url", "token")
        for (props in all) for (key in props.keys) {
            assertFalse("property `$key` must not be a PostHogPrivacy-redacted name", key in redacted || key.endsWith("_url") || "token" in key)
        }
    }

    @Test
    fun playlistKindsMapFromTvSourceTypes() {
        assertEquals("xtream", "xtream", FirstRunVocab.kindOfPlaylist("xtream"))
        assertEquals("url is m3u", "m3u", FirstRunVocab.kindOfPlaylist("url"))
        assertEquals("file is m3u", "m3u", FirstRunVocab.kindOfPlaylist("file"))
        assertEquals("stalker", "stalker", FirstRunVocab.kindOfPlaylist("stalker"))
        assertEquals("anything else", "unknown", FirstRunVocab.kindOfPlaylist("mystery"))
        assertEquals("null", "unknown", FirstRunVocab.kindOfPlaylist(null))
    }

    @Test
    fun aDamagedStateBlobReadsAsNoState() {
        assertNull("garbage", FirstRunStateCodec.decode("{not json"))
        assertNull("blank", FirstRunStateCodec.decode("  "))
        assertNull("null", FirstRunStateCodec.decode(null))
        assertNull("missing the existing flag", FirstRunStateCodec.decode("""{"launchIndex":1}"""))
    }

    // ---- stored-blob signals ----

    @Test
    fun storedBlobSignals() {
        assertFalse("no playlists blob", FirstRunInstallSignals.playlistsPresent(null))
        assertFalse("empty array", FirstRunInstallSignals.playlistsPresent("[]"))
        assertTrue("one row", FirstRunInstallSignals.playlistsPresent("""[{"id":"a"}]"""))
        assertTrue("damaged but present counts", FirstRunInstallSignals.playlistsPresent("{oops"))
        assertFalse("empty progress map", FirstRunInstallSignals.hasEntries("{}"))
        assertTrue("one progress entry", FirstRunInstallSignals.hasEntries("""{"tt1":{"contentId":"tt1"}}"""))
        assertFalse("factory add-ons only", FirstRunInstallSignals.userAddonUrls(
            listOf("https://v3-cinemeta.strem.io/", "https://opensubtitles-v3.strem.io/manifest.json"), AddonPreferences.DEFAULT_ADDON_URLS).isNotEmpty())
        assertTrue("a user add-on", FirstRunInstallSignals.userAddonUrls(
            listOf("https://v3-cinemeta.strem.io", "https://my.addon.example/manifest.json"), AddonPreferences.DEFAULT_ADDON_URLS).isNotEmpty())
    }

    // ---- existing-install probe: each stored signal suppresses everything, in ANY profile ----

    private fun probe(vararg stored: Triple<Int, String, Preferences>) =
        FirstRunExistingInstallProbe(reader = ProfilePrefsReader { pid, feature -> stored.firstOrNull { it.first == pid && it.second == feature }?.third })

    @Test
    fun aFreshStoreIsNotAnExistingInstall() {
        assertFalse("nothing stored", probe().hasUserState())
        val factoryAddons = mutablePreferencesOf(stringPreferencesKey(AddonPreferences.ORDERED_URLS_KEY_NAME) to
            """["https://v3-cinemeta.strem.io","https://opensubtitles-v3.strem.io"]""")
        val emptyProgress = mutablePreferencesOf(watchProgressEntriesKey to "{}")
        assertFalse("the factory add-ons and an empty progress shell are what a bare launch writes", probe(
            Triple(1, AddonPreferences.FEATURE, factoryAddons),
            Triple(1, WATCH_PROGRESS_RECENT_FEATURE, emptyProgress),
            Triple(1, WATCH_PROGRESS_ARCHIVE_FEATURE, emptyProgress),
        ).hasUserState())
    }

    @Test
    fun anIptvPlaylistInAnyProfileSuppresses() {
        val prefs = mutablePreferencesOf(stringPreferencesKey(XtreamAccountStore.ACCOUNTS_KEY_NAME) to """[{"id":"a"}]""")
        assertTrue("profile 3", probe(Triple(3, XtreamAccountStore.FEATURE, prefs)).hasUserState())
    }

    @Test
    fun aUserAddonSuppresses() {
        val ordered = mutablePreferencesOf(stringPreferencesKey(AddonPreferences.ORDERED_URLS_KEY_NAME) to
            """["https://v3-cinemeta.strem.io","https://custom.example/manifest.json"]""")
        assertTrue("ordered list, profile 2", probe(Triple(2, AddonPreferences.FEATURE, ordered)).hasUserState())
        val legacy = mutablePreferencesOf(stringSetPreferencesKey(AddonPreferences.LEGACY_URLS_KEY_NAME) to setOf("https://custom.example"))
        assertTrue("legacy set", probe(Triple(1, AddonPreferences.FEATURE, legacy)).hasUserState())
    }

    @Test
    fun watchProgressOrHistorySuppresses() {
        val progress = mutablePreferencesOf(watchProgressEntriesKey to """{"tt1":{"contentId":"tt1"}}""")
        assertTrue("recent progress", probe(Triple(1, WATCH_PROGRESS_RECENT_FEATURE, progress)).hasUserState())
        assertTrue("archived progress, profile 4", probe(Triple(4, WATCH_PROGRESS_ARCHIVE_FEATURE, progress)).hasUserState())
        val watched = mutablePreferencesOf(stringSetPreferencesKey(WatchedItemsPreferences.ITEMS_KEY_NAME) to setOf("""{"contentId":"tt2"}"""))
        assertTrue("watched items", probe(Triple(1, WatchedItemsPreferences.FEATURE, watched)).hasUserState())
    }
}

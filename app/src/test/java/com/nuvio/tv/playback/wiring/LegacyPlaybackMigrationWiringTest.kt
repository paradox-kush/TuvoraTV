package com.nuvio.tv.playback.wiring

import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.settings.LegacyPlaybackPreferenceImporter
import com.nuvio.tv.playback.settings.PlaybackPreferenceDocument
import com.nuvio.tv.playback.settings.PlaybackPreferenceDocumentStore
import com.nuvio.tv.playback.settings.PlaybackPreferenceRepository
import com.nuvio.tv.playback.core.PlaybackCommand
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class LegacyPlaybackMigrationWiringTest {
    @Test
    fun `typed mapper covers every current PlayerSettings property`() {
        val productionFields = PlayerSettings::class.java.declaredFields
            .asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(70, productionFields.size)
        assertEquals(productionFields, LegacyPlayerSettingsSnapshotMapper.topLevelFieldNames)
        val mapped = LegacyPlayerSettingsSnapshotMapper.map(PlayerSettings(), "player-settings-v1")
        assertTrue(mapped.values.keys.containsAll(productionFields))
    }

    @Test
    fun `typed mapper captures nested import values and is detached from mutable legacy collections`() {
        val addons = linkedSetOf("addon-b", "addon-a")
        val settings = PlayerSettings(
            internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER,
            autoSwitchInternalPlayerOnError = true,
            streamAutoPlaySelectedAddons = addons,
            bufferSettings = BufferSettings(
                minBufferMs = 1_000,
                maxBufferMs = 9_000,
                bufferForPlaybackMs = 500,
                bufferForPlaybackAfterRebufferMs = 750,
                targetBufferSizeMb = 100,
                backBufferDurationMs = 2_000,
            ),
        )

        val mapped = LegacyPlayerSettingsSnapshotMapper.map(settings, "player-settings-v1")
        addons += "addon-c"

        assertEquals("MVP_PLAYER", mapped.values["internalPlayerEngine"])
        assertEquals("true", mapped.values["autoSwitchInternalPlayerOnError"])
        assertEquals("addon-a,addon-b", mapped.values["streamAutoPlaySelectedAddons"])
        assertEquals("1000", mapped.values["bufferSettings.minBufferMs"])
        assertEquals("9000", mapped.values["bufferSettings.maxBufferMs"])
        assertFalse(mapped.values.getValue("streamAutoPlaySelectedAddons").contains("addon-c"))
        assertFalse(mapped.toString().contains("addon-a"))
    }

    @Test
    fun `typed cutover entry point remains idempotent`() = runTest {
        val store = InMemoryStore()
        val repository = PlaybackPreferenceRepository(store)

        val first = repository.importTypedLegacyIfAbsent(
            profileId = "profile",
            settings = PlayerSettings(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER),
            importToken = "player-settings-v1",
        )
        val second = repository.importTypedLegacyIfAbsent(
            profileId = "profile",
            settings = PlayerSettings(internalPlayerEngine = InternalPlayerEngine.EXOPLAYER),
            importToken = "player-settings-v2",
        )

        assertTrue(first.imported)
        assertFalse(second.imported)
        assertEquals(1, store.writeCount)
        assertEquals("player-settings-v1", second.snapshot.legacyImportToken)
    }

    // Regression: PlayerSettings materializes EXOPLAYER / autoSwitch=false for keys the user
    // never stored. Importing those materialized defaults as explicit choices pinned every
    // untouched profile to Media3 with automatic fallback disabled — reverting the live libmpv
    // product default and disabling engine handoff (the emulator "render error" cascade).
    @Test
    fun `materialized engine and fallback defaults do not import without stored keys`() {
        val mapped = LegacyPlayerSettingsSnapshotMapper.map(
            PlayerSettings(),
            "player-settings-v1",
            storedRawKeyNames = emptySet(),
        )

        val imported = LegacyPlaybackPreferenceImporter.map(mapped)

        assertEquals(EnginePreference.AUTO, imported.preferences.playback.engine)
        assertTrue(imported.preferences.playback.automaticFallback)
    }

    @Test
    fun `stored engine and fallback keys import as explicit user choices`() {
        val mapped = LegacyPlayerSettingsSnapshotMapper.map(
            PlayerSettings(
                internalPlayerEngine = InternalPlayerEngine.EXOPLAYER,
                autoSwitchInternalPlayerOnError = false,
            ),
            "player-settings-v1",
            storedRawKeyNames = setOf(
                "internal_player_engine",
                "auto_switch_internal_player_on_error",
            ),
        )

        val imported = LegacyPlaybackPreferenceImporter.map(mapped)

        assertEquals(EnginePreference.MEDIA3, imported.preferences.playback.engine)
        assertFalse(imported.preferences.playback.automaticFallback)
    }

    @Test
    fun `mapper without stored key knowledge keeps the imported-as-stored fallback`() {
        val mapped = LegacyPlayerSettingsSnapshotMapper.map(PlayerSettings(), "player-settings-v1")

        val imported = LegacyPlaybackPreferenceImporter.map(mapped)

        assertEquals(EnginePreference.MEDIA3, imported.preferences.playback.engine)
        assertFalse(imported.preferences.playback.automaticFallback)
    }

    @Test
    fun `production bootstrap is profile scoped lazy and propagates the exact imported preference`() = runTest {
        val store = InMemoryStore()
        val repository = PlaybackPreferenceRepository(store)
        val requestedProfiles = mutableListOf<String>()
        val bootstrap = ProductionPlaybackPreferenceBootstrap(
            repository = repository,
            legacySource = { profileId ->
                requestedProfiles += profileId
                LegacyPlayerSettingsSnapshotMapper.map(
                    PlayerSettings(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER),
                    "player-settings-v1",
                )
            },
        )

        val profileTwo = bootstrap.load("2")
        val profileTwoAgain = bootstrap.load("2")
        val profileThree = bootstrap.load("3")

        assertEquals(listOf("2", "3"), requestedProfiles)
        assertTrue(profileTwo.importedLegacy)
        assertFalse(profileTwoAgain.importedLegacy)
        assertTrue(profileThree.importedLegacy)
        assertEquals(profileTwo.preferences, profileTwoAgain.preferences)
        assertEquals(
            PlaybackCommand.PreferencesChanged(profileTwo.preferences.playback),
            profileTwo.initialCommand(),
        )
    }

    private class InMemoryStore : PlaybackPreferenceDocumentStore {
        private val documents = mutableMapOf<String, PlaybackPreferenceDocument>()
        var writeCount: Int = 0

        override suspend fun read(profileId: String): PlaybackPreferenceDocument? = documents[profileId]

        override suspend fun write(profileId: String, document: PlaybackPreferenceDocument) {
            writeCount += 1
            documents[profileId] = document
        }
    }
}

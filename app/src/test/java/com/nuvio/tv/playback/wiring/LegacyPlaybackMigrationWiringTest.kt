package com.nuvio.tv.playback.wiring

import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.playback.settings.PlaybackPreferenceDocument
import com.nuvio.tv.playback.settings.PlaybackPreferenceDocumentStore
import com.nuvio.tv.playback.settings.PlaybackPreferenceRepository
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

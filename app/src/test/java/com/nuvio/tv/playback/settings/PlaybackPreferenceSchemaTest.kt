package com.nuvio.tv.playback.settings

import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.CustomBufferPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.VideoDimensions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreferenceSchemaTest {
    @Test
    fun `supported preferences round trip without loss`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val expected = defaults.copy(
            playback = defaults.playback.copy(
                engine = EnginePreference.LIBMPV,
                automaticFallback = false,
                decoder = DecoderPreference.SOFTWARE_ONLY,
                softwareDecodeFallback = true,
                buffering = BufferingPreference.CUSTOM,
                customBuffer = CustomBufferPreference(1_000, 8_000, 500, 750),
                audio = defaults.playback.audio.copy(
                    output = AudioOutputPreference.PCM,
                    downmixToStereo = true,
                    normalization = true,
                    skipSilence = true,
                    preferredLanguage = "de",
                    delayMs = 125,
                ),
                subtitles = defaults.playback.subtitles.copy(
                    enabled = false,
                    fidelity = SubtitleFidelity.FULL,
                    preferredLanguage = "es",
                    delayMs = -75,
                ),
                display = defaults.playback.display.copy(
                    frameRate = FrameRatePreference.ON_RATE_CHANGE,
                    resolutionMatching = true,
                ),
                video = defaults.playback.video.copy(
                    hdr = HdrPreference.HDR10,
                    maximumDimensions = VideoDimensions(1_920, 1_080),
                ),
                behavior = defaults.playback.behavior.copy(
                    autoplayNext = false,
                    stillWatchingEnabled = false,
                    showStatusIndicators = false,
                ),
            ),
            expert = ExpertPlaybackPreferences(MpvOutputPreference.RENDER),
        )

        assertEquals(expected, PlaybackPreferenceSchema.decode(PlaybackPreferenceSchema.newDocument(expected)).preferences)
    }

    @Test
    fun `unknown values safely fall back and remain preserved`() {
        val source = PlaybackPreferenceDocument(
            schemaVersion = CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION,
            revision = 4,
            values = mapOf("engine" to "FUTURE_ENGINE", "future_toggle" to "NEW_MODE"),
        )

        val decoded = PlaybackPreferenceSchema.decode(source)

        assertEquals(EnginePreference.AUTO, decoded.preferences.playback.engine)
        assertEquals("FUTURE_ENGINE", decoded.document.preservedUnknownValues["engine"])
        assertEquals("NEW_MODE", decoded.document.preservedUnknownValues["future_toggle"])
        assertTrue(PreferenceDecodeWarning.UNKNOWN_VALUE in decoded.warnings)
        assertTrue(PreferenceDecodeWarning.UNKNOWN_KEY in decoded.warnings)
        val patched = PlaybackPreferenceSchema.patchGroup(
            decoded.document,
            decoded.preferences.copy(
                playback = decoded.preferences.playback.copy(
                    audio = decoded.preferences.playback.audio.copy(skipSilence = true),
                ),
            ),
            PlaybackPreferenceGroup.AUDIO,
        )
        assertEquals("FUTURE_ENGINE", patched.preservedUnknownValues["engine"])
        assertEquals("NEW_MODE", patched.values["future_toggle"])
    }

    @Test
    fun `v0 migration is idempotent`() {
        val source = PlaybackPreferenceDocument(
            schemaVersion = 0,
            revision = 2,
            values = mapOf(
                "player_engine" to "MVP_PLAYER",
                "auto_switch_on_error" to "false",
                "low_latency" to "true",
            ),
        )

        val first = PlaybackPreferenceSchema.decode(source)
        val second = PlaybackPreferenceSchema.decode(first.document)

        assertEquals(EnginePreference.LIBMPV, first.preferences.playback.engine)
        assertEquals(BufferingPreference.LOW_LATENCY_LIVE, first.preferences.playback.buffering)
        assertFalse(first.preferences.playback.automaticFallback)
        assertTrue(PreferenceDecodeWarning.MIGRATED_V0 in first.warnings)
        assertFalse(PreferenceDecodeWarning.MIGRATED_V0 in second.warnings)
        assertEquals(first.preferences, second.preferences)
        assertEquals(first.document, second.document)
        assertEquals(3, first.document.revision)
    }

    @Test
    fun `v1 frame-rate names migrate to explicit V2 semantics`() {
        val onStart = PlaybackPreferenceSchema.decode(
            PlaybackPreferenceDocument(
                schemaVersion = 1,
                revision = 4,
                values = mapOf("frame_rate" to "ON_COMMITTED_PLAYBACK"),
            ),
        )
        val onChange = PlaybackPreferenceSchema.decode(
            PlaybackPreferenceDocument(
                schemaVersion = 1,
                revision = 8,
                values = mapOf("frame_rate" to "ALWAYS"),
            ),
        )

        assertEquals(FrameRatePreference.ON_START, onStart.preferences.playback.display.frameRate)
        assertEquals("ON_START", onStart.document.values["frame_rate"])
        assertEquals(FrameRatePreference.ON_RATE_CHANGE, onChange.preferences.playback.display.frameRate)
        assertEquals("ON_RATE_CHANGE", onChange.document.values["frame_rate"])
        assertTrue(PreferenceDecodeWarning.MIGRATED_V1_FRAME_RATE in onStart.warnings)
        assertTrue(PreferenceDecodeWarning.MIGRATED_V1_FRAME_RATE in onChange.warnings)
    }

    @Test
    fun `legacy start and start-stop both import as one committed start switch`() {
        listOf("START", "START_STOP").forEach { legacyMode ->
            val mapped = LegacyPlaybackPreferenceImporter.map(
                LegacyPlayerSettingsSnapshot(
                    importToken = "legacy-$legacyMode",
                    values = mapOf("frameRateMatchingMode" to legacyMode),
                ),
            )
            assertEquals(FrameRatePreference.ON_START, mapped.preferences.playback.display.frameRate)
        }
    }

    @Test
    fun `invalid custom buffer atomically falls back and preserves every raw value`() {
        val raw = mapOf(
            "buffering" to BufferingPreference.CUSTOM.name,
            "buffer_min_ms" to "50000",
            "buffer_max_ms" to "1000",
            "buffer_playback_ms" to "not-a-number",
            "buffer_rebuffer_ms" to "9000",
        )

        val decoded = PlaybackPreferenceSchema.decode(
            PlaybackPreferenceDocument(1, 7, raw),
        )

        assertEquals(BufferingPreference.RECOMMENDED, decoded.preferences.playback.buffering)
        assertNull(decoded.preferences.playback.customBuffer)
        assertTrue(PreferenceDecodeWarning.INVALID_CUSTOM_BUFFER in decoded.warnings)
        assertEquals("50000", decoded.document.preservedUnknownValues["buffer_min_ms"])
        assertEquals("1000", decoded.document.preservedUnknownValues["buffer_max_ms"])
        assertEquals("not-a-number", decoded.document.preservedUnknownValues["buffer_playback_ms"])
        assertEquals("9000", decoded.document.preservedUnknownValues["buffer_rebuffer_ms"])
    }

    @Test
    fun `reset changes only its group and preserves unrelated unknown data`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val customized = defaults.copy(
            playback = defaults.playback.copy(
                engine = EnginePreference.LIBMPV,
                audio = defaults.playback.audio.copy(skipSilence = true),
            ),
        )
        val source = PlaybackPreferenceSchema.newDocument(customized).copy(
            values = PlaybackPreferenceSchema.newDocument(customized).values + ("future_key" to "future_value"),
            preservedUnknownValues = mapOf("future_key" to "future_value"),
        )

        val reset = PlaybackPreferenceSchema.decode(
            PlaybackPreferenceSchema.resetGroup(source, PlaybackPreferenceGroup.ENGINE),
        )

        assertEquals(EnginePreference.AUTO, reset.preferences.playback.engine)
        assertTrue(reset.preferences.playback.audio.skipSilence)
        assertEquals("future_value", reset.document.preservedUnknownValues["future_key"])
    }

    @Test
    fun `future schema is readable but not writable`() {
        val future = PlaybackPreferenceDocument(
            schemaVersion = CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION + 1,
            revision = 1,
            values = mapOf("engine" to EnginePreference.MEDIA3.name),
        )

        assertTrue(PreferenceDecodeWarning.FUTURE_SCHEMA in PlaybackPreferenceSchema.decode(future).warnings)
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackPreferenceSchema.resetGroup(future, PlaybackPreferenceGroup.ENGINE)
        }
    }

    @Test
    fun `legacy import is detached and repository import is idempotent`() = runTest {
        val mutableLegacy = mutableMapOf(
            "internalPlayerEngine" to "MVP_PLAYER",
            "autoSwitchInternalPlayerOnError" to "false",
        )
        val before = mutableLegacy.toMap()
        val legacy = LegacyPlayerSettingsSnapshot("legacy-v1", mutableLegacy)
        val store = InMemoryStore()
        val repository = PlaybackPreferenceRepository(store)

        val first = repository.importLegacyIfAbsent("profile", legacy)
        mutableLegacy["internalPlayerEngine"] = "EXOPLAYER"
        val second = repository.importLegacyIfAbsent("profile", LegacyPlayerSettingsSnapshot("legacy-v2", mutableLegacy))

        assertEquals(before, legacy.values)
        assertTrue(first.imported)
        assertFalse(second.imported)
        assertEquals(1, store.writeCount)
        assertEquals(EnginePreference.LIBMPV, second.snapshot.preferences.playback.engine)
        assertEquals("legacy-v1", second.snapshot.legacyImportToken)
    }

    @Test
    fun `repository persists migration once on read`() = runTest {
        val store = InMemoryStore().apply {
            document = PlaybackPreferenceDocument(
                schemaVersion = 0,
                revision = 8,
                values = mapOf("player_engine" to "EXOPLAYER"),
            )
        }
        val repository = PlaybackPreferenceRepository(store)

        val first = repository.load("profile")
        val second = repository.load("profile")

        assertEquals(9, first.revision)
        assertEquals(first.preferences, second.preferences)
        assertEquals(first.revision, second.revision)
        assertTrue(PreferenceDecodeWarning.MIGRATED_V0 in first.warnings)
        assertFalse(PreferenceDecodeWarning.MIGRATED_V0 in second.warnings)
        assertEquals(1, store.writeCount)
        assertEquals(CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION, store.document?.schemaVersion)
    }

    @Test
    fun `document serializer round trips unicode unknowns and legacy token`() {
        val document = PlaybackPreferenceDocument(
            schemaVersion = 1,
            revision = 42,
            values = mapOf("audio_language" to "日本語", "key=with delimiter" to "line\nvalue"),
            preservedUnknownValues = mapOf("future" to "🚀"),
            legacyImportToken = "legacy/profile=one",
        )

        assertEquals(
            document,
            PlaybackPreferenceDocumentSerializer.deserialize(
                PlaybackPreferenceDocumentSerializer.serialize(document),
            ),
        )
    }

    @Test
    fun `invalid preference envelope is recoverable as an absent clean document`() {
        val document = PlaybackPreferenceSchema.newDocument()
        val encoded = PlaybackPreferenceDocumentSerializer.serialize(document)

        assertEquals(document, PlaybackPreferenceDocumentSerializer.deserializeOrNull(encoded))
        assertNull(PlaybackPreferenceDocumentSerializer.deserializeOrNull("$encoded" + "unexpected\n"))
        assertNull(PlaybackPreferenceDocumentSerializer.deserializeOrNull("unsupported"))
    }

    private class InMemoryStore : PlaybackPreferenceDocumentStore {
        private val documents = mutableMapOf<String, PlaybackPreferenceDocument>()
        var writeCount: Int = 0
        var document: PlaybackPreferenceDocument?
            get() = documents["profile"]
            set(value) {
                if (value == null) documents.remove("profile") else documents["profile"] = value
            }

        override suspend fun read(profileId: String): PlaybackPreferenceDocument? = documents[profileId]

        override suspend fun write(profileId: String, document: PlaybackPreferenceDocument) {
            writeCount += 1
            documents[profileId] = document
        }
    }
}

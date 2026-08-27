package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.playback.core.AudioRoute
import com.nuvio.tv.playback.core.AudioRouteCapabilities
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DisplayCapabilities
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.ResourceCapabilities
import com.nuvio.tv.playback.core.RuntimeCapabilities
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SurfaceCapabilities
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.settings.CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION
import com.nuvio.tv.playback.settings.CleanPlaybackPreferences
import com.nuvio.tv.playback.settings.CleanPlaybackSettingField
import com.nuvio.tv.playback.settings.PlaybackPreferenceDocument
import com.nuvio.tv.playback.settings.PlaybackPreferenceDocumentStore
import com.nuvio.tv.playback.settings.PlaybackPreferenceGroup
import com.nuvio.tv.playback.settings.PlaybackPreferenceRepository
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolutionContext
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CleanPlaybackSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `updates and resets only the active profile requested preferences`() = runTest(mainDispatcherRule.dispatcher) {
        val store = InMemoryStore()
        val profiles = MutableStateFlow(1)
        val repository = PlaybackPreferenceRepository(store)
        val viewModel = CleanPlaybackSettingsViewModel(repository, profiles, resolverSource())
        advanceUntilIdle()

        viewModel.update(CleanPlaybackSettingField.ENGINE, "LIBMPV")
        advanceUntilIdle()
        assertEquals(EnginePreference.LIBMPV, repository.load("1").preferences.playback.engine)

        profiles.value = 2
        advanceUntilIdle()
        val profileTwo = (viewModel.uiState.value as CleanPlaybackSettingsUiState.Content).presentation
        assertEquals("2", profileTwo.profileId)
        assertEquals(EnginePreference.AUTO, repository.load("2").preferences.playback.engine)

        viewModel.update(CleanPlaybackSettingField.ENGINE, "MEDIA3")
        advanceUntilIdle()
        viewModel.reset(PlaybackPreferenceGroup.ENGINE)
        advanceUntilIdle()
        assertEquals(EnginePreference.AUTO, repository.load("2").preferences.playback.engine)
        assertEquals(EnginePreference.LIBMPV, repository.load("1").preferences.playback.engine)
    }

    @Test
    fun `future schema remains visible read only and rejects writes`() = runTest(mainDispatcherRule.dispatcher) {
        val store = InMemoryStore()
        store.write(
            "1",
            PlaybackPreferenceDocument(
                schemaVersion = CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION + 1,
                revision = 12,
                values = mapOf("engine" to "LIBMPV", "future_key" to "future_value"),
            ),
        )
        val repository = PlaybackPreferenceRepository(store)
        val viewModel = CleanPlaybackSettingsViewModel(repository, MutableStateFlow(1), resolverSource())
        advanceUntilIdle()

        val initial = viewModel.uiState.value as CleanPlaybackSettingsUiState.Content
        assertTrue(initial.presentation.readOnly)
        assertEquals(12, initial.presentation.revision)

        viewModel.update(CleanPlaybackSettingField.ENGINE, "MEDIA3")
        advanceUntilIdle()
        val after = viewModel.uiState.value as CleanPlaybackSettingsUiState.Content
        assertTrue(after.notice.orEmpty().contains("read-only"))
        assertEquals(12, store.documents.getValue("1").revision)
        assertEquals("LIBMPV", store.documents.getValue("1").values["engine"])
    }

    @Test
    fun `invalid edit keeps current content and reports the validation error`() = runTest(mainDispatcherRule.dispatcher) {
        val store = InMemoryStore()
        val viewModel = CleanPlaybackSettingsViewModel(
            PlaybackPreferenceRepository(store),
            MutableStateFlow(1),
            resolverSource(),
        )
        advanceUntilIdle()

        viewModel.update(CleanPlaybackSettingField.MAXIMUM_DIMENSIONS, "not-dimensions")
        advanceUntilIdle()

        val state = viewModel.uiState.value as CleanPlaybackSettingsUiState.Content
        assertFalse(state.operationInProgress)
        assertTrue(state.notice.orEmpty().contains("WIDTHxHEIGHT"))
    }

    private fun resolverSource() = CleanPlaybackSettingsResolutionSource { _, requested ->
        PlaybackPreferenceResolver.resolve(
            requested,
            PlaybackPreferenceResolutionContext(
                request = PlaybackRequest(
                    "https://example.invalid/video.mp4",
                    contentType = ContentType.VOD,
                ).summary(),
                evidence = StreamEvidence(),
                capabilities = RuntimeCapabilities(
                    snapshotVersion = 1,
                    capturedAtEpochMs = 1,
                    apiLevel = 35,
                    display = DisplayCapabilities(VideoDimensions(1920, 1080)),
                    audioRoute = AudioRouteCapabilities(AudioRoute.TV_SPEAKERS),
                    resources = ResourceCapabilities(2_000_000_000, lowMemory = false),
                    surfaces = SurfaceCapabilities(),
                ),
            ),
        )
    }

    private class InMemoryStore : PlaybackPreferenceDocumentStore {
        val documents = mutableMapOf<String, PlaybackPreferenceDocument>()

        override suspend fun read(profileId: String): PlaybackPreferenceDocument? = documents[profileId]

        override suspend fun write(profileId: String, document: PlaybackPreferenceDocument) {
            documents[profileId] = document
        }
    }
}

package com.nuvio.tv.playback.settings

import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.AudioRoute
import com.nuvio.tv.playback.core.AudioRouteCapabilities
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.DisplayCapabilities
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.ResourceCapabilities
import com.nuvio.tv.playback.core.RuntimeCapabilities
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SurfaceCapabilities
import com.nuvio.tv.playback.core.VideoDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanPlaybackSettingsPresentationTest {
    @Test
    fun `presenter exposes every group and field with requested and effective explanation`() {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(
                audio = defaults.playback.audio.copy(
                    output = AudioOutputPreference.PASSTHROUGH,
                    normalization = true,
                ),
            ),
        )
        val snapshot = PlaybackPreferenceSnapshot(requested, 7, emptySet(), emptyMap(), null)
        val presentation = CleanPlaybackSettingsPresenter.present("2", snapshot, resolve(requested))

        assertEquals(PlaybackPreferenceGroup.entries.toList(), presentation.groups.map { it.group })
        assertEquals(
            CleanPlaybackSettingField.entries.toSet(),
            presentation.groups.flatMap { it.fields }.map { it.key }.toSet(),
        )
        assertEquals(CleanPlaybackSettingField.entries.size, presentation.groups.sumOf { it.fields.size })
        assertEquals(28, CleanPlaybackSettingField.entries.flatMap { it.schemaKeys }.toSet().size)
        val normalization = presentation.groups.flatMap { it.fields }
            .single { it.key == CleanPlaybackSettingField.AUDIO_NORMALIZATION }
        assertEquals("true", normalization.requestedValue)
        assertEquals("false", normalization.effectiveValue)
        assertTrue(normalization.differsFromRequested)
        assertTrue(normalization.conflicts.isNotEmpty())
        assertFalse(presentation.readOnly)
    }

    @Test
    fun `future schema warnings are visible and make presentation read only`() {
        val requested = CleanPlaybackPreferences.recommended()
        val snapshot = PlaybackPreferenceSnapshot(
            preferences = requested,
            revision = 9,
            warnings = setOf(PreferenceDecodeWarning.FUTURE_SCHEMA, PreferenceDecodeWarning.UNKNOWN_KEY),
            preservedUnknownValues = mapOf("future_key" to "future_value"),
            legacyImportToken = null,
        )

        val presentation = CleanPlaybackSettingsPresenter.present("1", snapshot, resolve(requested))

        assertTrue(presentation.readOnly)
        assertEquals(snapshot.warnings, presentation.warnings)
        assertEquals(1, presentation.preservedUnknownValueCount)
    }

    @Test
    fun `editor updates every field without replacing unrelated requested groups`() {
        var requested = CleanPlaybackPreferences.recommended()
        val edits = listOf(
            CleanPlaybackSettingField.ENGINE to "LIBMPV",
            CleanPlaybackSettingField.AUTOMATIC_FALLBACK to "false",
            CleanPlaybackSettingField.DECODER to "HARDWARE_ONLY",
            CleanPlaybackSettingField.SOFTWARE_DECODE_FALLBACK to "true",
            CleanPlaybackSettingField.HDR to "SDR",
            CleanPlaybackSettingField.MAXIMUM_DIMENSIONS to "1920x1080",
            CleanPlaybackSettingField.AUDIO_OUTPUT to "PCM",
            CleanPlaybackSettingField.AUDIO_DOWNMIX to "true",
            CleanPlaybackSettingField.AUDIO_NORMALIZATION to "true",
            CleanPlaybackSettingField.AUDIO_SKIP_SILENCE to "true",
            CleanPlaybackSettingField.AUDIO_LANGUAGE to "en",
            CleanPlaybackSettingField.AUDIO_DELAY_MS to "-125",
            CleanPlaybackSettingField.SUBTITLES_ENABLED to "false",
            CleanPlaybackSettingField.SUBTITLE_FIDELITY to "FULL",
            CleanPlaybackSettingField.SUBTITLE_LANGUAGE to "es",
            CleanPlaybackSettingField.SUBTITLE_DELAY_MS to "250",
            CleanPlaybackSettingField.FRAME_RATE to "ALWAYS",
            CleanPlaybackSettingField.RESOLUTION_MATCHING to "true",
            CleanPlaybackSettingField.BUFFERING to "CUSTOM",
            CleanPlaybackSettingField.CUSTOM_BUFFER to "1000,5000,500,750",
            CleanPlaybackSettingField.AUTOPLAY_NEXT to "false",
            CleanPlaybackSettingField.STILL_WATCHING to "false",
            CleanPlaybackSettingField.SHOW_STATUS to "false",
            CleanPlaybackSettingField.MPV_OUTPUT to "DIRECT",
        )
        edits.forEach { (field, value) ->
            val edit = CleanPlaybackSettingsEditor.apply(requested, field, value)
            assertEquals(field.group, edit.group)
            requested = edit.preferences
        }

        assertEquals(EnginePreference.LIBMPV, requested.playback.engine)
        assertFalse(requested.playback.automaticFallback)
        assertEquals(DecoderPreference.HARDWARE_ONLY, requested.playback.decoder)
        assertEquals(HdrPreference.SDR, requested.playback.video.hdr)
        assertEquals(VideoDimensions(1920, 1080), requested.playback.video.maximumDimensions)
        assertEquals(BufferingPreference.CUSTOM, requested.playback.buffering)
        assertEquals(5_000, requested.playback.customBuffer?.maximumBufferMs)
        assertEquals(FrameRatePreference.ALWAYS, requested.playback.display.frameRate)
        assertEquals(SubtitleFidelity.FULL, requested.playback.subtitles.fidelity)
        assertEquals(MpvOutputPreference.DIRECT, requested.expert.mpvOutput)
        assertEquals("en", requested.playback.audio.preferredLanguage)
        assertEquals("es", requested.playback.subtitles.preferredLanguage)
    }

    private fun resolve(requested: CleanPlaybackPreferences): ResolvedPlaybackPreferences =
        PlaybackPreferenceResolver.resolve(
            requested,
            PlaybackPreferenceResolutionContext(
                request = PlaybackRequest("https://example.invalid/video.mp4", contentType = ContentType.VOD).summary(),
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

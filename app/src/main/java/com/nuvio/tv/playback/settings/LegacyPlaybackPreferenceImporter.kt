package com.nuvio.tv.playback.settings

import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.CustomBufferPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.SubtitleFidelity

/** Detached value snapshot supplied by cutover wiring; no legacy storage object crosses this API. */
class LegacyPlayerSettingsSnapshot(
    val importToken: String,
    values: Map<String, String>,
) {
    val values: Map<String, String> = values.toMap()

    init {
        require(importToken.isNotBlank())
    }
}

enum class LegacyImportNote {
    EXTERNAL_PLAYER_PREFERENCE_NOT_IMPORTED,
    UNKNOWN_ENGINE_FELL_BACK_TO_AUTO,
    INVALID_CUSTOM_BUFFER_FELL_BACK_TO_RECOMMENDED,
    EXPERIMENTAL_DOLBY_TRANSFORM_NOT_IMPORTED,
    ENGINE_SPECIFIC_OPTION_QUARANTINED,
}

data class MappedLegacyPlaybackPreferences(
    val preferences: CleanPlaybackPreferences,
    val notes: Set<LegacyImportNote>,
)

object LegacyPlaybackPreferenceImporter {
    fun map(snapshot: LegacyPlayerSettingsSnapshot): MappedLegacyPlaybackPreferences {
        val values = snapshot.values
        val notes = linkedSetOf<LegacyImportNote>()
        val defaults = CleanPlaybackPreferences.recommended()

        val playerPreference = values["playerPreference"]
        if (playerPreference != null && playerPreference != "INTERNAL") {
            notes += LegacyImportNote.EXTERNAL_PLAYER_PREFERENCE_NOT_IMPORTED
        }
        val engine = when (values["internalPlayerEngine"]) {
            null, "AUTO" -> EnginePreference.AUTO
            "EXOPLAYER" -> EnginePreference.MEDIA3
            "MVP_PLAYER" -> EnginePreference.LIBMPV
            else -> {
                notes += LegacyImportNote.UNKNOWN_ENGINE_FELL_BACK_TO_AUTO
                EnginePreference.AUTO
            }
        }
        val mpvOutput = when (values["mpvHardwareDecodeMode"]) {
            "LEGACY_DIRECT_COPY", "HARDWARE_DIRECT" -> MpvOutputPreference.DIRECT
            "HARDWARE_COPY", "AUTO_SAFE", null -> MpvOutputPreference.AUTO
            "DISABLED" -> MpvOutputPreference.RENDER
            else -> {
                notes += LegacyImportNote.ENGINE_SPECIFIC_OPTION_QUARANTINED
                MpvOutputPreference.AUTO
            }
        }
        val decoder = when (values["mpvHardwareDecodeMode"]) {
            "DISABLED" -> DecoderPreference.SOFTWARE_ONLY
            else -> DecoderPreference.AUTO
        }
        val forcedPassthrough = values.boolean("forceOpticalPassthrough", false)
        val buffering = mapBuffer(values, notes)
        val frameRate = when (values["frameRateMatchingMode"]) {
            "START", "START_STOP" -> FrameRatePreference.ON_START
            else -> FrameRatePreference.OFF
        }
        val hasLegacyDolbyTransform = values.boolean("dv5ToDv81Enabled", false) ||
            values.boolean("dv7ToDv81PreserveMappingEnabled", false) ||
            values["dv7HandlingMode"]?.let { it != "AUTO" } == true ||
            values["dv7LibdoviModeOverride"]?.let { it != "-1" } == true ||
            values.boolean("stripHdr10PlusSei", false)
        if (hasLegacyDolbyTransform) {
            notes += LegacyImportNote.EXPERIMENTAL_DOLBY_TRANSFORM_NOT_IMPORTED
        }

        val playback = defaults.playback.copy(
            engine = engine,
            automaticFallback = values.boolean("autoSwitchInternalPlayerOnError", true),
            decoder = decoder,
            buffering = buffering.first,
            customBuffer = buffering.second,
            audio = defaults.playback.audio.copy(
                output = if (forcedPassthrough) AudioOutputPreference.PASSTHROUGH else AudioOutputPreference.AUTO,
                downmixToStereo = values.boolean("downmixEnabled", false),
                normalization = defaults.playback.audio.normalization,
                skipSilence = values.boolean("skipSilence", false),
                preferredLanguage = values["preferredAudioLanguage"]?.takeUnless {
                    it == "default" || it == "device" || it == "original"
                },
            ),
            subtitles = defaults.playback.subtitles.copy(
                fidelity = if (values.boolean("useLibass", false)) {
                    SubtitleFidelity.FULL
                } else {
                    SubtitleFidelity.COMPATIBLE
                },
                preferredLanguage = values["subtitleStyle.preferredLanguage"]
                    ?: values["subtitlePreferredLanguage"],
            ),
            display = defaults.playback.display.copy(
                frameRate = frameRate,
                resolutionMatching = values.boolean("resolutionMatchingEnabled", false),
            ),
            video = defaults.playback.video.copy(hdr = HdrPreference.AUTO),
            behavior = defaults.playback.behavior.copy(
                autoplayNext = values.boolean("streamAutoPlayNextEpisodeEnabled", false),
                stillWatchingEnabled = values.boolean("stillWatchingEnabled", false),
                showStatusIndicators = values.boolean("showPlayerLoadingStatus", true),
            ),
        )
        return MappedLegacyPlaybackPreferences(
            preferences = defaults.copy(
                playback = playback,
                expert = ExpertPlaybackPreferences(mpvOutput),
            ),
            notes = notes,
        )
    }

    private fun mapBuffer(
        values: Map<String, String>,
        notes: MutableSet<LegacyImportNote>,
    ): Pair<BufferingPreference, CustomBufferPreference?> {
        val hasCustom = listOf(
            "bufferSettings.minBufferMs",
            "bufferSettings.maxBufferMs",
            "bufferSettings.bufferForPlaybackMs",
            "bufferSettings.bufferForPlaybackAfterRebufferMs",
        ).any(values::containsKey)
        if (!hasCustom) return BufferingPreference.RECOMMENDED to null
        val min = values["bufferSettings.minBufferMs"]?.toIntOrNull() ?: 15_000
        val max = values["bufferSettings.maxBufferMs"]?.toIntOrNull() ?: 45_000
        val playback = values["bufferSettings.bufferForPlaybackMs"]?.toIntOrNull() ?: 5_000
        val rebuffer = values["bufferSettings.bufferForPlaybackAfterRebufferMs"]?.toIntOrNull() ?: 3_000
        val custom = runCatching { CustomBufferPreference(min, max, playback, rebuffer) }.getOrNull()
        if (custom == null) {
            notes += LegacyImportNote.INVALID_CUSTOM_BUFFER_FELL_BACK_TO_RECOMMENDED
            return BufferingPreference.RECOMMENDED to null
        }
        return BufferingPreference.CUSTOM to custom
    }

    private fun Map<String, String>.boolean(key: String, fallback: Boolean): Boolean = when (get(key)) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}

package com.nuvio.tv.playback.settings

import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.ChangeImpact
import com.nuvio.tv.playback.core.CustomBufferPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.PreferenceAvailability
import com.nuvio.tv.playback.core.PreferenceConflict
import com.nuvio.tv.playback.core.PreferenceReason
import com.nuvio.tv.playback.core.PreferenceResolution
import com.nuvio.tv.playback.core.ResolutionAuthority
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.VideoDimensions

enum class CleanPlaybackSettingField(
    val group: PlaybackPreferenceGroup,
    val title: String,
    vararg schemaKeys: String,
) {
    ENGINE(PlaybackPreferenceGroup.ENGINE, "Playback engine", "engine"),
    AUTOMATIC_FALLBACK(PlaybackPreferenceGroup.ENGINE, "Automatic engine fallback", "automatic_fallback"),
    DECODER(PlaybackPreferenceGroup.VIDEO, "Video decoder", "decoder"),
    SOFTWARE_DECODE_FALLBACK(PlaybackPreferenceGroup.VIDEO, "Software decode fallback", "software_decode_fallback"),
    HDR(PlaybackPreferenceGroup.VIDEO, "HDR mode", "hdr"),
    MAXIMUM_DIMENSIONS(PlaybackPreferenceGroup.VIDEO, "Maximum video dimensions", "maximum_width", "maximum_height"),
    AUDIO_OUTPUT(PlaybackPreferenceGroup.AUDIO, "Audio output", "audio_output"),
    AUDIO_DOWNMIX(PlaybackPreferenceGroup.AUDIO, "Downmix to stereo", "audio_downmix"),
    AUDIO_NORMALIZATION(PlaybackPreferenceGroup.AUDIO, "Volume normalization", "audio_normalization"),
    AUDIO_SKIP_SILENCE(PlaybackPreferenceGroup.AUDIO, "Skip silence", "audio_skip_silence"),
    AUDIO_LANGUAGE(PlaybackPreferenceGroup.AUDIO, "Preferred audio language", "audio_language"),
    AUDIO_DELAY_MS(PlaybackPreferenceGroup.AUDIO, "Audio delay (ms)", "audio_delay_ms"),
    SUBTITLES_ENABLED(PlaybackPreferenceGroup.SUBTITLES, "Subtitles enabled", "subtitles_enabled"),
    SUBTITLE_FIDELITY(PlaybackPreferenceGroup.SUBTITLES, "Subtitle fidelity", "subtitle_fidelity"),
    SUBTITLE_LANGUAGE(PlaybackPreferenceGroup.SUBTITLES, "Preferred subtitle language", "subtitle_language"),
    SUBTITLE_DELAY_MS(PlaybackPreferenceGroup.SUBTITLES, "Subtitle delay (ms)", "subtitle_delay_ms"),
    FRAME_RATE(PlaybackPreferenceGroup.DISPLAY, "Frame-rate matching", "frame_rate"),
    RESOLUTION_MATCHING(PlaybackPreferenceGroup.DISPLAY, "Resolution matching", "resolution_matching"),
    BUFFERING(PlaybackPreferenceGroup.BUFFERING, "Buffering profile", "buffering"),
    CUSTOM_BUFFER(
        PlaybackPreferenceGroup.BUFFERING,
        "Custom buffer (min,max,start,rebuffer ms)",
        "buffer_min_ms",
        "buffer_max_ms",
        "buffer_playback_ms",
        "buffer_rebuffer_ms",
    ),
    AUTOPLAY_NEXT(PlaybackPreferenceGroup.BEHAVIOR_UI, "Autoplay next", "autoplay_next"),
    STILL_WATCHING(PlaybackPreferenceGroup.BEHAVIOR_UI, "Still-watching prompt", "still_watching"),
    SHOW_STATUS(PlaybackPreferenceGroup.BEHAVIOR_UI, "Playback status indicators", "show_status"),
    MPV_OUTPUT(PlaybackPreferenceGroup.EXPERT, "libmpv output path", "expert_mpv_output");

    val schemaKeys: Set<String> = schemaKeys.toSet()
}

enum class CleanPlaybackSettingInputKind { BOOLEAN, CHOICE, TEXT, INTEGER, DIMENSIONS, CUSTOM_BUFFER }

data class CleanPlaybackSettingFieldUi(
    val key: CleanPlaybackSettingField,
    val title: String,
    /** Stable schema-facing value; display formatting never replaces the requested value. */
    val requestedValue: String,
    val effectiveValue: String?,
    val inputKind: CleanPlaybackSettingInputKind,
    val choices: List<String> = emptyList(),
    val editable: Boolean = true,
    val authority: ResolutionAuthority,
    val availability: PreferenceAvailability,
    val reason: PreferenceReason,
    val contributingReasons: Set<PreferenceReason>,
    val conflicts: Set<PreferenceConflict>,
    val impact: ChangeImpact,
) {
    val differsFromRequested: Boolean get() = effectiveValue != null && effectiveValue != requestedValue
}

data class CleanPlaybackSettingGroupUi(
    val group: PlaybackPreferenceGroup,
    val title: String,
    val fields: List<CleanPlaybackSettingFieldUi>,
)

data class CleanPlaybackSettingsPresentation(
    val profileId: String,
    val revision: Long,
    val readOnly: Boolean,
    val warnings: Set<PreferenceDecodeWarning>,
    val preservedUnknownValueCount: Int,
    val groups: List<CleanPlaybackSettingGroupUi>,
)

/** Pure requested/effective mapper. It has no Android, repository, player, or engine dependency. */
object CleanPlaybackSettingsPresenter {
    fun present(
        profileId: String,
        snapshot: PlaybackPreferenceSnapshot,
        resolved: ResolvedPlaybackPreferences,
    ): CleanPlaybackSettingsPresentation {
        require(profileId.isNotBlank()) { "Playback preference profile id must not be blank" }
        require(resolved.requested == snapshot.preferences) {
            "Resolved settings must describe the loaded requested preference snapshot"
        }
        val requested = snapshot.preferences.playback
        val customEditable = requested.buffering == BufferingPreference.CUSTOM
        val fields = listOf(
            field(CleanPlaybackSettingField.ENGINE, requested.engine, resolved.engine, EnginePreference.entries),
            field(CleanPlaybackSettingField.AUTOMATIC_FALLBACK, requested.automaticFallback, resolved.automaticFallback),
            field(CleanPlaybackSettingField.DECODER, requested.decoder, resolved.decoder, DecoderPreference.entries),
            field(
                CleanPlaybackSettingField.SOFTWARE_DECODE_FALLBACK,
                requested.softwareDecodeFallback,
                resolved.softwareDecodeFallback,
            ),
            field(CleanPlaybackSettingField.HDR, requested.video.hdr, resolved.hdr, HdrPreference.entries),
            field(CleanPlaybackSettingField.MAXIMUM_DIMENSIONS, requested.video.maximumDimensions, resolved.maximumDimensions),
            field(CleanPlaybackSettingField.AUDIO_OUTPUT, requested.audio.output, resolved.audioOutput, AudioOutputPreference.entries),
            field(CleanPlaybackSettingField.AUDIO_DOWNMIX, requested.audio.downmixToStereo, resolved.downmix),
            field(CleanPlaybackSettingField.AUDIO_NORMALIZATION, requested.audio.normalization, resolved.normalization),
            field(CleanPlaybackSettingField.AUDIO_SKIP_SILENCE, requested.audio.skipSilence, resolved.skipSilence),
            field(CleanPlaybackSettingField.AUDIO_LANGUAGE, requested.audio.preferredLanguage, resolved.audioLanguage),
            field(CleanPlaybackSettingField.AUDIO_DELAY_MS, requested.audio.delayMs, resolved.audioDelayMs),
            field(CleanPlaybackSettingField.SUBTITLES_ENABLED, requested.subtitles.enabled, resolved.subtitlesEnabled),
            field(
                CleanPlaybackSettingField.SUBTITLE_FIDELITY,
                requested.subtitles.fidelity,
                resolved.subtitleFidelity,
                SubtitleFidelity.entries,
            ),
            field(CleanPlaybackSettingField.SUBTITLE_LANGUAGE, requested.subtitles.preferredLanguage, resolved.subtitleLanguage),
            field(CleanPlaybackSettingField.SUBTITLE_DELAY_MS, requested.subtitles.delayMs, resolved.subtitleDelayMs),
            field(CleanPlaybackSettingField.FRAME_RATE, requested.display.frameRate, resolved.frameRate, FrameRatePreference.entries),
            field(CleanPlaybackSettingField.RESOLUTION_MATCHING, requested.display.resolutionMatching, resolved.resolutionMatching),
            field(CleanPlaybackSettingField.BUFFERING, requested.buffering, resolved.buffering, BufferingPreference.entries),
            field(CleanPlaybackSettingField.CUSTOM_BUFFER, requested.customBuffer, resolved.customBuffer, editable = customEditable),
            field(CleanPlaybackSettingField.AUTOPLAY_NEXT, requested.behavior.autoplayNext, resolved.autoplayNext),
            field(
                CleanPlaybackSettingField.STILL_WATCHING,
                requested.behavior.stillWatchingEnabled,
                resolved.stillWatchingEnabled,
            ),
            field(CleanPlaybackSettingField.SHOW_STATUS, requested.behavior.showStatusIndicators, resolved.showStatusIndicators),
            field(
                CleanPlaybackSettingField.MPV_OUTPUT,
                snapshot.preferences.expert.mpvOutput,
                resolved.mpvOutput,
                MpvOutputPreference.entries,
            ),
        )
        check(fields.map(CleanPlaybackSettingFieldUi::key).toSet() == CleanPlaybackSettingField.entries.toSet()) {
            "The clean settings presenter must expose every preference field exactly once"
        }
        check(CleanPlaybackSettingField.entries.flatMap { it.schemaKeys }.distinct().size == SCHEMA_FIELD_COUNT) {
            "The clean settings presenter must expose all $SCHEMA_FIELD_COUNT persisted schema fields"
        }
        return CleanPlaybackSettingsPresentation(
            profileId = profileId,
            revision = snapshot.revision,
            readOnly = PreferenceDecodeWarning.FUTURE_SCHEMA in snapshot.warnings,
            warnings = snapshot.warnings,
            preservedUnknownValueCount = snapshot.preservedUnknownValues.size,
            groups = PlaybackPreferenceGroup.entries.map { group ->
                CleanPlaybackSettingGroupUi(
                    group = group,
                    title = group.displayTitle(),
                    fields = fields.filter { it.key.group == group },
                )
            },
        )
    }

    private fun <T> field(
        key: CleanPlaybackSettingField,
        requested: T,
        resolution: PreferenceResolution<T>,
        choices: List<T> = emptyList(),
        editable: Boolean = true,
    ) = CleanPlaybackSettingFieldUi(
        key = key,
        title = key.title,
        requestedValue = requested.encodedValue(),
        effectiveValue = resolution.effective?.encodedValue(),
        inputKind = key.inputKind(),
        choices = when (requested) {
            is Boolean -> listOf("true", "false")
            else -> choices.map { it.encodedValue() }
        },
        editable = editable,
        authority = resolution.authority,
        availability = resolution.availability,
        reason = resolution.primaryReason,
        contributingReasons = resolution.contributingReasons,
        conflicts = resolution.conflicts,
        impact = resolution.impact,
    )

    private fun Any?.encodedValue(): String = when (this) {
        null -> ""
        is Enum<*> -> name
        is VideoDimensions -> "${width}x$height"
        is CustomBufferPreference -> listOf(
            minimumBufferMs,
            maximumBufferMs,
            playbackStartBufferMs,
            rebufferStartBufferMs,
        ).joinToString(",")
        else -> toString()
    }

    private fun PlaybackPreferenceGroup.displayTitle(): String = name
        .lowercase()
        .replace('_', ' ')
        .replaceFirstChar(Char::titlecase)

    private fun CleanPlaybackSettingField.inputKind(): CleanPlaybackSettingInputKind = when (this) {
        CleanPlaybackSettingField.AUTOMATIC_FALLBACK,
        CleanPlaybackSettingField.SOFTWARE_DECODE_FALLBACK,
        CleanPlaybackSettingField.AUDIO_DOWNMIX,
        CleanPlaybackSettingField.AUDIO_NORMALIZATION,
        CleanPlaybackSettingField.AUDIO_SKIP_SILENCE,
        CleanPlaybackSettingField.SUBTITLES_ENABLED,
        CleanPlaybackSettingField.RESOLUTION_MATCHING,
        CleanPlaybackSettingField.AUTOPLAY_NEXT,
        CleanPlaybackSettingField.STILL_WATCHING,
        CleanPlaybackSettingField.SHOW_STATUS,
        -> CleanPlaybackSettingInputKind.BOOLEAN
        CleanPlaybackSettingField.ENGINE,
        CleanPlaybackSettingField.DECODER,
        CleanPlaybackSettingField.HDR,
        CleanPlaybackSettingField.AUDIO_OUTPUT,
        CleanPlaybackSettingField.SUBTITLE_FIDELITY,
        CleanPlaybackSettingField.FRAME_RATE,
        CleanPlaybackSettingField.BUFFERING,
        CleanPlaybackSettingField.MPV_OUTPUT,
        -> CleanPlaybackSettingInputKind.CHOICE
        CleanPlaybackSettingField.AUDIO_LANGUAGE,
        CleanPlaybackSettingField.SUBTITLE_LANGUAGE,
        -> CleanPlaybackSettingInputKind.TEXT
        CleanPlaybackSettingField.AUDIO_DELAY_MS,
        CleanPlaybackSettingField.SUBTITLE_DELAY_MS,
        -> CleanPlaybackSettingInputKind.INTEGER
        CleanPlaybackSettingField.MAXIMUM_DIMENSIONS -> CleanPlaybackSettingInputKind.DIMENSIONS
        CleanPlaybackSettingField.CUSTOM_BUFFER -> CleanPlaybackSettingInputKind.CUSTOM_BUFFER
    }

    private const val SCHEMA_FIELD_COUNT = 28
}

data class CleanPlaybackPreferenceEdit(
    val group: PlaybackPreferenceGroup,
    val preferences: CleanPlaybackPreferences,
)

/** Pure schema editor. The repository remains the sole persistence authority. */
object CleanPlaybackSettingsEditor {
    fun apply(
        current: CleanPlaybackPreferences,
        field: CleanPlaybackSettingField,
        encodedValue: String,
    ): CleanPlaybackPreferenceEdit {
        val playback = current.playback
        val updated = when (field) {
            CleanPlaybackSettingField.ENGINE -> current.copy(playback = playback.copy(engine = enumValue(encodedValue)))
            CleanPlaybackSettingField.AUTOMATIC_FALLBACK -> current.copy(
                playback = playback.copy(automaticFallback = booleanValue(encodedValue)),
            )
            CleanPlaybackSettingField.DECODER -> current.copy(playback = playback.copy(decoder = enumValue(encodedValue)))
            CleanPlaybackSettingField.SOFTWARE_DECODE_FALLBACK -> current.copy(
                playback = playback.copy(softwareDecodeFallback = booleanValue(encodedValue)),
            )
            CleanPlaybackSettingField.HDR -> current.copy(
                playback = playback.copy(video = playback.video.copy(hdr = enumValue(encodedValue))),
            )
            CleanPlaybackSettingField.MAXIMUM_DIMENSIONS -> current.copy(
                playback = playback.copy(video = playback.video.copy(maximumDimensions = dimensionsValue(encodedValue))),
            )
            CleanPlaybackSettingField.AUDIO_OUTPUT -> current.copy(
                playback = playback.copy(audio = playback.audio.copy(output = enumValue(encodedValue))),
            )
            CleanPlaybackSettingField.AUDIO_DOWNMIX -> current.copy(
                playback = playback.copy(audio = playback.audio.copy(downmixToStereo = booleanValue(encodedValue))),
            )
            CleanPlaybackSettingField.AUDIO_NORMALIZATION -> current.copy(
                playback = playback.copy(audio = playback.audio.copy(normalization = booleanValue(encodedValue))),
            )
            CleanPlaybackSettingField.AUDIO_SKIP_SILENCE -> current.copy(
                playback = playback.copy(audio = playback.audio.copy(skipSilence = booleanValue(encodedValue))),
            )
            CleanPlaybackSettingField.AUDIO_LANGUAGE -> current.copy(
                playback = playback.copy(audio = playback.audio.copy(preferredLanguage = encodedValue.trim().ifEmpty { null })),
            )
            CleanPlaybackSettingField.AUDIO_DELAY_MS -> current.copy(
                playback = playback.copy(audio = playback.audio.copy(delayMs = longValue(encodedValue))),
            )
            CleanPlaybackSettingField.SUBTITLES_ENABLED -> current.copy(
                playback = playback.copy(subtitles = playback.subtitles.copy(enabled = booleanValue(encodedValue))),
            )
            CleanPlaybackSettingField.SUBTITLE_FIDELITY -> current.copy(
                playback = playback.copy(subtitles = playback.subtitles.copy(fidelity = enumValue(encodedValue))),
            )
            CleanPlaybackSettingField.SUBTITLE_LANGUAGE -> current.copy(
                playback = playback.copy(
                    subtitles = playback.subtitles.copy(preferredLanguage = encodedValue.trim().ifEmpty { null }),
                ),
            )
            CleanPlaybackSettingField.SUBTITLE_DELAY_MS -> current.copy(
                playback = playback.copy(subtitles = playback.subtitles.copy(delayMs = longValue(encodedValue))),
            )
            CleanPlaybackSettingField.FRAME_RATE -> current.copy(
                playback = playback.copy(display = playback.display.copy(frameRate = enumValue(encodedValue))),
            )
            CleanPlaybackSettingField.RESOLUTION_MATCHING -> current.copy(
                playback = playback.copy(display = playback.display.copy(resolutionMatching = booleanValue(encodedValue))),
            )
            CleanPlaybackSettingField.BUFFERING -> current.copy(playback = playback.copy(buffering = enumValue(encodedValue)))
            CleanPlaybackSettingField.CUSTOM_BUFFER -> {
                require(playback.buffering == BufferingPreference.CUSTOM) {
                    "Select the custom buffering profile before editing custom values"
                }
                current.copy(playback = playback.copy(customBuffer = customBufferValue(encodedValue)))
            }
            CleanPlaybackSettingField.AUTOPLAY_NEXT -> current.copy(
                playback = playback.copy(behavior = playback.behavior.copy(autoplayNext = booleanValue(encodedValue))),
            )
            CleanPlaybackSettingField.STILL_WATCHING -> current.copy(
                playback = playback.copy(
                    behavior = playback.behavior.copy(stillWatchingEnabled = booleanValue(encodedValue)),
                ),
            )
            CleanPlaybackSettingField.SHOW_STATUS -> current.copy(
                playback = playback.copy(
                    behavior = playback.behavior.copy(showStatusIndicators = booleanValue(encodedValue)),
                ),
            )
            CleanPlaybackSettingField.MPV_OUTPUT -> current.copy(expert = current.expert.copy(mpvOutput = enumValue(encodedValue)))
        }
        return CleanPlaybackPreferenceEdit(field.group, updated)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unsupported choice value")

    private fun booleanValue(value: String): Boolean = value.toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("Boolean value must be true or false")

    private fun longValue(value: String): Long = value.trim().toLongOrNull()
        ?: throw IllegalArgumentException("Value must be a whole number")

    private fun dimensionsValue(value: String): VideoDimensions? {
        if (value.isBlank()) return null
        val parts = value.lowercase().split('x')
        require(parts.size == 2) { "Dimensions must use WIDTHxHEIGHT" }
        val width = parts[0].trim().toIntOrNull()
        val height = parts[1].trim().toIntOrNull()
        require(width != null && height != null && width > 0 && height > 0) {
            "Dimensions must contain positive whole numbers"
        }
        return VideoDimensions(width, height)
    }

    private fun customBufferValue(value: String): CustomBufferPreference {
        val parts = value.split(',').map(String::trim)
        require(parts.size == 4) { "Custom buffer requires min,max,start,rebuffer" }
        val numbers = parts.map { it.toIntOrNull() ?: throw IllegalArgumentException("Buffer values must be whole numbers") }
        return CustomBufferPreference(numbers[0], numbers[1], numbers[2], numbers[3])
    }
}

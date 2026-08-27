package com.nuvio.tv.playback.settings

import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.AudioPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.ChangeImpact
import com.nuvio.tv.playback.core.CustomBufferPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.DisplayPreference
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.PlaybackBehaviorPreference
import com.nuvio.tv.playback.core.PlaybackPreferences
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SubtitlePreference
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.core.VideoPreference

const val CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION: Int = 1

enum class PlaybackPreferenceGroup {
    ENGINE,
    VIDEO,
    AUDIO,
    SUBTITLES,
    DISPLAY,
    BUFFERING,
    BEHAVIOR_UI,
    EXPERT,
}

enum class MpvOutputPreference { AUTO, DIRECT, RENDER }

/** Engine-specific choices stay quarantined from the engine-neutral core contract. */
data class ExpertPlaybackPreferences(
    val mpvOutput: MpvOutputPreference = MpvOutputPreference.AUTO,
)

data class CleanPlaybackPreferences(
    val schemaVersion: Int = CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION,
    val playback: PlaybackPreferences = PlaybackPreferences.recommended(),
    val expert: ExpertPlaybackPreferences = ExpertPlaybackPreferences(),
) {
    companion object {
        fun recommended(): CleanPlaybackPreferences = CleanPlaybackPreferences()
    }
}

/** Persistence DTO. It contains no Android/DataStore types and is safe to migrate on the JVM. */
data class PlaybackPreferenceDocument(
    val schemaVersion: Int,
    val revision: Long,
    val values: Map<String, String>,
    val preservedUnknownValues: Map<String, String> = emptyMap(),
    val legacyImportToken: String? = null,
) {
    init {
        require(schemaVersion >= 0)
        require(revision >= 0)
    }
}

enum class PreferenceDecodeWarning {
    FUTURE_SCHEMA,
    UNKNOWN_KEY,
    UNKNOWN_VALUE,
    INVALID_NUMBER,
    INVALID_CUSTOM_BUFFER,
    MIGRATED_V0,
}

data class DecodedPlaybackPreferences(
    val preferences: CleanPlaybackPreferences,
    val document: PlaybackPreferenceDocument,
    val warnings: Set<PreferenceDecodeWarning>,
)

object PlaybackPreferenceSchema {
    private const val ENGINE = "engine"
    private const val AUTOMATIC_FALLBACK = "automatic_fallback"
    private const val DECODER = "decoder"
    private const val SOFTWARE_FALLBACK = "software_decode_fallback"
    private const val BUFFERING = "buffering"
    private const val BUFFER_MIN = "buffer_min_ms"
    private const val BUFFER_MAX = "buffer_max_ms"
    private const val BUFFER_PLAYBACK = "buffer_playback_ms"
    private const val BUFFER_REBUFFER = "buffer_rebuffer_ms"
    private const val AUDIO_OUTPUT = "audio_output"
    private const val AUDIO_DOWNMIX = "audio_downmix"
    private const val AUDIO_NORMALIZATION = "audio_normalization"
    private const val AUDIO_SKIP_SILENCE = "audio_skip_silence"
    private const val AUDIO_LANGUAGE = "audio_language"
    private const val AUDIO_DELAY = "audio_delay_ms"
    private const val SUBTITLES_ENABLED = "subtitles_enabled"
    private const val SUBTITLE_FIDELITY = "subtitle_fidelity"
    private const val SUBTITLE_LANGUAGE = "subtitle_language"
    private const val SUBTITLE_DELAY = "subtitle_delay_ms"
    private const val FRAME_RATE = "frame_rate"
    private const val RESOLUTION_MATCHING = "resolution_matching"
    private const val HDR = "hdr"
    private const val MAX_WIDTH = "maximum_width"
    private const val MAX_HEIGHT = "maximum_height"
    private const val AUTOPLAY_NEXT = "autoplay_next"
    private const val STILL_WATCHING = "still_watching"
    private const val SHOW_STATUS = "show_status"
    private const val MPV_OUTPUT = "expert_mpv_output"

    private val groupKeys: Map<PlaybackPreferenceGroup, Set<String>> = mapOf(
        PlaybackPreferenceGroup.ENGINE to setOf(ENGINE, AUTOMATIC_FALLBACK),
        PlaybackPreferenceGroup.VIDEO to setOf(DECODER, SOFTWARE_FALLBACK, HDR, MAX_WIDTH, MAX_HEIGHT),
        PlaybackPreferenceGroup.AUDIO to setOf(
            AUDIO_OUTPUT,
            AUDIO_DOWNMIX,
            AUDIO_NORMALIZATION,
            AUDIO_SKIP_SILENCE,
            AUDIO_LANGUAGE,
            AUDIO_DELAY,
        ),
        PlaybackPreferenceGroup.SUBTITLES to setOf(
            SUBTITLES_ENABLED,
            SUBTITLE_FIDELITY,
            SUBTITLE_LANGUAGE,
            SUBTITLE_DELAY,
        ),
        PlaybackPreferenceGroup.DISPLAY to setOf(FRAME_RATE, RESOLUTION_MATCHING),
        PlaybackPreferenceGroup.BUFFERING to setOf(
            BUFFERING,
            BUFFER_MIN,
            BUFFER_MAX,
            BUFFER_PLAYBACK,
            BUFFER_REBUFFER,
        ),
        PlaybackPreferenceGroup.BEHAVIOR_UI to setOf(AUTOPLAY_NEXT, STILL_WATCHING, SHOW_STATUS),
        PlaybackPreferenceGroup.EXPERT to setOf(MPV_OUTPUT),
    )
    private val knownKeys: Set<String> = groupKeys.values.flatten().toSet()

    fun decode(source: PlaybackPreferenceDocument): DecodedPlaybackPreferences {
        val (document, migrated) = migrate(source)
        val warnings = linkedSetOf<PreferenceDecodeWarning>()
        if (migrated) warnings += PreferenceDecodeWarning.MIGRATED_V0
        if (document.schemaVersion > CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION) {
            warnings += PreferenceDecodeWarning.FUTURE_SCHEMA
        }
        val unknown = document.preservedUnknownValues.toMutableMap()
        document.values.forEach { (key, value) ->
            if (key !in knownKeys) {
                unknown[key] = value
                warnings += PreferenceDecodeWarning.UNKNOWN_KEY
            }
        }

        fun raw(key: String): String? = document.values[key]
        fun bool(key: String, fallback: Boolean): Boolean = when (val value = raw(key)) {
            null -> fallback
            "true" -> true
            "false" -> false
            else -> {
                unknown[key] = value
                warnings += PreferenceDecodeWarning.UNKNOWN_VALUE
                fallback
            }
        }
        fun int(key: String, fallback: Int): Int {
            val value = raw(key) ?: return fallback
            return value.toIntOrNull() ?: run {
                unknown[key] = value
                warnings += PreferenceDecodeWarning.INVALID_NUMBER
                fallback
            }
        }
        fun long(key: String, fallback: Long): Long {
            val value = raw(key) ?: return fallback
            return value.toLongOrNull() ?: run {
                unknown[key] = value
                warnings += PreferenceDecodeWarning.INVALID_NUMBER
                fallback
            }
        }
        fun <T : Enum<T>> enum(key: String, fallback: T, entries: List<T>): T {
            val value = raw(key) ?: return fallback
            return entries.firstOrNull { it.name == value } ?: run {
                unknown[key] = value
                warnings += PreferenceDecodeWarning.UNKNOWN_VALUE
                fallback
            }
        }

        val defaults = CleanPlaybackPreferences.recommended()
        val defaultPlayback = defaults.playback
        val requestedBuffering = enum(BUFFERING, defaultPlayback.buffering, BufferingPreference.entries)
        var customBufferInvalid = false
        val customBuffer = if (requestedBuffering == BufferingPreference.CUSTOM) {
            val min = int(BUFFER_MIN, 15_000)
            val max = int(BUFFER_MAX, 45_000)
            val playback = int(BUFFER_PLAYBACK, 5_000)
            val rebuffer = int(BUFFER_REBUFFER, 3_000)
            runCatching { CustomBufferPreference(min, max, playback, rebuffer) }.getOrElse {
                customBufferInvalid = true
                warnings += PreferenceDecodeWarning.INVALID_CUSTOM_BUFFER
                listOf(BUFFER_MIN, BUFFER_MAX, BUFFER_PLAYBACK, BUFFER_REBUFFER).forEach { key ->
                    raw(key)?.let { unknown[key] = it }
                }
                null
            }
        } else {
            null
        }
        val buffering = if (customBufferInvalid) BufferingPreference.RECOMMENDED else requestedBuffering
        val width = int(MAX_WIDTH, 0)
        val height = int(MAX_HEIGHT, 0)
        val maxDimensions = if (width > 0 && height > 0) VideoDimensions(width, height) else null

        val playback = PlaybackPreferences(
            schemaVersion = PlaybackPreferences.CURRENT_SCHEMA_VERSION,
            engine = enum(ENGINE, defaultPlayback.engine, EnginePreference.entries),
            automaticFallback = bool(AUTOMATIC_FALLBACK, defaultPlayback.automaticFallback),
            decoder = enum(DECODER, defaultPlayback.decoder, DecoderPreference.entries),
            softwareDecodeFallback = bool(SOFTWARE_FALLBACK, defaultPlayback.softwareDecodeFallback),
            buffering = buffering,
            customBuffer = customBuffer,
            audio = AudioPreference(
                output = enum(AUDIO_OUTPUT, defaultPlayback.audio.output, AudioOutputPreference.entries),
                downmixToStereo = bool(AUDIO_DOWNMIX, defaultPlayback.audio.downmixToStereo),
                normalization = bool(AUDIO_NORMALIZATION, defaultPlayback.audio.normalization),
                skipSilence = bool(AUDIO_SKIP_SILENCE, defaultPlayback.audio.skipSilence),
                preferredLanguage = raw(AUDIO_LANGUAGE)?.ifBlank { null },
                delayMs = long(AUDIO_DELAY, defaultPlayback.audio.delayMs),
            ),
            subtitles = SubtitlePreference(
                enabled = bool(SUBTITLES_ENABLED, defaultPlayback.subtitles.enabled),
                fidelity = enum(SUBTITLE_FIDELITY, defaultPlayback.subtitles.fidelity, SubtitleFidelity.entries),
                preferredLanguage = raw(SUBTITLE_LANGUAGE)?.ifBlank { null },
                delayMs = long(SUBTITLE_DELAY, defaultPlayback.subtitles.delayMs),
            ),
            display = DisplayPreference(
                frameRate = enum(FRAME_RATE, defaultPlayback.display.frameRate, FrameRatePreference.entries),
                resolutionMatching = bool(RESOLUTION_MATCHING, defaultPlayback.display.resolutionMatching),
            ),
            video = VideoPreference(
                hdr = enum(HDR, defaultPlayback.video.hdr, HdrPreference.entries),
                maximumDimensions = maxDimensions,
            ),
            behavior = PlaybackBehaviorPreference(
                autoplayNext = bool(AUTOPLAY_NEXT, defaultPlayback.behavior.autoplayNext),
                stillWatchingEnabled = bool(STILL_WATCHING, defaultPlayback.behavior.stillWatchingEnabled),
                showStatusIndicators = bool(SHOW_STATUS, defaultPlayback.behavior.showStatusIndicators),
            ),
        )
        val preferences = CleanPlaybackPreferences(
            playback = playback,
            expert = ExpertPlaybackPreferences(
                enum(MPV_OUTPUT, defaults.expert.mpvOutput, MpvOutputPreference.entries),
            ),
        )
        return DecodedPlaybackPreferences(
            preferences = preferences,
            document = document.copy(preservedUnknownValues = unknown.toMap()),
            warnings = warnings,
        )
    }

    fun newDocument(preferences: CleanPlaybackPreferences = CleanPlaybackPreferences.recommended()): PlaybackPreferenceDocument =
        PlaybackPreferenceDocument(
            schemaVersion = CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION,
            revision = 0,
            values = encodeValues(preferences),
        )

    fun patchGroup(
        document: PlaybackPreferenceDocument,
        preferences: CleanPlaybackPreferences,
        group: PlaybackPreferenceGroup,
    ): PlaybackPreferenceDocument {
        require(document.schemaVersion <= CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION) {
            "Cannot rewrite a future playback-preference schema"
        }
        val keys = groupKeys.getValue(group)
        val encoded = encodeValues(preferences)
        val values = document.values.toMutableMap().apply {
            keys.forEach { key ->
                encoded[key]?.let { put(key, it) } ?: remove(key)
            }
        }
        val preserved = document.preservedUnknownValues - keys
        return document.copy(
            schemaVersion = CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION,
            revision = document.revision + 1,
            values = values.toMap(),
            preservedUnknownValues = preserved,
        )
    }

    fun resetGroup(
        document: PlaybackPreferenceDocument,
        group: PlaybackPreferenceGroup,
    ): PlaybackPreferenceDocument = patchGroup(document, CleanPlaybackPreferences.recommended(), group)

    fun replaceGroup(
        original: CleanPlaybackPreferences,
        proposed: CleanPlaybackPreferences,
        group: PlaybackPreferenceGroup,
    ): CleanPlaybackPreferences {
        val old = original.playback
        val next = proposed.playback
        return when (group) {
            PlaybackPreferenceGroup.ENGINE -> original.copy(
                playback = old.copy(engine = next.engine, automaticFallback = next.automaticFallback),
            )
            PlaybackPreferenceGroup.VIDEO -> original.copy(
                playback = old.copy(
                    decoder = next.decoder,
                    softwareDecodeFallback = next.softwareDecodeFallback,
                    video = next.video,
                ),
            )
            PlaybackPreferenceGroup.AUDIO -> original.copy(playback = old.copy(audio = next.audio))
            PlaybackPreferenceGroup.SUBTITLES -> original.copy(playback = old.copy(subtitles = next.subtitles))
            PlaybackPreferenceGroup.DISPLAY -> original.copy(playback = old.copy(display = next.display))
            PlaybackPreferenceGroup.BUFFERING -> original.copy(
                playback = old.copy(buffering = next.buffering, customBuffer = next.customBuffer),
            )
            PlaybackPreferenceGroup.BEHAVIOR_UI -> original.copy(playback = old.copy(behavior = next.behavior))
            PlaybackPreferenceGroup.EXPERT -> original.copy(expert = proposed.expert)
        }
    }

    fun impact(group: PlaybackPreferenceGroup): ChangeImpact = when (group) {
        PlaybackPreferenceGroup.BEHAVIOR_UI,
        -> ChangeImpact.APPLY_IN_PLACE
        PlaybackPreferenceGroup.AUDIO,
        PlaybackPreferenceGroup.BUFFERING,
        -> ChangeImpact.REBUILD_CURRENT_GRAPH
        PlaybackPreferenceGroup.ENGINE,
        PlaybackPreferenceGroup.VIDEO,
        PlaybackPreferenceGroup.SUBTITLES,
        PlaybackPreferenceGroup.DISPLAY,
        PlaybackPreferenceGroup.EXPERT,
        -> ChangeImpact.RESELECT_GRAPH
    }

    private fun encodeValues(preferences: CleanPlaybackPreferences): Map<String, String> = buildMap {
        val p = preferences.playback
        put(ENGINE, p.engine.name)
        put(AUTOMATIC_FALLBACK, p.automaticFallback.toString())
        put(DECODER, p.decoder.name)
        put(SOFTWARE_FALLBACK, p.softwareDecodeFallback.toString())
        put(BUFFERING, p.buffering.name)
        p.customBuffer?.let {
            put(BUFFER_MIN, it.minimumBufferMs.toString())
            put(BUFFER_MAX, it.maximumBufferMs.toString())
            put(BUFFER_PLAYBACK, it.playbackStartBufferMs.toString())
            put(BUFFER_REBUFFER, it.rebufferStartBufferMs.toString())
        }
        put(AUDIO_OUTPUT, p.audio.output.name)
        put(AUDIO_DOWNMIX, p.audio.downmixToStereo.toString())
        put(AUDIO_NORMALIZATION, p.audio.normalization.toString())
        put(AUDIO_SKIP_SILENCE, p.audio.skipSilence.toString())
        p.audio.preferredLanguage?.let { put(AUDIO_LANGUAGE, it) }
        put(AUDIO_DELAY, p.audio.delayMs.toString())
        put(SUBTITLES_ENABLED, p.subtitles.enabled.toString())
        put(SUBTITLE_FIDELITY, p.subtitles.fidelity.name)
        p.subtitles.preferredLanguage?.let { put(SUBTITLE_LANGUAGE, it) }
        put(SUBTITLE_DELAY, p.subtitles.delayMs.toString())
        put(FRAME_RATE, p.display.frameRate.name)
        put(RESOLUTION_MATCHING, p.display.resolutionMatching.toString())
        put(HDR, p.video.hdr.name)
        p.video.maximumDimensions?.let {
            put(MAX_WIDTH, it.width.toString())
            put(MAX_HEIGHT, it.height.toString())
        }
        put(AUTOPLAY_NEXT, p.behavior.autoplayNext.toString())
        put(STILL_WATCHING, p.behavior.stillWatchingEnabled.toString())
        put(SHOW_STATUS, p.behavior.showStatusIndicators.toString())
        put(MPV_OUTPUT, preferences.expert.mpvOutput.name)
    }

    private fun migrate(source: PlaybackPreferenceDocument): Pair<PlaybackPreferenceDocument, Boolean> {
        if (source.schemaVersion != 0) return source to false
        val values = source.values.toMutableMap()
        values.remove("player_engine")?.let { raw ->
            values[ENGINE] = when (raw) {
                "EXOPLAYER" -> EnginePreference.MEDIA3.name
                "MVP_PLAYER" -> EnginePreference.LIBMPV.name
                else -> EnginePreference.AUTO.name
            }
        }
        values.remove("auto_switch_on_error")?.let { values[AUTOMATIC_FALLBACK] = it }
        values.remove("low_latency")?.let {
            values[BUFFERING] = if (it == "true") {
                BufferingPreference.LOW_LATENCY_LIVE.name
            } else {
                BufferingPreference.RECOMMENDED.name
            }
        }
        return source.copy(
            schemaVersion = CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION,
            revision = source.revision + 1,
            values = values.toMap(),
        ) to true
    }
}

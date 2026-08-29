package com.nuvio.tv.playback.wiring

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.playback.settings.LegacyPreferenceImportResult
import com.nuvio.tv.playback.settings.LegacyPlayerSettingsSnapshot
import com.nuvio.tv.playback.settings.PlaybackPreferenceRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The only production bridge from the frozen legacy settings model into the detached clean import
 * contract. It reads an immutable value snapshot, mutates neither model, and has no resolver or
 * engine dependency.
 */
object LegacyPlayerSettingsSnapshotMapper {
    val topLevelFieldNames: Set<String> = setOf(
        "playerPreference",
        "internalPlayerEngine",
        "autoSwitchInternalPlayerOnError",
        "useLibass",
        "libassRenderType",
        "subtitleStyle",
        "bufferSettings",
        "decoderPriority",
        "downmixEnabled",
        "audioOutputChannels",
        "maintainOriginalAudioOnDownmix",
        "tunnelingEnabled",
        "forceOpticalPassthrough",
        "skipSilence",
        "audioAmplificationDb",
        "centerMixLevelDb",
        "persistAudioAmplification",
        "rememberAudioDelayPerDevice",
        "preferredAudioLanguage",
        "secondaryPreferredAudioLanguage",
        "loadingOverlayEnabled",
        "showPlayerLoadingStatus",
        "playbackIssueReportsEnabled",
        "pauseOverlayEnabled",
        "osdClockEnabled",
        "skipIntroEnabled",
        "parentalGuideEnabled",
        "autoSkipSegmentTypes",
        "dv5ToDv81Enabled",
        "dv7ToDv81PreserveMappingEnabled",
        "dv7HandlingMode",
        "dv7LibdoviModeOverride",
        "stripHdr10PlusSei",
        "mpvHardwareDecodeMode",
        "frameRateMatchingMode",
        "resolutionMatchingEnabled",
        "streamAutoPlayMode",
        "streamAutoPlaySource",
        "streamAutoPlaySelectedAddons",
        "streamAutoPlaySelectedPlugins",
        "streamAutoPlayRegex",
        "streamAutoPlayNextEpisodeEnabled",
        "streamAutoPlayPreferBingeGroupForNextEpisode",
        "streamAutoPlayReuseBingeGroup",
        "streamAutoPlayTimeoutSeconds",
        "stillWatchingEnabled",
        "stillWatchingEpisodeThreshold",
        "nextEpisodeThresholdMode",
        "nextEpisodeThresholdPercent",
        "nextEpisodeThresholdMinutesBeforeEnd",
        "streamReuseLastLinkEnabled",
        "streamReuseLastLinkCacheHours",
        "externalPlayerForwardSubtitles",
        "externalPlayerSendSkipSegments",
        "subtitleOrganizationMode",
        "bufferEngineEnabled",
        "parallelNetworkEnabled",
        "bufferBudgetManaged",
        "allowLargeTargetBuffer",
        "vodCacheEnabled",
        "vodCacheSizeMode",
        "vodCacheSizeMb",
        "useParallelConnections",
        "parallelConnectionCount",
        "parallelChunkSizeKb",
        "enableHttp2",
        "addonSubtitleStartupMode",
        "enableBufferLogs",
        "resizeMode",
        "nuvioPerformanceModeEnabled",
    )

    /**
     * Raw DataStore key names → snapshot field names for the fields where a materialized
     * legacy default must never import as an explicit user choice. Fields without an entry
     * keep the previous imported-as-stored behavior.
     */
    private val RAW_KEY_TO_GATED_FIELD = mapOf(
        "internal_player_engine" to "internalPlayerEngine",
        "auto_switch_internal_player_on_error" to "autoSwitchInternalPlayerOnError",
    )

    fun map(
        settings: PlayerSettings,
        importToken: String,
        storedRawKeyNames: Set<String>? = null,
    ): LegacyPlayerSettingsSnapshot {
        val values = linkedMapOf(
            "playerPreference" to settings.playerPreference.name,
            "internalPlayerEngine" to settings.internalPlayerEngine.name,
            "autoSwitchInternalPlayerOnError" to settings.autoSwitchInternalPlayerOnError.toString(),
            "useLibass" to settings.useLibass.toString(),
            "libassRenderType" to settings.libassRenderType.name,
            "subtitleStyle" to settings.subtitleStyle.toString(),
            "bufferSettings" to settings.bufferSettings.toString(),
            "decoderPriority" to settings.decoderPriority.toString(),
            "downmixEnabled" to settings.downmixEnabled.toString(),
            "audioOutputChannels" to settings.audioOutputChannels.name,
            "maintainOriginalAudioOnDownmix" to settings.maintainOriginalAudioOnDownmix.toString(),
            "tunnelingEnabled" to settings.tunnelingEnabled.toString(),
            "forceOpticalPassthrough" to settings.forceOpticalPassthrough.toString(),
            "skipSilence" to settings.skipSilence.toString(),
            "audioAmplificationDb" to settings.audioAmplificationDb.toString(),
            "centerMixLevelDb" to settings.centerMixLevelDb.toString(),
            "persistAudioAmplification" to settings.persistAudioAmplification.toString(),
            "rememberAudioDelayPerDevice" to settings.rememberAudioDelayPerDevice.toString(),
            "preferredAudioLanguage" to settings.preferredAudioLanguage,
            "secondaryPreferredAudioLanguage" to settings.secondaryPreferredAudioLanguage.orEmpty(),
            "loadingOverlayEnabled" to settings.loadingOverlayEnabled.toString(),
            "showPlayerLoadingStatus" to settings.showPlayerLoadingStatus.toString(),
            "playbackIssueReportsEnabled" to settings.playbackIssueReportsEnabled.toString(),
            "pauseOverlayEnabled" to settings.pauseOverlayEnabled.toString(),
            "osdClockEnabled" to settings.osdClockEnabled.toString(),
            "skipIntroEnabled" to settings.skipIntroEnabled.toString(),
            "parentalGuideEnabled" to settings.parentalGuideEnabled.toString(),
            "autoSkipSegmentTypes" to settings.autoSkipSegmentTypes.map { it.name }.sorted().joinToString(","),
            "dv5ToDv81Enabled" to settings.dv5ToDv81Enabled.toString(),
            "dv7ToDv81PreserveMappingEnabled" to settings.dv7ToDv81PreserveMappingEnabled.toString(),
            "dv7HandlingMode" to settings.dv7HandlingMode.name,
            "dv7LibdoviModeOverride" to settings.dv7LibdoviModeOverride.toString(),
            "stripHdr10PlusSei" to settings.stripHdr10PlusSei.toString(),
            "mpvHardwareDecodeMode" to settings.mpvHardwareDecodeMode.name,
            "frameRateMatchingMode" to settings.frameRateMatchingMode.name,
            "resolutionMatchingEnabled" to settings.resolutionMatchingEnabled.toString(),
            "streamAutoPlayMode" to settings.streamAutoPlayMode.name,
            "streamAutoPlaySource" to settings.streamAutoPlaySource.name,
            "streamAutoPlaySelectedAddons" to settings.streamAutoPlaySelectedAddons.sorted().joinToString(","),
            "streamAutoPlaySelectedPlugins" to settings.streamAutoPlaySelectedPlugins.sorted().joinToString(","),
            "streamAutoPlayRegex" to settings.streamAutoPlayRegex,
            "streamAutoPlayNextEpisodeEnabled" to settings.streamAutoPlayNextEpisodeEnabled.toString(),
            "streamAutoPlayPreferBingeGroupForNextEpisode" to
                settings.streamAutoPlayPreferBingeGroupForNextEpisode.toString(),
            "streamAutoPlayReuseBingeGroup" to settings.streamAutoPlayReuseBingeGroup.toString(),
            "streamAutoPlayTimeoutSeconds" to settings.streamAutoPlayTimeoutSeconds.toString(),
            "stillWatchingEnabled" to settings.stillWatchingEnabled.toString(),
            "stillWatchingEpisodeThreshold" to settings.stillWatchingEpisodeThreshold.toString(),
            "nextEpisodeThresholdMode" to settings.nextEpisodeThresholdMode.name,
            "nextEpisodeThresholdPercent" to settings.nextEpisodeThresholdPercent.toString(),
            "nextEpisodeThresholdMinutesBeforeEnd" to settings.nextEpisodeThresholdMinutesBeforeEnd.toString(),
            "streamReuseLastLinkEnabled" to settings.streamReuseLastLinkEnabled.toString(),
            "streamReuseLastLinkCacheHours" to settings.streamReuseLastLinkCacheHours.toString(),
            "externalPlayerForwardSubtitles" to settings.externalPlayerForwardSubtitles.toString(),
            "externalPlayerSendSkipSegments" to settings.externalPlayerSendSkipSegments.toString(),
            "subtitleOrganizationMode" to settings.subtitleOrganizationMode.name,
            "bufferEngineEnabled" to settings.bufferEngineEnabled.toString(),
            "parallelNetworkEnabled" to settings.parallelNetworkEnabled.toString(),
            "bufferBudgetManaged" to settings.bufferBudgetManaged.toString(),
            "allowLargeTargetBuffer" to settings.allowLargeTargetBuffer.toString(),
            "vodCacheEnabled" to settings.vodCacheEnabled.toString(),
            "vodCacheSizeMode" to settings.vodCacheSizeMode.name,
            "vodCacheSizeMb" to settings.vodCacheSizeMb.toString(),
            "useParallelConnections" to settings.useParallelConnections.toString(),
            "parallelConnectionCount" to settings.parallelConnectionCount.toString(),
            "parallelChunkSizeKb" to settings.parallelChunkSizeKb.toString(),
            "enableHttp2" to settings.enableHttp2.toString(),
            "addonSubtitleStartupMode" to settings.addonSubtitleStartupMode.name,
            "enableBufferLogs" to settings.enableBufferLogs.toString(),
            "resizeMode" to settings.resizeMode.toString(),
            "nuvioPerformanceModeEnabled" to settings.nuvioPerformanceModeEnabled.toString(),
        )
        check(values.keys == topLevelFieldNames) { "Legacy playback snapshot mapper is not exhaustive" }
        values += mapOf(
            "bufferSettings.minBufferMs" to settings.bufferSettings.minBufferMs.toString(),
            "bufferSettings.maxBufferMs" to settings.bufferSettings.maxBufferMs.toString(),
            "bufferSettings.bufferForPlaybackMs" to settings.bufferSettings.bufferForPlaybackMs.toString(),
            "bufferSettings.bufferForPlaybackAfterRebufferMs" to
                settings.bufferSettings.bufferForPlaybackAfterRebufferMs.toString(),
            "bufferSettings.targetBufferSizeMb" to settings.bufferSettings.targetBufferSizeMb.toString(),
            "bufferSettings.backBufferDurationMs" to settings.bufferSettings.backBufferDurationMs.toString(),
            "bufferSettings.retainBackBufferFromKeyframe" to
                settings.bufferSettings.retainBackBufferFromKeyframe.toString(),
        )
        val storedFieldNames = storedRawKeyNames?.let { raw ->
            buildSet {
                addAll(values.keys)
                RAW_KEY_TO_GATED_FIELD.forEach { (rawKey, field) ->
                    if (rawKey !in raw) remove(field)
                }
            }
        }
        return LegacyPlayerSettingsSnapshot(
            importToken = importToken,
            values = values,
            storedFieldNames = storedFieldNames,
        )
    }
}

/** Atomic cutover entry point; the clean repository retains idempotence and isolated ownership. */
suspend fun PlaybackPreferenceRepository.importTypedLegacyIfAbsent(
    profileId: String,
    settings: PlayerSettings,
    importToken: String,
): LegacyPreferenceImportResult = importLegacyIfAbsent(
    profileId = profileId,
    legacy = LegacyPlayerSettingsSnapshotMapper.map(settings, importToken),
)

/** Profile-explicit bridge used only while bootstrapping the detached clean repository. */
internal fun interface LegacyPlaybackPreferenceSnapshotSource {
    suspend fun snapshot(profileId: String): LegacyPlayerSettingsSnapshot
}

@Singleton
internal class ActiveProfileLegacyPlaybackPreferenceSnapshotSource @Inject constructor(
    private val profileManager: ProfileManager,
    private val legacyStore: PlayerSettingsDataStore,
) : LegacyPlaybackPreferenceSnapshotSource {
    override suspend fun snapshot(profileId: String): LegacyPlayerSettingsSnapshot {
        val numericProfileId = profileId.toIntOrNull()?.takeIf { it > 0 }
            ?: error("Playback preference profile id is invalid")
        profileManager.activeProfileReady.first { it }
        check(profileManager.activeProfileId.value == numericProfileId) {
            "Playback preference bootstrap profile is not active"
        }
        val settings = legacyStore.playerSettings.first()
        val storedRawKeyNames = legacyStore.storedPlayerSettingKeyNames.first()
        check(profileManager.activeProfileId.value == numericProfileId) {
            "Playback preference profile changed during bootstrap"
        }
        return LegacyPlayerSettingsSnapshotMapper.map(
            settings,
            LEGACY_PLAYBACK_IMPORT_TOKEN,
            storedRawKeyNames,
        )
    }

    private companion object {
        const val LEGACY_PLAYBACK_IMPORT_TOKEN = "player-settings-v1"
    }
}

package com.nuvio.tv.data.local

import com.nuvio.tv.core.picture.LivePicturePort
import com.nuvio.tv.core.picture.PictureChoice
import com.nuvio.tv.core.picture.PlayerPreferencePolicy
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F28: the live picture (aspect + manual zoom) through lane F's preference model, per CHANNEL —
 * the live counterpart of the per-series memory. With "Remember my player preferences" on, a
 * channel keeps its own picture; otherwise (and for a channel never adjusted) it starts from the
 * device's last-used aspect with no zoom, exactly like VOD (PlayerPreferencePolicy.initialPicture).
 * Live never writes the device-wide aspect: a crop chosen for one 4:3 channel must not crop movies.
 */
@Singleton
class LivePicturePreferences @Inject constructor(
    private val trackPreferences: TrackPreferenceDataStore,
    private val devicePreferences: DeviceLocalPlayerPreferences,
    private val playerSettings: PlayerSettingsDataStore,
) : LivePicturePort {
    override suspend fun initial(channelId: String): PictureChoice {
        val remember = playerSettings.playerSettings.first().rememberPlayerPreferences
        val stored = if (remember && channelId.isNotBlank()) trackPreferences.loadPicture(channelId) else null
        return PlayerPreferencePolicy.initialPicture(
            rememberEnabled = remember,
            stored = stored,
            globalAspectMode = devicePreferences.aspectMode.first(),
        )
    }

    override suspend fun save(channelId: String, choice: PictureChoice) {
        val remember = playerSettings.playerSettings.first().rememberPlayerPreferences
        if (!PlayerPreferencePolicy.persistsSeriesChoice(remember, channelId)) return
        trackPreferences.savePicture(channelId, PlayerPreferencePolicy.pictureMemory(choice.aspectMode, choice.zoom))
    }
}

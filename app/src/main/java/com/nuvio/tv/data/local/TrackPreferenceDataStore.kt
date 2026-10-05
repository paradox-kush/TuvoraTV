package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "track_preference"
        private const val SUB_TYPE = "sub_type"
        private const val SUB_LANG = "sub_lang"
        private const val SUB_NAME = "sub_name"
        private const val SUB_TRACK_ID = "sub_track_id"
        private const val SUB_IS_FORCED = "sub_is_forced"
        private const val SUB_ADDON_ID = "sub_addon_id"
        private const val SUB_ADDON_URL = "sub_addon_url"
        private const val SUB_ADDON_NAME = "sub_addon_name"
        private const val AUDIO_LANG = "audio_lang"
        private const val AUDIO_NAME = "audio_name"
        private const val AUDIO_TRACK_ID = "audio_track_id"
        // Keyed per-videoId (not per-contentId) so that a delay calibrated for
        // one episode is not blindly reapplied to the next episode where it is
        // almost certainly wrong.
        private const val SUB_DELAY_MS = "sub_delay_ms"
        private const val PLAYBACK_SPEED = "playback_speed"

        // F37/F36 per-series picture memory. A SEPARATE feature store on purpose: "track_preference"
        // is in ProfileSettingsSyncService.syncedFeatures, and syncing the picture memory is a sync
        // payload change held for an owner decision. This store is device-local.
        private const val PICTURE_FEATURE = "picture_preference"
        private const val ASPECT_MODE = "aspect_mode"
        private const val ZOOM_SCALE_X = "zoom_scale_x"
        private const val ZOOM_SCALE_Y = "zoom_scale_y"
        private const val ZOOM_PAN_X = "zoom_pan_x"
        private const val ZOOM_PAN_Y = "zoom_pan_y"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private fun pictureStore() = factory.get(profileManager.activeProfileId.value, PICTURE_FEATURE)

    suspend fun savePicture(contentId: String, memory: com.nuvio.tv.core.picture.PictureMemory) {
        pictureStore().edit { prefs ->
            val aspectKey = key(ASPECT_MODE, contentId)
            if (memory.aspectMode != null) prefs[aspectKey] = memory.aspectMode else prefs.remove(aspectKey)
            val zoom = memory.zoom
            listOf(
                ZOOM_SCALE_X to zoom?.scaleX,
                ZOOM_SCALE_Y to zoom?.scaleY,
                ZOOM_PAN_X to zoom?.panX,
                ZOOM_PAN_Y to zoom?.panY,
            ).forEach { (field, value) ->
                val k = floatKey(field, contentId)
                if (value != null) prefs[k] = value else prefs.remove(k)
            }
        }
    }

    suspend fun loadPicture(contentId: String): com.nuvio.tv.core.picture.PictureMemory? {
        val prefs = pictureStore().data.first()
        val aspect = prefs[key(ASPECT_MODE, contentId)]
        val sx = prefs[floatKey(ZOOM_SCALE_X, contentId)]
        val sy = prefs[floatKey(ZOOM_SCALE_Y, contentId)]
        val px = prefs[floatKey(ZOOM_PAN_X, contentId)]
        val py = prefs[floatKey(ZOOM_PAN_Y, contentId)]
        val zoom = if (sx == null && sy == null && px == null && py == null) {
            null
        } else {
            com.nuvio.tv.core.picture.VideoZoom(sx ?: 1f, sy ?: 1f, px ?: 0f, py ?: 0f)
        }
        if (aspect == null && zoom == null) return null
        return com.nuvio.tv.core.picture.PictureMemory(aspectMode = aspect, zoom = zoom)
    }

    private fun key(field: String, contentId: String) =
        stringPreferencesKey("$field|$contentId")

    private fun intKey(field: String, id: String) =
        intPreferencesKey("$field|$id")

    private fun floatKey(field: String, id: String) =
        floatPreferencesKey("$field|$id")

    suspend fun save(contentId: String, pref: PersistedTrackPreference) {
        store().edit { prefs ->
            fun set(field: String, value: String?) {
                val k = key(field, contentId)
                if (value != null) prefs[k] = value else prefs.remove(k)
            }
            set(SUB_TYPE, pref.subtitleType)
            set(SUB_LANG, pref.subtitleLanguage)
            set(SUB_NAME, pref.subtitleName)
            set(SUB_TRACK_ID, pref.subtitleTrackId)
            set(SUB_IS_FORCED, pref.subtitleIsForced?.toString())
            set(SUB_ADDON_ID, pref.addonSubtitleId)
            set(SUB_ADDON_URL, pref.addonSubtitleUrl)
            set(SUB_ADDON_NAME, pref.addonSubtitleAddonName)
            set(AUDIO_LANG, pref.audioLanguage)
            set(AUDIO_NAME, pref.audioName)
            set(AUDIO_TRACK_ID, pref.audioTrackId)
        }
    }

    suspend fun load(contentId: String): PersistedTrackPreference? {
        val prefs = store().data.first()
        val subType = prefs[key(SUB_TYPE, contentId)]
        val audioLang = prefs[key(AUDIO_LANG, contentId)]
        val audioName = prefs[key(AUDIO_NAME, contentId)]
        val audioTrackId = prefs[key(AUDIO_TRACK_ID, contentId)]
        if (
            subType == null &&
            audioLang == null &&
            audioName == null &&
            audioTrackId == null
        ) return null
        return PersistedTrackPreference(
            subtitleType = subType,
            subtitleLanguage = prefs[key(SUB_LANG, contentId)],
            subtitleName = prefs[key(SUB_NAME, contentId)],
            subtitleTrackId = prefs[key(SUB_TRACK_ID, contentId)],
            subtitleIsForced = prefs[key(SUB_IS_FORCED, contentId)]?.toBooleanStrictOrNull(),
            addonSubtitleId = prefs[key(SUB_ADDON_ID, contentId)],
            addonSubtitleUrl = prefs[key(SUB_ADDON_URL, contentId)],
            addonSubtitleAddonName = prefs[key(SUB_ADDON_NAME, contentId)],
            audioLanguage = audioLang,
            audioName = audioName,
            audioTrackId = audioTrackId
        )
    }

    /**
     * Subtitle delay is persisted separately from audio/subtitle track selection
     * because it has different locality: tracks sensibly apply to every episode
     * of a series (same preferred language), but a delay calibrated against one
     * release/encode rarely transfers to the next episode. Keying by videoId
     * scopes the delay to exactly the video it was synced against. See #1063.
     */
    suspend fun saveSubtitleDelayMs(videoId: String, delayMs: Int?) {
        store().edit { prefs ->
            val k = intKey(SUB_DELAY_MS, videoId)
            if (delayMs != null && delayMs != 0) prefs[k] = delayMs else prefs.remove(k)
        }
    }

    suspend fun loadSubtitleDelayMs(videoId: String): Int? {
        return store().data.first()[intKey(SUB_DELAY_MS, videoId)]
    }

    suspend fun savePlaybackSpeed(contentId: String, speed: Float?) {
        store().edit { prefs ->
            val k = floatKey(PLAYBACK_SPEED, contentId)
            if (speed != null && speed != 1f) prefs[k] = speed else prefs.remove(k)
        }
    }

    suspend fun loadPlaybackSpeed(contentId: String): Float? {
        return store().data.first()[floatKey(PLAYBACK_SPEED, contentId)]
    }
}

data class PersistedTrackPreference(
    val subtitleType: String?,
    val subtitleLanguage: String?,
    val subtitleName: String?,
    val subtitleTrackId: String?,
    val subtitleIsForced: Boolean? = null,
    val addonSubtitleId: String?,
    val addonSubtitleUrl: String?,
    val addonSubtitleAddonName: String?,
    val audioLanguage: String?,
    val audioName: String?,
    val audioTrackId: String?
)

internal fun PersistedTrackPreference.toTrackPreference(): com.nuvio.tv.ui.screens.player.PlayerRuntimeController.TrackPreference? {
    val audio = if (audioLanguage != null || audioName != null || audioTrackId != null) {
        com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedTrackSelection(
            language = audioLanguage,
            name = audioName,
            trackId = audioTrackId
        )
    } else null

    val subtitle = when (subtitleType) {
        "INTERNAL" -> com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            track = com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedTrackSelection(
                language = subtitleLanguage,
                name = subtitleName,
                trackId = subtitleTrackId,
                isForcedHint = subtitleIsForced
            )
        )
        "ADDON" -> com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            id = addonSubtitleId ?: "",
            url = addonSubtitleUrl ?: "",
            language = subtitleLanguage ?: "",
            addonName = addonSubtitleAddonName ?: ""
        )
        "DISABLED" -> com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        else -> null
    }

    if (audio == null && subtitle == null) return null
    return com.nuvio.tv.ui.screens.player.PlayerRuntimeController.TrackPreference(
        audio = audio,
        subtitle = subtitle
    )
}

package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * F37 + F36 on TV: the per-series picture memory (aspect mode + manual zoom) and the manual-zoom
 * events. Decisions live in PlayerPreferencePolicy; this file only moves values between the policy,
 * the UI state and TrackPreferenceDataStore's device-local picture store.
 */

/** Suspend: runs inside preparePlaybackBeforeStart, before initializePlayer reads the aspect. */
internal suspend fun PlayerRuntimeController.restoreSeriesPicture() {
    val id = contentId?.takeIf { it.isNotBlank() }
    val stored = if (id != null && rememberPlayerPreferences) trackPreferenceDataStore.loadPicture(id) else null
    val choice = PlayerPreferencePolicy.initialPicture(
        rememberEnabled = rememberPlayerPreferences,
        stored = stored,
        globalAspectMode = deviceLocalPlayerPreferences.aspectMode.first(),
    )
    seriesPictureApplied = true
    Log.d(PlayerRuntimeController.TAG, "PICTURE_PREF restore: remembered=${stored != null} -> $choice")
    _uiState.update { it.copy(aspectMode = choice.aspectMode, videoZoom = choice.zoom) }
}

internal fun PlayerRuntimeController.persistSeriesPicture() {
    val id = contentId ?: return
    if (!PlayerPreferencePolicy.persistsSeriesChoice(rememberPlayerPreferences, id)) return
    val state = _uiState.value
    val memory = PlayerPreferencePolicy.pictureMemory(state.aspectMode, state.videoZoom)
    scope.launch { trackPreferenceDataStore.savePicture(id, memory) }
}

internal fun PlayerRuntimeController.handleVideoZoomEvent(event: PlayerEvent) {
    when (event) {
        PlayerEvent.OnShowVideoZoomPanel -> {
            if (_uiState.value.tunnelingEnabled) {
                // Tunneled video ignores view scale (PlayerAspectScaleUtils): say so instead of a dead panel.
                _uiState.update {
                    it.copy(
                        showAspectRatioIndicator = true,
                        aspectRatioIndicatorText = context.getString(R.string.player_zoom_unavailable_tunneling),
                    )
                }
                hideAspectRatioIndicatorJob?.cancel()
                hideAspectRatioIndicatorJob = scope.launch {
                    delay(1500)
                    _uiState.update { it.copy(showAspectRatioIndicator = false) }
                }
                return
            }
            _uiState.update {
                it.copy(
                    showVideoZoomPanel = true,
                    showAudioOverlay = false,
                    showSubtitleOverlay = false,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSpeedDialog = false,
                    showControls = true,
                )
            }
        }
        PlayerEvent.OnDismissVideoZoomPanel -> {
            _uiState.update { it.copy(showVideoZoomPanel = false) }
            scheduleHideControls()
        }
        is PlayerEvent.OnAdjustVideoZoom -> {
            _uiState.update { it.copy(videoZoom = VideoZoomPolicy.adjust(it.videoZoom, event.axis, event.steps)) }
            persistSeriesPicture()
        }
        PlayerEvent.OnResetVideoZoom -> {
            _uiState.update { it.copy(videoZoom = VideoZoom.IDENTITY) }
            persistSeriesPicture()
        }
        else -> Unit
    }
}

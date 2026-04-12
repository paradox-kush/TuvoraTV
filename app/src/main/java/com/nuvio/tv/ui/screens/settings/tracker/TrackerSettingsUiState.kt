package com.nuvio.tv.ui.screens.settings.tracker

import com.nuvio.tv.domain.model.TrackerListStatus

/**
 * Shape shared by [MalSettingsViewModel] / [AniListSettingsViewModel] /
 * [KitsuSettingsViewModel]. All three anime-tracker subscreens render from
 * this single data class — the composable [TrackerSettingsContent] never
 * needs to know which service it's talking to.
 */
data class TrackerSettingsUiState(
    val serviceName: String,
    val connection: Connection = Connection.Disconnected,
    val sendProgressEnabled: Boolean = true,
    /** All statuses this tracker exposes — unavailable ones (e.g. MAL has no REWATCHING) are omitted. */
    val availableStatuses: List<TrackerListStatus> = TrackerListStatus.values().toList(),
    val enabledStatuses: Set<TrackerListStatus> = emptySet(),
    val transientMessage: String? = null,
    val errorMessage: String? = null
) {
    val isConnecting: Boolean get() = connection is Connection.AwaitingPhone
    val isConnected: Boolean get() = connection is Connection.Connected

    sealed interface Connection {
        data object Disconnected : Connection
        data class AwaitingPhone(
            val code: String,
            val webUrl: String,
            val expiresAtEpochMs: Long
        ) : Connection
        data class Connected(
            val username: String?,
            val expiresAtEpochMs: Long?
        ) : Connection
    }
}

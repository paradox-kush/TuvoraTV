package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.radar.RadarRepository
import com.nuvio.tv.core.radar.RadarStore
import com.nuvio.tv.core.radar.alerts.BundledLeagues
import com.nuvio.tv.core.radar.alerts.SportsAlertSettings
import com.nuvio.tv.domain.model.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the sports-alerts settings page shows. Read straight from the radar store: opening it fetches nothing. */
data class SportsAlertsSettingsUiState(
    val settings: SportsAlertSettings = SportsAlertSettings(),
    val signedIn: Boolean = false,
    val leagues: List<Followed> = emptyList(),
    val teams: List<Followed> = emptyList(),
) {
    data class Followed(val id: String, val name: String)
}

@HiltViewModel
class SportsAlertsSettingsViewModel @Inject constructor(
    private val repository: RadarRepository,
    store: RadarStore,
    authManager: AuthManager,
) : ViewModel() {
    val state: StateFlow<SportsAlertsSettingsUiState> = combine(store.state, authManager.authState) { local, auth ->
        SportsAlertsSettingsUiState(
            settings = local.prefs.alerts,
            signedIn = auth is AuthState.FullAccount,
            leagues = local.follows.sortedBy { it.sortOrder }.map {
                SportsAlertsSettingsUiState.Followed(it.leagueId, it.name?.takeIf(String::isNotBlank) ?: BundledLeagues.nameOf(it.leagueId) ?: "League ${it.leagueId}")
            },
            teams = local.teams.sortedBy { it.sortOrder }.map {
                SportsAlertsSettingsUiState.Followed(it.teamId, it.name.ifBlank { "Team ${it.teamId}" })
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsAlertsSettingsUiState())

    /** One edit = one store transaction (value + dirty flag) + a debounced push, like every alerts control. */
    fun update(transform: (SportsAlertSettings) -> SportsAlertSettings) = repository.updateAlerts(transform)
}

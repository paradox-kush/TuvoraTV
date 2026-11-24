@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.radar

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.radar.RadarRepository
import com.nuvio.tv.core.radar.RadarStore
import com.nuvio.tv.core.radar.alerts.GameAlertChoice
import com.nuvio.tv.core.radar.alerts.SportsAlertLabels
import com.nuvio.tv.core.radar.alerts.SportsAlertSettings
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.screens.calendar.CalendarChip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** State and actions for the two quick alert controls (Sports hub header chip, game page chip). Reads the store only. */
@HiltViewModel
class SportsAlertsChipViewModel @Inject constructor(
    private val repository: RadarRepository,
    store: RadarStore,
    authManager: AuthManager,
) : ViewModel() {
    data class State(
        val settings: SportsAlertSettings = SportsAlertSettings(),
        val signedIn: Boolean = false,
        val followedLeagueIds: Set<String> = emptySet(),
        val followedTeamIds: Set<String> = emptySet(),
    )

    val state: StateFlow<State> = combine(store.state, authManager.authState) { local, auth ->
        State(
            settings = local.prefs.alerts,
            signedIn = auth is AuthState.FullAccount,
            followedLeagueIds = local.follows.map { it.leagueId }.toSet(),
            followedTeamIds = local.teams.map { it.teamId }.toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun toggleEnabled() = repository.updateAlerts { it.copy(enabled = !it.enabled) }

    /** Default -> All -> Off -> Default for ONE game; forgotten a day after kick-off. */
    fun cycleGame(eventId: String, kickoffMs: Long, current: GameAlertChoice) {
        val now = System.currentTimeMillis()
        repository.updateAlerts { it.withGameChoice(eventId, current.next(), expMs = kickoffMs + GAME_OVERRIDE_TTL_MS, nowMs = now) }
    }

    private companion object {
        const val GAME_OVERRIDE_TTL_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * Sports hub header, beside the Scores chip: "Alerts on" / "Alerts off". Signed out: "Sign in for alerts" (shown,
 * not focusable: alerts need an account, and a dead control must not catch the D-pad).
 */
@Composable
internal fun SportsAlertsHubChip(modifier: Modifier = Modifier, viewModel: SportsAlertsChipViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    CalendarChip(
        label = if (ui.signedIn) SportsAlertLabels.hubChip(ui.settings.enabled) else SportsAlertLabels.SIGN_IN_CHIP,
        selected = ui.signedIn && ui.settings.enabled,
        onClick = { if (ui.signedIn) viewModel.toggleEnabled() },
        modifier = if (ui.signedIn) modifier else modifier.focusProperties { canFocus = false },
    )
}

/**
 * Game page chip "Alerts: Default / All / Off", only for a game in a followed league or with a followed team.
 * Signed out: "Sign in for alerts", not focusable. Null when the game is not followed (nothing to override).
 */
@Composable
internal fun GameAlertsChip(
    eventId: String,
    leagueId: String?,
    homeId: String?,
    awayId: String?,
    kickoffMs: Long?,
    modifier: Modifier = Modifier,
    viewModel: SportsAlertsChipViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val followed = (leagueId != null && leagueId in ui.followedLeagueIds) ||
        (homeId != null && homeId in ui.followedTeamIds) || (awayId != null && awayId in ui.followedTeamIds)
    if (!followed || kickoffMs == null) return
    val choice = ui.settings.choiceFor(eventId, System.currentTimeMillis())
    CalendarChip(
        label = if (ui.signedIn) SportsAlertLabels.gameChip(choice) else SportsAlertLabels.SIGN_IN_CHIP,
        selected = ui.signedIn && choice != GameAlertChoice.Default,
        onClick = { if (ui.signedIn) viewModel.cycleGame(eventId, kickoffMs, choice) },
        modifier = if (ui.signedIn) modifier else modifier.focusProperties { canFocus = false },
    )
    Spacer(Modifier.width(12.dp))
}

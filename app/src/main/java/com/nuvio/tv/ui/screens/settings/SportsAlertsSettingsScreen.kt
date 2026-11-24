@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.core.radar.alerts.AlertLevel
import com.nuvio.tv.core.radar.alerts.DuringPlayback
import com.nuvio.tv.core.radar.alerts.SportsAlertLabels
import com.nuvio.tv.core.radar.alerts.SportsAlertSettings
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val ALERTS_TITLE = "Sports alerts"
private const val ALERTS_SUBTITLE = "Kick-offs, goals and full time for the games you follow, while the app is open."

/** Standalone page (own route); the same content is the "Sports alerts" category of Settings. */
@Composable
fun SportsAlertsSettingsScreen(onBackPress: () -> Unit) {
    val initialFocusRequester = remember { FocusRequester() }
    BackHandler { onBackPress() }
    LaunchedEffect(Unit) {
        delay(160L)
        runCatching { initialFocusRequester.requestFocus() }
    }
    SettingsStandaloneScaffold(title = ALERTS_TITLE, subtitle = ALERTS_SUBTITLE, classicContainer = false) {
        SportsAlertsSettingsContent(initialFocusRequester = initialFocusRequester)
    }
}

/**
 * Every row is a settings row (the house focus highlight). Cycle rows step through their choices on OK; the
 * followed leagues and teams below are the mute list. Defaults: on, team = everything, league = full time only,
 * quiet 22:00 to 08:00, six an hour, grouped per minute, live TV only.
 */
@Composable
fun SportsAlertsSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: SportsAlertsSettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val s = ui.settings
    val listState = rememberLazyListState()
    fun first(): Modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = ALERTS_TITLE,
            subtitle = if (ui.signedIn) ALERTS_SUBTITLE else "Sign in to get alerts. These choices sync to your account once you do.",
        )
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "alerts_main") {
                    SettingsGroupCard {
                        SettingsToggleRow(
                            title = "Alerts",
                            subtitle = if (s.enabled) "On for the teams and leagues you follow" else "Off: no banners",
                            checked = s.enabled,
                            onToggle = { viewModel.update { it.copy(enabled = !it.enabled) } },
                            modifier = first(),
                        )
                        SettingsActionRow(
                            title = "Teams you follow",
                            subtitle = "What a followed team sends",
                            value = SportsAlertLabels.level(s.teamLevel),
                            leadingIcon = Icons.Default.NotificationsActive,
                            enabled = s.enabled,
                            onClick = { viewModel.update { it.copy(teamLevel = AlertLevel.next(it.teamLevel)) } },
                        )
                        SettingsActionRow(
                            title = "Leagues you follow",
                            subtitle = "What a followed league sends for games of teams you do not follow",
                            value = SportsAlertLabels.level(s.leagueLevel),
                            leadingIcon = Icons.Default.NotificationsActive,
                            enabled = s.enabled,
                            onClick = { viewModel.update { it.copy(leagueLevel = AlertLevel.next(it.leagueLevel)) } },
                        )
                    }
                }
                item(key = "alerts_quiet") {
                    SettingsGroupCard(title = "Quiet hours", subtitle = "Alerts during quiet hours are skipped, not saved for later.") {
                        SettingsToggleRow(
                            title = "Quiet hours",
                            subtitle = "${SportsAlertLabels.minuteOfDay(s.quietStartMin)} to ${SportsAlertLabels.minuteOfDay(s.quietEndMin)}, this device's time",
                            checked = s.quietOn,
                            onToggle = { viewModel.update { it.copy(quietOn = !it.quietOn) } },
                            enabled = s.enabled,
                        )
                        SettingsActionRow(
                            title = "Quiet from",
                            subtitle = null,
                            value = SportsAlertLabels.minuteOfDay(s.quietStartMin),
                            enabled = s.enabled && s.quietOn,
                            onClick = { viewModel.update { it.copy(quietStartMin = SportsAlertLabels.nextHour(it.quietStartMin)) } },
                        )
                        SettingsActionRow(
                            title = "Quiet until",
                            subtitle = null,
                            value = SportsAlertLabels.minuteOfDay(s.quietEndMin),
                            enabled = s.enabled && s.quietOn,
                            onClick = { viewModel.update { it.copy(quietEndMin = SportsAlertLabels.nextHour(it.quietEndMin)) } },
                        )
                    }
                }
                item(key = "alerts_limits") {
                    SettingsGroupCard(title = "How many") {
                        SettingsActionRow(
                            title = "Most banners per hour",
                            subtitle = "Extra alerts are skipped",
                            value = SportsAlertLabels.maxPerHour(s.maxPerHour),
                            enabled = s.enabled,
                            onClick = { viewModel.update { it.copy(maxPerHour = SportsAlertSettings.nextMaxPerHour(it.maxPerHour)) } },
                        )
                        SettingsActionRow(
                            title = "Group alerts",
                            subtitle = "After a banner, wait and show the next ones together",
                            value = SportsAlertLabels.batch(s.batchSeconds),
                            enabled = s.enabled,
                            onClick = { viewModel.update { it.copy(batchSeconds = SportsAlertSettings.nextBatchSeconds(it.batchSeconds)) } },
                        )
                        SettingsActionRow(
                            title = "While something is playing",
                            subtitle = "Films and shows stay free of banners unless you say otherwise",
                            value = SportsAlertLabels.duringPlayback(s.duringPlayback),
                            enabled = s.enabled,
                            onClick = { viewModel.update { it.copy(duringPlayback = DuringPlayback.next(it.duringPlayback)) } },
                        )
                    }
                }
                if (ui.teams.isNotEmpty()) {
                    item(key = "alerts_teams") {
                        SettingsGroupCard(title = "Teams", subtitle = "Switch a team off to stop its alerts.") {
                            ui.teams.forEach { team ->
                                SettingsToggleRow(
                                    title = team.name,
                                    subtitle = null,
                                    checked = team.id !in s.mutedTeams,
                                    onToggle = { viewModel.update { it.toggleMutedTeam(team.id) } },
                                    enabled = s.enabled,
                                )
                            }
                        }
                    }
                }
                if (ui.leagues.isNotEmpty()) {
                    item(key = "alerts_leagues") {
                        SettingsGroupCard(title = "Leagues", subtitle = "Switch a league off to stop its alerts.") {
                            ui.leagues.forEach { league ->
                                SettingsToggleRow(
                                    title = league.name,
                                    subtitle = null,
                                    checked = league.id !in s.mutedLeagues,
                                    onToggle = { viewModel.update { it.toggleMutedLeague(league.id) } },
                                    enabled = s.enabled,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

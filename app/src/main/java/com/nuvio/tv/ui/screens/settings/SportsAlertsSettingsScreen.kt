@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.core.contracts.SportsAlertsHost
import com.nuvio.tv.core.contracts.SportsAlertsUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Hands upstream-aligned screens the fork's alerts UI and host through the neutral contracts (nothing radar is named here). */
@HiltViewModel
class SportsAlertsUiViewModel @Inject constructor(
    val ui: SportsAlertsUi,
    val host: SportsAlertsHost,
) : ViewModel()

/** Standalone page (own route); the same content is the "Sports alerts" category of Settings. */
@Composable
fun SportsAlertsSettingsScreen(onBackPress: () -> Unit) {
    val initialFocusRequester = remember { FocusRequester() }
    val ui = hiltViewModel<SportsAlertsUiViewModel>().ui
    BackHandler { onBackPress() }
    LaunchedEffect(Unit) {
        delay(160L)
        runCatching { initialFocusRequester.requestFocus() }
    }
    SettingsStandaloneScaffold(title = "Sports alerts", subtitle = "", classicContainer = false) {
        ui.SettingsContent(initialFocusRequester)
    }
}

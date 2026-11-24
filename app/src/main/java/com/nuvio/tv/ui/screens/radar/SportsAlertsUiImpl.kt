package com.nuvio.tv.ui.screens.radar

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.nuvio.tv.core.contracts.SportsAlertsUi
import com.nuvio.tv.core.radar.alerts.SportsAlertController
import javax.inject.Inject
import javax.inject.Singleton

/** The fork's alerts composables behind the neutral [SportsAlertsUi] contract (bound in core/di/SportsAlertsModule). */
@Singleton
class SportsAlertsUiImpl @Inject constructor(
    private val controller: SportsAlertController,
) : SportsAlertsUi {
    @Composable
    override fun SettingsContent(initialFocusRequester: FocusRequester?) {
        SportsAlertsSettingsContent(initialFocusRequester = initialFocusRequester)
    }

    @Composable
    override fun BannerHost(box: BoxScope) {
        with(box) { SportsAlertBannerHost(controller) }
    }
}
